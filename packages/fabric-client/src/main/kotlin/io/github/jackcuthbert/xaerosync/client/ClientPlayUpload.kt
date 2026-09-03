package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ClientSyncStateStore
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import io.github.jackcuthbert.xaerosync.shared.WaypointDirectoryMonitor
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot

internal class ClientPlayUpload(private val scope: XaeroConnectionScope, private val send: (SyncMessage) -> Unit) :
    AutoCloseable {
    private val state = ClientSyncStateStore(scope.sidecarPath)
    private var pending: WaypointSnapshot? = null
    private val monitor = WaypointDirectoryMonitor(
        scope.waypointRoot,
        state,
        scope.address,
        state.load()?.hash ?: "",
        ::offer,
    )

    @Synchronized
    fun receive(message: SyncMessage) {
        when (message) {
            SyncMessage.UploadRequired -> {
                val transfer = SnapshotTransfer.from(requireNotNull(pending))
                send(transfer.start)
                transfer.chunks.forEach(send)
            }
            is SyncMessage.TransferAccepted -> {
                val snapshot = requireNotNull(pending)
                require(snapshot.hash == message.hash && snapshot.updatedAt == message.updatedAt)
                state.record(scope.address, snapshot)
                monitor.acknowledge(snapshot.hash)
                pending = null
            }
            is SyncMessage.TransferRejected -> {
                pending = null
                monitor.retry()
            }
            else -> Unit
        }
    }

    fun flush() = monitor.flush()

    override fun close() = monitor.close()

    @Synchronized
    private fun offer(snapshot: WaypointSnapshot) {
        pending = snapshot
        send(SyncMessage.ClientMetadata(SnapshotMetadata.from(snapshot)))
    }
}
