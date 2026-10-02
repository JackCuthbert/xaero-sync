package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransferAssembler
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import io.github.jackcuthbert.xaerosync.shared.WaypointSubscriptionRepository
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WaypointSubscriptionSyncTest {
    @TempDir
    lateinit var directory: Path

    private val source = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val subscriber = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val clock = Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC)

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `uploaded source update is offered and accepted set downloads only on reconnect`(configuration: Boolean) {
        val snapshots = PlayerSnapshotRepository(directory, clock)
        val updates = mutableListOf<SubscriptionUpdate>()
        val manager =
            WaypointSubscriptionManager(snapshots, WaypointSubscriptionRepository(directory)) { _, update, delivered ->
                updates += update
                delivered(true)
            }
        val original = snapshot("Mine", 1)
        snapshots.save(subscriber, original)
        snapshots.save(source, snapshot("First", 2))
        manager.subscribe(subscriber, source, "Friend")
        val uploaded = snapshot("Shared", 3)
        val replies = mutableListOf<SyncMessage>()
        val receive = receiver(configuration, snapshots, replies, manager)

        receive(SyncMessage.ClientMetadata(SnapshotMetadata.from(uploaded)))
        val transfer = SnapshotTransfer.from(uploaded)
        receive(transfer.start)
        assertTrue(updates.isEmpty())
        transfer.chunks.forEach { receive(it) }

        assertIs<SyncMessage.TransferAccepted>(replies.last())
        assertEquals(uploaded.hash, updates.single().snapshot.hash)
        assertEquals(original.hash, snapshots.load(subscriber)?.hash)
        assertTrue(snapshots.listSnapshots(subscriber).isEmpty())

        assertTrue(manager.accept(subscriber, source, uploaded.hash))
        assertEquals(original.hash, snapshots.listSnapshots(subscriber).single().hash)
        val reconnectReplies = mutableListOf<SyncMessage>()
        val reconnect = ServerConfigurationSync(subscriber, snapshots, reconnectReplies::add)
        reconnect.receive(SyncMessage.ClientMetadata(SnapshotMetadata.from(original)))
        val download = SnapshotTransferAssembler(assertIs<SyncMessage.TransferStart>(reconnectReplies.first()))
        reconnectReplies.drop(1).forEach { download.accept(assertIs<SyncMessage.TransferChunk>(it)) }

        val downloaded = download.finish()
        assertEquals(uploaded.hash, downloaded.hash)
        assertEquals(clock.instant(), downloaded.updatedAt)
        assertTrue(reconnect.receive(SyncMessage.TransferAccepted(uploaded.hash, clock.instant())))
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `rejected source upload neither changes canonical set nor prompts subscribers`(configuration: Boolean) {
        val snapshots = PlayerSnapshotRepository(directory, clock)
        val updates = mutableListOf<SubscriptionUpdate>()
        val manager =
            WaypointSubscriptionManager(snapshots, WaypointSubscriptionRepository(directory)) { _, update, delivered ->
                updates += update
                delivered(true)
            }
        val original = snapshot("First", 1)
        snapshots.save(source, original)
        manager.subscribe(subscriber, source, "Friend")
        val replies = mutableListOf<SyncMessage>()
        val receive = receiver(configuration, snapshots, replies, manager)
        receive(SyncMessage.ClientMetadata(SnapshotMetadata.from(snapshot("Announced", 2))))
        val different = SnapshotTransfer.from(snapshot("Different", 2))
        receive(different.start)
        different.chunks.forEach { receive(it) }

        assertIs<SyncMessage.TransferRejected>(replies.last())
        assertEquals(original.hash, snapshots.load(source)?.hash)
        assertTrue(updates.isEmpty())
    }

    private fun receiver(
        configuration: Boolean,
        snapshots: PlayerSnapshotRepository,
        replies: MutableList<SyncMessage>,
        manager: WaypointSubscriptionManager,
    ): (SyncMessage) -> Boolean = if (configuration) {
        ServerConfigurationSync(source, snapshots, replies::add, manager::sourceChanged)::receive
    } else {
        ServerPlayUpload(source, snapshots, replies::add, manager::sourceChanged)::receive
    }

    private fun snapshot(name: String, second: Long) = WaypointSnapshot.create(
        listOf(
            WaypointFile(
                "dim%0/mw0.txt",
                (
                    "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
                        "rotate_on_tp:tp_yaw:visibility_type:destination\n" +
                        "waypoint:$name:H:1:2:3:1:false:0:set:false:0:0:false"
                    ).toByteArray(),
            ),
        ),
        Instant.ofEpochSecond(second),
    )
}
