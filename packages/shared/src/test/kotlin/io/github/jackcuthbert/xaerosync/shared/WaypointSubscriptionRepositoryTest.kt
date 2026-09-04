package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointSubscriptionRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val subscriber = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val source = UUID.fromString("00000000-0000-4000-8000-000000000002")

    @Test
    fun `subscriptions and prompt decisions survive restart`() {
        val repository = WaypointSubscriptionRepository(temporaryDirectory)
        repository.subscribe(subscriber, source, "Friend", "initial")
        repository.markPrompted(subscriber, source, "update")
        repository.remindLater(subscriber, source, "update")

        val restored = WaypointSubscriptionRepository(temporaryDirectory).subscriptions(subscriber).single()

        assertEquals(source, restored.sourceId)
        assertEquals("Friend", restored.sourceName)
        assertEquals(SubscriptionPolicy.PROMPT, restored.policy)
        assertFalse(restored.shouldPrompt("update", joining = false))
        assertTrue(restored.shouldPrompt("update", joining = true))
        assertEquals(listOf(subscriber), repository.subscribers(source))
    }

    @Test
    fun `dismissed snapshot stays quiet while a newer snapshot is offered`() {
        val repository = WaypointSubscriptionRepository(temporaryDirectory)
        repository.subscribe(subscriber, source, "Friend", "initial")
        repository.markPrompted(subscriber, source, "first")
        repository.dismiss(subscriber, source, "first")

        val subscription = repository.subscriptions(subscriber).single()

        assertFalse(subscription.shouldPrompt("first", joining = true))
        assertTrue(subscription.shouldPrompt("second", joining = false))
        assertTrue(repository.unsubscribe(subscriber, source))
        assertTrue(repository.subscriptions(subscriber).isEmpty())
    }
}
