package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ConfigurationProbePayloadsTest {
    @Test
    fun `configuration start registers the response channel before probing`() {
        val actions = mutableListOf<String>()
        var sent: ConfigurationProbeRequest? = null

        ConfigurationProbeHandshake.start(
            registerResponseChannel = { actions += "register" },
            sendProbe = { request ->
                actions += "probe"
                sent = request
            },
        )

        assertEquals(listOf("register", "probe"), actions)
        assertEquals(ConfigurationProbe.PROTOCOL_VERSION, requireNotNull(sent).protocolVersion)
    }

    @Test
    fun `request uses Minecraft's canonical VarInt wire encoding`() {
        val buffer = Unpooled.buffer()

        ConfigurationProbeRequest.CODEC.encode(
            buffer,
            ConfigurationProbeRequest(ConfigurationProbe.PROTOCOL_VERSION),
        )

        val encoded = ByteArray(buffer.readableBytes())
        buffer.readBytes(encoded)
        assertContentEquals(byteArrayOf(1), encoded)
    }

    @Test
    fun `response decodes the supported protocol version`() {
        val response = ConfigurationProbeResponse.CODEC.decode(Unpooled.wrappedBuffer(byteArrayOf(1)))

        assertEquals(ConfigurationProbe.PROTOCOL_VERSION, response.protocolVersion)
    }
}
