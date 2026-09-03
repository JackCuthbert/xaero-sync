package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.github.jackcuthbert.xaerosync.shared.SyncMessageCodec
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.impl.networking.RegistrationPayload
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

        var sync: ClientConfigurationSync? = null
        var playUpload: ClientPlayUpload? = null
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
                LOGGER.info("Configuration probe succeeded before entering play.")
            } else {
                LOGGER.warn("Configuration probe received unsupported version {}.", response.protocolVersion)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(PlaySyncPayload.TYPE) { payload, _ ->
            runCatching { playUpload?.receive(SyncMessageCodec.decode(payload.bytes)) }
                .onFailure { LOGGER.error("Safety-net upload failed.", it) }
        }

        ClientPlayConnectionEvents.JOIN.register { _, sender, client ->
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
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            playUpload?.flush()
        }

        ClientConfigurationConnectionEvents.START.register { listener, client ->
            ConfigurationProbeHandshake.start(
                registerResponseChannel = {
                    val registration = RegistrationPayload(
                        RegistrationPayload.REGISTER,
                        listOf(ConfigurationProbeResponse.TYPE.id(), ConfigurationSyncPayload.TYPE.id()),
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
