package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ClientSyncStateStore
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransferAssembler
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshotFiles
import org.slf4j.LoggerFactory
import java.time.Clock

internal class ClientConfigurationSync(
    private val scope: XaeroConnectionScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val state = ClientSyncStateStore(scope.sidecarPath)
    private var localSnapshot: WaypointSnapshot? = null
    private var incoming: SnapshotTransferAssembler? = null
    private var pendingAutomaticWorldDownload: WaypointSnapshot? = null

    fun start(): SyncMessage.ClientMetadata {
        val snapshot = state.observe(scope.address, scope.waypointRoot, clock.instant())
        localSnapshot = snapshot
        LOGGER.debug(
            "Prepared configuration snapshot for {}: {} file(s), hash={}, updatedAt={}",
            scope.address,
            snapshot.files.size,
            snapshot.hash.take(12),
            snapshot.updatedAt,
        )
        return SyncMessage.ClientMetadata(SnapshotMetadata.from(snapshot))
    }

    @Synchronized
    fun receive(message: SyncMessage): List<SyncMessage> = try {
        when (message) {
            SyncMessage.UploadRequired -> upload()
            is SyncMessage.InSync -> acknowledgeInSync(message)
            is SyncMessage.TransferStart -> {
                check(incoming == null) { "A download is already active." }
                incoming = SnapshotTransferAssembler(message)
                emptyList()
            }
            is SyncMessage.TransferChunk -> acceptDownloadChunk(message)
            is SyncMessage.TransferAccepted -> acceptUpload(message)
            is SyncMessage.TransferRejected -> emptyList()
            is SyncMessage.ClientMetadata -> reject("unexpected_message")
        }
    } catch (_: Exception) {
        incoming = null
        reject("client_application_failed")
    }

    private fun upload(): List<SyncMessage> {
        val transfer = SnapshotTransfer.from(requireNotNull(localSnapshot) { "Sync has not started." })
        return listOf(transfer.start) + transfer.chunks
    }

    private fun acknowledgeInSync(message: SyncMessage.InSync): List<SyncMessage> {
        val local = requireNotNull(localSnapshot)
        require(local.hash == message.hash)
        val canonical = WaypointSnapshot.create(local.files, message.updatedAt)
        localSnapshot = canonical
        state.record(scope.address, canonical)
        return emptyList()
    }

    private fun acceptDownloadChunk(chunk: SyncMessage.TransferChunk): List<SyncMessage> {
        val assembler = requireNotNull(incoming) { "Download has not started." }
        assembler.accept(chunk)
        if (!assembler.isComplete) {
            return emptyList()
        }

        val snapshot = assembler.finish()
        if (WaypointSnapshotFiles.hasLegacyAutomaticWorldFile(snapshot) &&
            !WaypointSnapshotFiles.hasAutomaticWorldConfiguration(scope.waypointRoot)
        ) {
            pendingAutomaticWorldDownload = snapshot
            LOGGER.info(
                "Deferred automatic-world download for {}: Xaero config.txt is not initialized yet (hash={}).",
                scope.address,
                snapshot.hash.take(12),
            )
        } else {
            applyDownload(snapshot)
        }
        incoming = null
        return listOf(SyncMessage.TransferAccepted(snapshot.hash, snapshot.updatedAt))
    }

    @Synchronized
    fun applyPendingAutomaticWorldDownload(): WaypointSnapshot? {
        val snapshot = pendingAutomaticWorldDownload ?: return null
        if (!WaypointSnapshotFiles.hasAutomaticWorldConfiguration(scope.waypointRoot)) return null
        pendingAutomaticWorldDownload = null
        return applyDownload(snapshot).also {
            LOGGER.info(
                "Applied deferred automatic-world download for {}: {} file(s), sourceHash={}, localHash={}; " +
                    "reconnect required.",
                scope.address,
                it.files.size,
                snapshot.hash.take(12),
                it.hash.take(12),
            )
        }
    }

    private fun acceptUpload(message: SyncMessage.TransferAccepted): List<SyncMessage> {
        val local = requireNotNull(localSnapshot)
        require(local.hash == message.hash && local.updatedAt == message.updatedAt)
        state.record(scope.address, local)
        return emptyList()
    }

    private fun applyDownload(snapshot: WaypointSnapshot): WaypointSnapshot {
        val applied = WaypointSnapshotFiles.applyDownload(scope.waypointRoot, snapshot)
        state.record(scope.address, applied)
        localSnapshot = applied
        return applied
    }

    private fun reject(category: String) = listOf(SyncMessage.TransferRejected(category))

    private companion object {
        val LOGGER = LoggerFactory.getLogger("Xaero Sync")
    }
}
