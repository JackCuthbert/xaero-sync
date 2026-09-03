package io.github.jackcuthbert.xaerosync.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurationProbeTest {
    @Test
    fun `accepts only the current protocol version`() {
        assertTrue(ConfigurationProbe.accepts(ConfigurationProbe.PROTOCOL_VERSION))
        assertFalse(ConfigurationProbe.accepts(ConfigurationProbe.PROTOCOL_VERSION - 1))
        assertFalse(ConfigurationProbe.accepts(ConfigurationProbe.PROTOCOL_VERSION + 1))
    }
}
