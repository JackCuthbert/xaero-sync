package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import io.github.jackcuthbert.xaerosync.shared.WaypointSubscriptionRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointSubscriptionManagerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val subscriber = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val source = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val clock = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `source update prompts once and dismiss leaves subscriber untouched`() {
        val fixture = fixture()
        val original = snapshot("Mine", 1)
        fixture.snapshots.save(subscriber, original)
        fixture.snapshots.save(source, snapshot("Source first", 2))
        fixture.manager.subscribe(subscriber, source, "Friend")
        fixture.snapshots.save(source, snapshot("Source update", 3))

        fixture.manager.sourceChanged(source)
        fixture.manager.sourceChanged(source)

        val update = fixture.updates.single().second
        assertEquals(source, update.sourceId)
        assertTrue(fixture.manager.dismiss(subscriber, source, update.snapshot.hash))
        fixture.manager.subscriberJoined(subscriber)
        assertEquals(1, fixture.updates.size)
        assertEquals(original.hash, fixture.snapshots.load(subscriber)?.hash)
    }

    @Test
    fun `remind later prompts on next join but not again during play`() {
        val fixture = fixture()
        fixture.snapshots.save(source, snapshot("First", 1))
        fixture.manager.subscribe(subscriber, source, "Friend")
        fixture.snapshots.save(source, snapshot("Second", 2))
        fixture.manager.sourceChanged(source)
        val update = fixture.updates.single().second

        assertTrue(fixture.manager.remindLater(subscriber, source, update.snapshot.hash))
        fixture.manager.sourceChanged(source)
        assertEquals(1, fixture.updates.size)

        fixture.manager.subscriberJoined(subscriber)
        assertEquals(2, fixture.updates.size)
        fixture.manager.subscriberJoined(subscriber)
        assertEquals(2, fixture.updates.size)
    }

    @Test
    fun `accepted update preserves current set and waits for normal reconnect download`() {
        val fixture = fixture()
        val original = snapshot("Mine", 1)
        fixture.snapshots.save(subscriber, original)
        fixture.snapshots.save(source, snapshot("First", 2))
        fixture.manager.subscribe(subscriber, source, "Friend")
        val update = snapshot("Shared", 3)
        fixture.snapshots.save(source, update)
        fixture.manager.sourceChanged(source)

        assertTrue(fixture.manager.accept(subscriber, source, update.hash))

        assertEquals(update.hash, fixture.snapshots.load(subscriber)?.hash)
        assertEquals(original.hash, fixture.snapshots.listSnapshots(subscriber).single().hash)
        assertEquals(clock.instant(), fixture.snapshots.load(subscriber)?.updatedAt)
    }

    @Test
    fun `accepted prompt cannot replace a later subscriber backup again`() {
        val fixture = fixture()
        fixture.snapshots.save(source, snapshot("First", 1))
        fixture.manager.subscribe(subscriber, source, "Friend")
        val update = snapshot("Shared", 2)
        fixture.snapshots.save(source, update)
        fixture.manager.sourceChanged(source)
        assertTrue(fixture.manager.accept(subscriber, source, update.hash))

        val later = snapshot("My later changes", 3)
        fixture.snapshots.save(subscriber, later)

        assertFalse(fixture.manager.accept(subscriber, source, update.hash))
        assertEquals(later.hash, fixture.snapshots.load(subscriber)?.hash)
    }

    @Test
    fun `offline update remains pending and stale prompt cannot replace a newer source`() {
        var online = false
        val fixture = fixture { online }
        val original = snapshot("Mine", 1)
        fixture.snapshots.save(subscriber, original)
        fixture.snapshots.save(source, snapshot("First", 2))
        fixture.manager.subscribe(subscriber, source, "Friend")
        val offered = snapshot("Offered", 3)
        fixture.snapshots.save(source, offered)

        fixture.manager.sourceChanged(source)
        assertTrue(fixture.updates.isEmpty())
        online = true
        fixture.manager.subscriberJoined(subscriber)
        assertEquals(1, fixture.updates.size)

        fixture.snapshots.save(source, snapshot("Newer", 4))
        assertFalse(fixture.manager.accept(subscriber, source, offered.hash))
        assertEquals(original.hash, fixture.snapshots.load(subscriber)?.hash)
    }

    @Test
    fun `one failed notification does not stop other subscribers`() {
        val snapshots = PlayerSnapshotRepository(temporaryDirectory, clock)
        val records = WaypointSubscriptionRepository(temporaryDirectory)
        val secondSubscriber = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val notified = mutableListOf<UUID>()
        var failedSubscriber = subscriber
        val manager = WaypointSubscriptionManager(snapshots, records) { playerId, _, delivered ->
            if (playerId == failedSubscriber) error("Notification failed")
            notified += playerId
            delivered(true)
        }
        snapshots.save(source, snapshot("First", 1))
        manager.subscribe(subscriber, source, "Friend")
        manager.subscribe(secondSubscriber, source, "Friend")
        val subscriberOrder = records.subscribers(source)
        failedSubscriber = subscriberOrder.first()
        snapshots.save(source, snapshot("New", 2))

        manager.sourceChanged(source)

        assertEquals(listOf(subscriberOrder.last()), notified)
    }

    @Test
    fun `update stays pending when player leaves before scheduled notification`() {
        val snapshots = PlayerSnapshotRepository(temporaryDirectory, clock)
        val records = WaypointSubscriptionRepository(temporaryDirectory)
        val scheduled = mutableListOf<() -> Unit>()
        val received = mutableListOf<SubscriptionUpdate>()
        var online = true
        val manager = WaypointSubscriptionManager(snapshots, records) { _, update, delivered ->
            scheduled += {
                if (online) received += update
                delivered(online)
            }
        }
        snapshots.save(source, snapshot("First", 1))
        manager.subscribe(subscriber, source, "Friend")
        snapshots.save(source, snapshot("New", 2))

        manager.sourceChanged(source)
        online = false
        scheduled.removeAt(0)()
        online = true
        manager.subscriberJoined(subscriber)

        assertEquals(1, scheduled.size)
        scheduled.single()()
        assertEquals(1, received.size)
    }

    private fun fixture(isOnline: () -> Boolean = { true }): Fixture {
        val snapshots = PlayerSnapshotRepository(temporaryDirectory, clock)
        val updates = mutableListOf<Pair<UUID, SubscriptionUpdate>>()
        val manager = WaypointSubscriptionManager(
            snapshots,
            WaypointSubscriptionRepository(temporaryDirectory),
        ) { playerId, update, delivered ->
            if (!isOnline()) {
                delivered(false)
                return@WaypointSubscriptionManager
            }
            updates += playerId to update
            delivered(true)
        }
        return Fixture(snapshots, manager, updates)
    }

    private fun snapshot(name: String, second: Long) = WaypointSnapshot.create(
        listOf(
            WaypointFile(
                "dim%0/mw0.txt",
                "#\nwaypoint:$name:H:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
            ),
        ),
        Instant.ofEpochSecond(second),
    )

    private data class Fixture(
        val snapshots: PlayerSnapshotRepository,
        val manager: WaypointSubscriptionManager,
        val updates: MutableList<Pair<UUID, SubscriptionUpdate>>,
    )
}
