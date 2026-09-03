package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.SyncMessageCodec
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.connection.PlayerConnection
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** Paper entry point and configuration-phase compatibility probe. */
class XaeroSyncPlugin :
    JavaPlugin(),
    PluginMessageListener,
    Listener {
    private lateinit var repository: PlayerSnapshotRepository
    private val sessions = java.util.concurrent.ConcurrentHashMap<java.util.UUID, ServerConfigurationSync>()
    private val completions =
        java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.concurrent.CompletableFuture<Unit>>()

    override fun onEnable() {
        repository = PlayerSnapshotRepository(dataFolder.toPath())
        server.pluginManager.registerEvents(this, this)
        server.messenger.registerIncomingPluginChannel(this, ConfigurationProbe.CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, ConfigurationProbe.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, ConnectionSyncProtocol.CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, ConnectionSyncProtocol.CHANNEL)
    }

    override fun onDisable() {
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) = Unit

    @EventHandler
    fun onConfigure(event: AsyncPlayerConnectionConfigureEvent) {
        if (ConnectionSyncProtocol.CHANNEL !in event.connection.listeningPluginChannels) return
        val playerId = requireNotNull(event.connection.profile.id)
        runCatching {
            completions.computeIfAbsent(playerId) { java.util.concurrent.CompletableFuture() }
                .get(15, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { logger.warning("Configuration sync timed out or failed for $playerId.") }
        completions.remove(playerId)
    }

    override fun onPluginMessageReceived(channel: String, connection: PlayerConnection, message: ByteArray) {
        val configurationConnection = connection as? PlayerConfigurationConnection
        if (channel == ConnectionSyncProtocol.CHANNEL && configurationConnection != null) {
            val playerId = requireNotNull(configurationConnection.profile.id)
            val session = sessions.computeIfAbsent(playerId) {
                ServerConfigurationSync(playerId, repository) { response ->
                    configurationConnection.sendPluginMessage(
                        this,
                        ConnectionSyncProtocol.CHANNEL,
                        SyncMessageCodec.encode(response),
                    )
                }
            }
            val complete = runCatching { session.receive(SyncMessageCodec.decode(message)) }
                .onFailure { logger.warning("Rejected configuration sync for $playerId: ${it.javaClass.simpleName}") }
                .getOrDefault(true)
            if (complete) {
                sessions.remove(playerId)
                completions.computeIfAbsent(playerId) { java.util.concurrent.CompletableFuture() }.complete(Unit)
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
