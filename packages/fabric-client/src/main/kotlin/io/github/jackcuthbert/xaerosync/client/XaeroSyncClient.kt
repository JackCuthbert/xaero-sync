package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
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

        ClientConfigurationNetworking.registerGlobalReceiver(ConfigurationProbeResponse.TYPE) { response, _ ->
            if (ConfigurationProbe.accepts(response.protocolVersion)) {
                LOGGER.info("Configuration probe succeeded before entering play.")
            } else {
                LOGGER.warn("Configuration probe received unsupported version {}.", response.protocolVersion)
            }
        }

        ClientConfigurationConnectionEvents.START.register { _, _ ->
            ConfigurationProbeHandshake.start(
                registerResponseChannel = {
                    val registration = RegistrationPayload(
                        RegistrationPayload.REGISTER,
                        listOf(ConfigurationProbeResponse.TYPE.id()),
                    )
                    ClientConfigurationNetworking.getSender().sendPacket(ServerboundCustomPayloadPacket(registration))
                },
                sendProbe = ClientConfigurationNetworking::send,
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
