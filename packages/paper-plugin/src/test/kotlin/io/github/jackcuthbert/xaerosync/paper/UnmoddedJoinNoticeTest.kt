package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnmoddedJoinNoticeTest {
    @Test
    fun `only clients without the sync configuration channel get the notice`() {
        assertTrue(UnmoddedJoinNotice.shouldNotify(emptySet()))
        assertFalse(UnmoddedJoinNotice.shouldNotify(setOf(ConnectionSyncProtocol.CHANNEL)))
        assertTrue(UnmoddedJoinNotice.shouldNotify(setOf("other:channel")))
    }

    @Test
    fun `configuration notice is claimed only once at join`() {
        val playerId = UUID.randomUUID()

        UnmoddedJoinNotice.recordConfiguration(playerId, emptySet())

        assertTrue(UnmoddedJoinNotice.takeForJoin(playerId))
        assertFalse(UnmoddedJoinNotice.takeForJoin(playerId))
    }

    @Test
    fun `modded connection clears a pending unmodded notice`() {
        val playerId = UUID.randomUUID()
        UnmoddedJoinNotice.recordConfiguration(playerId, emptySet())
        UnmoddedJoinNotice.recordConfiguration(playerId, setOf(ConnectionSyncProtocol.CHANNEL))

        assertFalse(UnmoddedJoinNotice.takeForJoin(playerId))
    }

    @Test
    fun `notice explains sync and links to the project`() {
        val message = UnmoddedJoinNotice.message()

        assertTrue(message.plainText().contains("Install Xaero Sync"))
        assertTrue(message.plainText().contains("sync waypoints with this server"))
        assertTrue(message.plainText().contains("https://github.com/JackCuthbert/xaero-sync"))
        assertEquals(
            "https://github.com/JackCuthbert/xaero-sync",
            (message.clickEvent()?.payload() as ClickEvent.Payload.Text).value(),
        )
        assertEquals(ClickEvent.Action.OPEN_URL, message.clickEvent()?.action())
    }
}

private fun Component.plainText(): String =
    ((this as? TextComponent)?.content().orEmpty()) + children().joinToString("") { it.plainText() }
