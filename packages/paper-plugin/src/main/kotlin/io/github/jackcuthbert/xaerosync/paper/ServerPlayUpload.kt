package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransferAssembler
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import java.util.UUID

internal class ServerPlayUpload(
    private val playerId: UUID,
    private val repository: PlayerSnapshotRepository,
    private val send: (SyncMessage) -> Unit,
    private val onSaved: (UUID) -> Unit = {},
) {
    private var announced: SnapshotMetadata? = null
    private var incoming: SnapshotTransferAssembler? = null

    @Synchronized
    fun receive(message: SyncMessage): Boolean = try {
        when (message) {
            is SyncMessage.ClientMetadata -> {
                val server = repository.load(playerId)
                if (server == null || message.metadata.updatedAt > server.updatedAt) {
                    announced = message.metadata
                    send(SyncMessage.UploadRequired)
                    false
                } else {
                    send(SyncMessage.TransferRejected("server_newer_reconnect"))
                    true
                }
            }
            is SyncMessage.TransferStart -> {
                incoming = SnapshotTransferAssembler(message)
                false
            }
            is SyncMessage.TransferChunk -> accept(message)
            else -> true
        }
    } catch (_: Exception) {
        send(SyncMessage.TransferRejected("server_processing_failed"))
        true
    }

    private fun accept(chunk: SyncMessage.TransferChunk): Boolean {
        val assembler = requireNotNull(incoming)
        assembler.accept(chunk)
        if (!assembler.isComplete) return false
        val snapshot = assembler.finish()
        val metadata = requireNotNull(announced)
        require(snapshot.hash == metadata.hash && snapshot.updatedAt == metadata.updatedAt)
        val current = repository.load(playerId)
        if (current != null && snapshot.updatedAt <= current.updatedAt) {
            send(SyncMessage.TransferRejected("server_newer_reconnect"))
            return true
        }
        repository.save(playerId, snapshot)
        onSaved(playerId)
        send(SyncMessage.TransferAccepted(snapshot.hash, snapshot.updatedAt))
        return true
    }
}
