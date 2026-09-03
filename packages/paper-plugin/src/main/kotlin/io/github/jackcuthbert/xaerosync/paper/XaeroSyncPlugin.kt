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
        if (channel != ConfigurationProbe.CHANNEL || connection !is PlayerConfigurationConnection) {
            return
        }

        val version = message.decodeVarIntOrNull()
        if (version == null || !ConfigurationProbe.accepts(version)) {
            logger.warning("Rejected invalid configuration probe from ${connection.profile.id}.")
            return
        }

        connection.sendPluginMessage(
            this,
            ConfigurationProbe.CHANNEL,
            byteArrayOf(ConfigurationProbe.PROTOCOL_VERSION.toByte()),
        )
        logger.info("Configuration probe succeeded for ${connection.profile.id}.")
    }

    private fun ByteArray.decodeVarIntOrNull(): Int? {
        if (isEmpty() || size > 5) {
            return null
        }

        var result = 0
        forEachIndexed { index, value ->
            result = result or ((value.toInt() and 0x7F) shl (index * 7))
            if (value.toInt() and 0x80 == 0) {
                return result
            }
        }
        return null
    }
}
