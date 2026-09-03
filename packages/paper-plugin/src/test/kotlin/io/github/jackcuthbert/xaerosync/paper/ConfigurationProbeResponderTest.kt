package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConfigurationProbeResponderTest {
    @Test
    fun `configuration probe receives a supported-version response`() {
        val result = ConfigurationProbeResponder.respond(
            ConfigurationProbe.CHANNEL,
            isConfiguration = true,
            byteArrayOf(ConfigurationProbe.PROTOCOL_VERSION.toByte()),
        )

        assertContentEquals(
            byteArrayOf(ConfigurationProbe.PROTOCOL_VERSION.toByte()),
            assertIs<ProbeResult.Response>(result).payload,
        )
    }

    @Test
    fun `ignores another channel and messages outside configuration`() {
        assertEquals(
            ProbeResult.Ignored,
            ConfigurationProbeResponder.respond("other:channel", isConfiguration = true, byteArrayOf(1)),
        )
        assertEquals(
            ProbeResult.Ignored,
            ConfigurationProbeResponder.respond(ConfigurationProbe.CHANNEL, isConfiguration = false, byteArrayOf(1)),
        )
    }

    @Test
    fun `rejects unsupported malformed noncanonical and trailing payloads`() {
        val invalidPayloads = listOf(
            byteArrayOf(),
            byteArrayOf(2),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0x81.toByte(), 0),
            byteArrayOf(1, 0),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x10),
        )

        invalidPayloads.forEach { payload ->
            assertEquals(
                ProbeResult.Rejected,
                ConfigurationProbeResponder.respond(ConfigurationProbe.CHANNEL, isConfiguration = true, payload),
            )
        }
    }
}
