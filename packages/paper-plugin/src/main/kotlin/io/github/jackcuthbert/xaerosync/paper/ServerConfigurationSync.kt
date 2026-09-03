package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransferAssembler
import io.github.jackcuthbert.xaerosync.shared.SyncDecision
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import java.util.UUID

internal class ServerConfigurationSync(
    private val playerId: UUID,
    private val repository: PlayerSnapshotRepository,
    private val send: (SyncMessage) -> Unit,
) {
    private var announced: SnapshotMetadata? = null
    private var incoming: SnapshotTransferAssembler? = null

    @Synchronized
    fun receive(message: SyncMessage): Boolean = try {
        when (message) {
            is SyncMessage.ClientMetadata -> decide(message.metadata)
            is SyncMessage.TransferStart -> {
                check(announced != null && incoming == null)
                incoming = SnapshotTransferAssembler(message)
                false
            }
            is SyncMessage.TransferChunk -> accept(message)
            is SyncMessage.TransferAccepted -> true
            is SyncMessage.TransferRejected -> true
            else -> error("Unexpected client sync message.")
        }
    } catch (_: Exception) {
        send(SyncMessage.TransferRejected("server_processing_failed"))
        true
    }

    private fun decide(metadata: SnapshotMetadata): Boolean {
        check(announced == null)
        when (val decision = ConnectionSyncProtocol.decide(metadata, repository.load(playerId))) {
            SyncDecision.UploadRequired -> {
                announced = metadata
                send(SyncMessage.UploadRequired)
                return false
            }
            is SyncDecision.DownloadRequired -> {
                val transfer = SnapshotTransfer.from(decision.snapshot)
                send(transfer.start)
                transfer.chunks.forEach(send)
                return false
            }
            is SyncDecision.InSync -> {
                send(SyncMessage.InSync(decision.hash, decision.updatedAt))
                return true
            }
        }
    }

    private fun accept(chunk: SyncMessage.TransferChunk): Boolean {
        val assembler = requireNotNull(incoming)
        assembler.accept(chunk)
        if (!assembler.isComplete) return false
        val snapshot = assembler.finish()
        val metadata = requireNotNull(announced)
        require(
            snapshot.hash == metadata.hash &&
                snapshot.updatedAt == metadata.updatedAt &&
                snapshot.manifest == metadata.manifest,
        )
        val current = repository.load(playerId)
        if (current != null && snapshot.updatedAt <= current.updatedAt) {
            val transfer = SnapshotTransfer.from(current)
            send(transfer.start)
            transfer.chunks.forEach(send)
            return false
        }
        repository.save(playerId, snapshot)
        send(SyncMessage.TransferAccepted(snapshot.hash, snapshot.updatedAt))
        return true
    }
}
