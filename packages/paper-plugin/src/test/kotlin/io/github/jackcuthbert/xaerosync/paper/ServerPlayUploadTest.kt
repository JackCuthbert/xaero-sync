package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerPlayUploadTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `stale concurrent upload cannot overwrite a newer accepted upload`() {
        val repository = PlayerSnapshotRepository(directory)
        val playerId = UUID.randomUUID()
        val older = snapshot(2, "Older")
        val newer = snapshot(3, "Newer")
        val olderReplies = mutableListOf<SyncMessage>()
        val newerReplies = mutableListOf<SyncMessage>()
        val olderSession = ServerPlayUpload(playerId, repository, olderReplies::add)
        val newerSession = ServerPlayUpload(playerId, repository, newerReplies::add)
        olderSession.receive(SyncMessage.ClientMetadata(SnapshotMetadata.from(older)))
        newerSession.receive(SyncMessage.ClientMetadata(SnapshotMetadata.from(newer)))

        upload(newerSession, newer)
        upload(olderSession, older)

        assertEquals(newer.hash, repository.load(playerId)?.hash)
        assertIs<SyncMessage.TransferAccepted>(newerReplies.last())
        assertEquals("server_newer_reconnect", assertIs<SyncMessage.TransferRejected>(olderReplies.last()).category)
    }

    @Test
    fun `server newer state asks live client to reconnect instead of downloading`() {
        val repository = PlayerSnapshotRepository(directory)
        val playerId = UUID.randomUUID()
        repository.save(playerId, snapshot(4, "Server"))
        val replies = mutableListOf<SyncMessage>()

        ServerPlayUpload(playerId, repository, replies::add)
            .receive(SyncMessage.ClientMetadata(SnapshotMetadata.from(snapshot(3, "Client"))))

        assertEquals("server_newer_reconnect", assertIs<SyncMessage.TransferRejected>(replies.single()).category)
    }

    private fun upload(session: ServerPlayUpload, snapshot: WaypointSnapshot) {
        val transfer = SnapshotTransfer.from(snapshot)
        session.receive(transfer.start)
        transfer.chunks.forEach(session::receive)
    }

    private fun snapshot(second: Long, name: String) = WaypointSnapshot.create(
        listOf(
            WaypointFile(
                "dim%0/mw0.txt",
                "$HEADER\nwaypoint:$name:N:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
            ),
        ),
        Instant.ofEpochSecond(second),
    )

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
