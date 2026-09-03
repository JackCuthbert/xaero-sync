package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
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
            if (ClientConfigurationNetworking.canSend(ConfigurationProbeRequest.TYPE)) {
                ClientConfigurationNetworking.send(ConfigurationProbeRequest(ConfigurationProbe.PROTOCOL_VERSION))
            } else {
                LOGGER.debug("Server does not advertise Xaero Sync's configuration channel.")
            }
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("Xaero Sync")
    }
}
