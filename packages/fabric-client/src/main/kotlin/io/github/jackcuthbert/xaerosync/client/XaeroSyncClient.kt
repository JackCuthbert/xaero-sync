package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.github.jackcuthbert.xaerosync.shared.ModVersionReport
import io.github.jackcuthbert.xaerosync.shared.SyncMessageCodec
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.impl.networking.RegistrationPayload
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import org.slf4j.LoggerFactory

/** Client entry point and configuration-phase compatibility probe. */
class XaeroSyncClient : ClientModInitializer {
    override fun onInitializeClient() {
        PayloadTypeRegistry.serverboundConfiguration().register(
            ConfigurationProbeRequest.TYPE,
            ConfigurationProbeRequest.CODEC,
        )
        PayloadTypeRegistry.clientboundConfiguration().register(
            ConfigurationProbeResponse.TYPE,
            ConfigurationProbeResponse.CODEC,
        )
        PayloadTypeRegistry.serverboundConfiguration().register(
            ConfigurationSyncPayload.TYPE,
            ConfigurationSyncPayload.CODEC,
        )
        PayloadTypeRegistry.clientboundConfiguration().register(
            ConfigurationSyncPayload.TYPE,
            ConfigurationSyncPayload.CODEC,
        )
        PayloadTypeRegistry.serverboundPlay().register(PlaySyncPayload.TYPE, PlaySyncPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(PlaySyncPayload.TYPE, PlaySyncPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(VersionReportPayload.TYPE, VersionReportPayload.CODEC)

        var sync: ClientConfigurationSync? = null
        var playUpload: ClientPlayUpload? = null
        var sampleSelectionAfterJoin = false
        ClientConfigurationNetworking.registerGlobalReceiver(ConfigurationSyncPayload.TYPE) { payload, context ->
            val responses = runCatching { requireNotNull(sync).receive(SyncMessageCodec.decode(payload.bytes)) }
                .onFailure { LOGGER.error("Configuration sync failed.", it) }
                .getOrDefault(emptyList())
            responses.forEach {
                context.responseSender().sendPacket(ConfigurationSyncPayload(SyncMessageCodec.encode(it)))
            }
        }

        ClientConfigurationNetworking.registerGlobalReceiver(ConfigurationProbeResponse.TYPE) { response, _ ->
            if (ConfigurationProbe.accepts(response.protocolVersion)) {
                LOGGER.debug("Configuration probe succeeded before entering play.")
            } else {
                LOGGER.warn("Configuration probe received unsupported version {}.", response.protocolVersion)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PlaySyncPayload.TYPE) { payload, _ ->
            runCatching { playUpload?.receive(SyncMessageCodec.decode(payload.bytes)) }
                .onFailure { LOGGER.error("Safety-net upload failed.", it) }
        }

        ClientPlayConnectionEvents.JOIN.register { _, sender, client ->
            val version = FabricLoader.getInstance().getModContainer(
                "xaero-sync",
            ).orElseThrow().metadata.version.friendlyString
            val versionBytes = version.toByteArray(Charsets.UTF_8)
            if (versionBytes.size <= ModVersionReport.MAX_BYTES) {
                sender.sendPacket(VersionReportPayload(versionBytes))
            }
            sampleSelectionAfterJoin = true
            val address = client.currentServer?.ip ?: return@register
            playUpload?.close()
            playUpload = ClientPlayUpload(
                XaeroConnectionScope.from(client.gameDirectory.toPath(), address),
            ) { message ->
                sender.sendPacket(PlaySyncPayload(SyncMessageCodec.encode(message)))
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            playUpload?.close()
            playUpload = null
            sync = null
            sampleSelectionAfterJoin = false
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val configurationSync = sync ?: return@register
            if (!sampleSelectionAfterJoin && !configurationSync.hasStagedDownloads()) return@register
            val target = XaeroAutomaticWorldTarget.current(
                reportSelection = sampleSelectionAfterJoin,
            ) ?: return@register
            sampleSelectionAfterJoin = false
            configurationSync.discoverTarget(target).takeIf(List<String>::isNotEmpty)?.let { dimensions ->
                notifyReconnectRequired(client, dimensions)
            }
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            playUpload?.flush()
        }

        ClientConfigurationConnectionEvents.START.register { listener, client ->
            ConfigurationProbeHandshake.start(
                registerResponseChannel = {
                    val registration = RegistrationPayload(
                        RegistrationPayload.REGISTER,
                        listOf(
                            ConfigurationProbeResponse.TYPE.id(),
                            ConfigurationSyncPayload.TYPE.id(),
                            VersionReportPayload.TYPE.id(),
                        ),
                    )
                    ClientConfigurationNetworking.getSender().sendPacket(ServerboundCustomPayloadPacket(registration))
                },
                sendProbe = ClientConfigurationNetworking::send,
            )
            val address = requireNotNull(listener.serverData) { "Configuration connection has no server address." }.ip
            sync = ClientConfigurationSync(XaeroConnectionScope.from(client.gameDirectory.toPath(), address))
            ClientConfigurationNetworking.send(
                ConfigurationSyncPayload(SyncMessageCodec.encode(requireNotNull(sync).start())),
            )
            LOGGER.debug("Sent configuration probe before entering play.")
        }
    }

    private fun notifyReconnectRequired(client: Minecraft, dimensions: List<String>) {
        client.player?.sendSystemMessage(
            Component.literal(
                "Xaero Sync: new waypoints downloaded for ${dimensions.joinToString {
                    displayDimension(it)
                }}; reconnect to load them.",
            ),
        )
        LOGGER.info(
            "Selected automatic-world restore targets after join; reconnect is required to apply downloaded waypoints.",
        )
    }

    private fun displayDimension(directory: String): String = when (directory) {
        "dim%0" -> "Overworld"
        "dim%-1" -> "Nether"
        "dim%1" -> "End"
        else -> directory.removePrefix("dim%")
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("Xaero Sync")
    }
}

internal object ConfigurationProbeHandshake {
    fun start(registerResponseChannel: () -> Unit, sendProbe: (ConfigurationProbeRequest) -> Unit) {
        registerResponseChannel()
        sendProbe(ConfigurationProbeRequest(ConfigurationProbe.PROTOCOL_VERSION))
    }
}
