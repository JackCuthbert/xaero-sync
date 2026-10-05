package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.github.jackcuthbert.xaerosync.shared.ModVersionReport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientVersionNoticeTest {
    @Test
    fun `notifies older releases and prereleases but not matching or newer`() {
        assertTrue(ClientVersionNotice.shouldNotify("0.9.9", "0.10.0"))
        assertTrue(ClientVersionNotice.shouldNotify("0.10.0-rc.1", "0.10.0"))
        assertFalse(ClientVersionNotice.shouldNotify("0.10.0", "0.10.0"))
        assertFalse(ClientVersionNotice.shouldNotify("0.11.0", "0.10.0"))
        assertFalse(ClientVersionNotice.shouldNotify("invalid", "0.10.0"))
    }

    @Test
    fun `bounds and strictly decodes the version report`() {
        assertEquals("1.2.3", ClientVersionNotice.decode("1.2.3".toByteArray()))
        assertNull(ClientVersionNotice.decode(ByteArray(129)))
        assertNull(ClientVersionNotice.decode(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test
    fun `join policy warns legacy modded clients and waits for capable client reports`() {
        val legacy = UUID.randomUUID()
        ClientVersionNotice.configure(legacy, setOf(ConnectionSyncProtocol.CHANNEL))
        assertFalse(ClientVersionNotice.report(legacy, "malformed", "1.1.2"))
        assertTrue(ClientVersionNotice.join(legacy, "1.1.2"))
        assertFalse(ClientVersionNotice.join(legacy, "1.1.2"))
        ClientVersionNotice.clear(legacy)

        val reportBeforeJoin = UUID.randomUUID()
        ClientVersionNotice.configure(reportBeforeJoin, setOf(ConnectionSyncProtocol.CHANNEL, ModVersionReport.CHANNEL))
        assertFalse(ClientVersionNotice.report(reportBeforeJoin, "1.1.0", "1.1.2"))
        assertTrue(ClientVersionNotice.join(reportBeforeJoin, "1.1.2"))
        ClientVersionNotice.clear(reportBeforeJoin)

        val reportAfterJoin = UUID.randomUUID()
        ClientVersionNotice.configure(reportAfterJoin, setOf(ConnectionSyncProtocol.CHANNEL, ModVersionReport.CHANNEL))
        assertFalse(ClientVersionNotice.join(reportAfterJoin, "1.1.2"))
        assertFalse(ClientVersionNotice.report(reportAfterJoin, "1.1.2", "1.1.2"))
        assertFalse(ClientVersionNotice.report(reportAfterJoin, "1.2.0", "1.1.2"))
        ClientVersionNotice.clear(reportAfterJoin)
    }
}
