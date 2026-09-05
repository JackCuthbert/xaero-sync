package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.SnapshotRetention
import io.github.jackcuthbert.xaerosync.shared.SyncMessageCodec
import io.github.jackcuthbert.xaerosync.shared.WaypointSubscriptionRepository
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.connection.PlayerConnection
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** Paper entry point and configuration-phase compatibility probe. */
class XaeroSyncPlugin :
    JavaPlugin(),
    PluginMessageListener,
    Listener {
    private lateinit var repository: PlayerSnapshotRepository
    private lateinit var subscriptions: WaypointSubscriptionManager
    private val onlinePlayerIds = ConcurrentHashMap.newKeySet<java.util.UUID>()
    private val sessions = ConcurrentHashMap<PlayerConfigurationConnection, ServerConfigurationSync>()
    private val playUploads = ConcurrentHashMap<Player, ServerPlayUpload>()
    private val completions = ConcurrentHashMap<PlayerConfigurationConnection, CompletableFuture<Unit>>()
    private val storageExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("xaero-sync-storage").factory(),
    )

    override fun onEnable() {
        saveDefaultConfig()
        repository = PlayerSnapshotRepository(
            dataFolder.toPath(),
            retention = snapshotRetention(),
        ) { path, error ->
            logger.warning("Could not read or remove snapshot $path: ${error.message}")
        }
        subscriptions = WaypointSubscriptionManager(
            repository,
            WaypointSubscriptionRepository(dataFolder.toPath()) { path, error ->
                logger.warning("Could not read subscription record $path: ${error.message}")
            },
        ) { subscriberId, update ->
            if (subscriberId !in onlinePlayerIds) return@WaypointSubscriptionManager false
            server.scheduler.runTask(this) { _ ->
                val player = server.getPlayer(subscriberId) ?: return@runTask
                player.sendMessage(subscriptionPrompt(update))
            }
            true
        }
        onlinePlayerIds.addAll(server.onlinePlayers.map { it.uniqueId })
        val command = requireNotNull(getCommand("xaerosync"))
        val handler = XaeroSyncCommand(this, repository, subscriptions)
        command.setExecutor(handler)
        command.tabCompleter = handler
        server.pluginManager.registerEvents(this, this)
        server.messenger.registerIncomingPluginChannel(this, ConfigurationProbe.CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, ConfigurationProbe.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, ConnectionSyncProtocol.CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, ConnectionSyncProtocol.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, ConnectionSyncProtocol.PLAY_CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, ConnectionSyncProtocol.PLAY_CHANNEL)
    }

    override fun onDisable() {
        storageExecutor.close()
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
    }

    internal fun runStorage(operation: () -> Unit) {
        storageExecutor.submit(operation)
    }

    private fun snapshotRetention(): SnapshotRetention {
        val configured = config.getInt("snapshots.max-per-player", SnapshotRetention.DEFAULT_MAXIMUM_PER_PLAYER)
        return when {
            configured == -1 -> SnapshotRetention.UNLIMITED
            configured > 0 -> SnapshotRetention(configured)
            else -> {
                logger.warning(
                    "snapshots.max-per-player must be positive or -1; using ${SnapshotRetention.DEFAULT_MAXIMUM_PER_PLAYER}.",
                )
                SnapshotRetention.DEFAULT
            }
        }
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != ConnectionSyncProtocol.PLAY_CHANNEL) return
        val playerId = player.uniqueId
        storageExecutor.submit {
            val upload = playUploads.computeIfAbsent(player) {
                ServerPlayUpload(playerId, repository, { response ->
                    server.scheduler.runTask(this) { _ ->
                        if (player.isOnline) player.sendPluginMessage(this, channel, SyncMessageCodec.encode(response))
                    }
                }, subscriptions::sourceChanged)
            }
            if (runCatching { upload.receive(SyncMessageCodec.decode(message)) }.getOrDefault(true)) {
                playUploads.remove(player)
            }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        onlinePlayerIds += event.player.uniqueId
        runStorage { subscriptions.subscriberJoined(event.player.uniqueId) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        onlinePlayerIds -= event.player.uniqueId
    }

    @EventHandler
    fun onConfigure(event: AsyncPlayerConnectionConfigureEvent) {
        if (ConnectionSyncProtocol.CHANNEL !in event.connection.listeningPluginChannels) return
        val playerId = requireNotNull(event.connection.profile.id)
        runCatching {
            completions.computeIfAbsent(event.connection) { CompletableFuture() }
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { logger.warning("Configuration sync timed out or failed for $playerId.") }
        completions.remove(event.connection)
    }

    override fun onPluginMessageReceived(channel: String, connection: PlayerConnection, message: ByteArray) {
        val configurationConnection = connection as? PlayerConfigurationConnection
        if (channel == ConnectionSyncProtocol.CHANNEL && configurationConnection != null) {
            val playerId = requireNotNull(configurationConnection.profile.id)
            storageExecutor.submit {
                val session = sessions.computeIfAbsent(configurationConnection) {
                    ServerConfigurationSync(playerId, repository, { response ->
                        configurationConnection.sendPluginMessage(
                            this,
                            ConnectionSyncProtocol.CHANNEL,
                            SyncMessageCodec.encode(response),
                        )
                    }, subscriptions::sourceChanged)
                }
                val complete = runCatching { session.receive(SyncMessageCodec.decode(message)) }
                    .onFailure {
                        logger.warning("Rejected configuration sync for $playerId: ${it.javaClass.simpleName}")
                    }
                    .getOrDefault(true)
                if (complete) {
                    sessions.remove(configurationConnection)
                    completions.computeIfAbsent(configurationConnection) {
                        CompletableFuture()
                    }.complete(Unit)
                }
            }
            return
        }
        when (
            val result = ConfigurationProbeResponder.respond(
                channel,
                configurationConnection != null,
                message,
            )
        ) {
            ProbeResult.Ignored -> return
            ProbeResult.Rejected -> {
                logger.warning("Rejected invalid configuration probe from ${configurationConnection?.profile?.id}.")
                return
            }
            is ProbeResult.Response -> requireNotNull(configurationConnection).sendPluginMessage(
                this,
                ConfigurationProbe.CHANNEL,
                result.payload,
            )
        }
        logger.info("Configuration probe succeeded for ${requireNotNull(configurationConnection).profile.id}.")
    }
}
