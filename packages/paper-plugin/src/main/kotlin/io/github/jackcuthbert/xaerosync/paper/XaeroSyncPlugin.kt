package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.papermc.paper.connection.PlayerConfigurationConnection
import io.papermc.paper.connection.PlayerConnection
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener

/** Paper entry point and configuration-phase compatibility probe. */
class XaeroSyncPlugin :
    JavaPlugin(),
    PluginMessageListener {
    override fun onEnable() {
        server.messenger.registerIncomingPluginChannel(this, ConfigurationProbe.CHANNEL, this)
        server.messenger.registerOutgoingPluginChannel(this, ConfigurationProbe.CHANNEL)
    }

    override fun onDisable() {
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
    }

    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) = Unit

    override fun onPluginMessageReceived(channel: String, connection: PlayerConnection, message: ByteArray) {
        val configurationConnection = connection as? PlayerConfigurationConnection
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
