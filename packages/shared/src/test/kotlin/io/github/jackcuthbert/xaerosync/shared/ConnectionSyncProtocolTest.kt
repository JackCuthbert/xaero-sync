package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionSyncProtocolTest {
    @Test
    fun `fresh server requests the client snapshot`() {
        assertEquals(SyncDecision.UploadRequired, ConnectionSyncProtocol.decide(metadata(at(1)), null))
    }

    @Test
    fun `newer side wins and server wins an exact timestamp conflict`() {
        assertEquals(
            SyncDecision.UploadRequired,
            ConnectionSyncProtocol.decide(metadata(at(3)), snapshot(at(2))),
        )
        assertIs<SyncDecision.DownloadRequired>(
            ConnectionSyncProtocol.decide(metadata(at(1)), snapshot(at(2))),
        )

        val conflictingClient = SnapshotMetadata("0".repeat(64), at(2), emptyList())
        assertIs<SyncDecision.DownloadRequired>(
            ConnectionSyncProtocol.decide(conflictingClient, snapshot(at(2))),
        )
    }

    @Test
    fun `matching metadata is already in sync`() {
        val snapshot = snapshot(at(2))
        assertEquals(
            SyncDecision.InSync(snapshot.hash, snapshot.updatedAt),
            ConnectionSyncProtocol.decide(SnapshotMetadata.from(snapshot), snapshot),
        )
    }

    @Test
    fun `serializer round trips metadata and binary chunks`() {
        val metadata = SyncMessage.ClientMetadata(metadata(at(1)))
        assertEquals(metadata, SyncMessageCodec.decode(SyncMessageCodec.encode(metadata)))

        val bytes = ByteArray(ConnectionSyncProtocol.CHUNK_BYTES) { (it % 251).toByte() }
        val chunk = SyncMessage.TransferChunk(UUID.randomUUID(), 4, bytes)
        val decoded = assertIs<SyncMessage.TransferChunk>(SyncMessageCodec.decode(SyncMessageCodec.encode(chunk)))
        assertEquals(chunk.transferId, decoded.transferId)
        assertEquals(chunk.index, decoded.index)
        assertTrue(bytes.contentEquals(decoded.bytes))
    }

    @Test
    fun `chunked snapshot is reassembled only when complete and valid`() {
        val transfer = SnapshotTransfer.from(
            largeSnapshot(at(4)),
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        assertTrue(transfer.chunks.size > 1)
        val assembler = SnapshotTransferAssembler(transfer.start)
        transfer.chunks.forEach(assembler::accept)
        val result = assembler.finish()
        assertEquals(largeSnapshot(at(4)).hash, result.hash)
        assertEquals(at(4), result.updatedAt)
    }

    @Test
    fun `interrupted and corrupted transfers are rejected`() {
        val transfer = SnapshotTransfer.from(largeSnapshot(at(4)))
        val interrupted = SnapshotTransferAssembler(transfer.start)
        interrupted.accept(transfer.chunks.first())
        assertThrows<IllegalArgumentException>(interrupted::finish)

        val corrupted = SnapshotTransferAssembler(transfer.start)
        transfer.chunks.forEachIndexed { index, chunk ->
            val bytes = chunk.bytes
            if (index == transfer.chunks.lastIndex) {
                bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
            }
            corrupted.accept(SyncMessage.TransferChunk(chunk.transferId, chunk.index, bytes))
        }
        assertThrows<IllegalArgumentException>(corrupted::finish)
    }

    @Test
    fun `malformed envelopes are rejected`() {
        assertThrows<InvalidSyncMessageException> {
            SyncMessageCodec.decode("""{"type":"transfer_rejected","category":"NOT ALLOWED"}""".toByteArray())
        }
        assertThrows<InvalidSyncMessageException> {
            SyncMessageCodec.decode("""{"type":"unknown"}""".toByteArray())
        }
    }

    private fun metadata(updatedAt: Instant) = SnapshotMetadata.from(snapshot(updatedAt))

    private fun snapshot(updatedAt: Instant) = WaypointSnapshot.create(
        listOf(WaypointFile("dim%0/mw0,1,0_1.txt", WAYPOINT.toByteArray())),
        updatedAt,
    )

    private fun largeSnapshot(updatedAt: Instant) = WaypointSnapshot.create(
        listOf(WaypointFile("dim%0/mw0,1,0_1.txt", (WAYPOINT + "#".repeat(40_000)).toByteArray())),
        updatedAt,
    )

    private fun at(second: Long) = Instant.ofEpochSecond(second)

    private companion object {
        const val WAYPOINT = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination\n"
    }
}
