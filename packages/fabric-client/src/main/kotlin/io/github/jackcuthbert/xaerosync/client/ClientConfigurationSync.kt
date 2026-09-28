package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ClientSyncStateStore
import io.github.jackcuthbert.xaerosync.shared.SnapshotMetadata
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.SnapshotTransferAssembler
import io.github.jackcuthbert.xaerosync.shared.SyncMessage
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshotFiles
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock

internal class ClientConfigurationSync(
    private val scope: XaeroConnectionScope,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val state = ClientSyncStateStore(scope.sidecarPath)
    private var localSnapshot: WaypointSnapshot? = null
    private var incoming: SnapshotTransferAssembler? = null
    private val pendingDownloads = PendingAutomaticWorldDownloadStore(scope.pendingDownloadPath)
    private var staged = pendingDownloads.load()
    private var rejectedTarget: Path? = null

    fun hasStagedDownloads(): Boolean = staged?.snapshot?.files?.isNotEmpty() == true

    fun start(): SyncMessage.ClientMetadata {
        applyKnownTargets()
        val observed = state.observe(scope.address, scope.waypointRoot, clock.instant())
        val pending = staged
        val snapshot = if (pending == null) {
            observed
        } else {
            val files = observed.files.associateByTo(linkedMapOf()) { it.path }
            pending.snapshot.files.forEach { files[it.path] = it }
            WaypointSnapshot.create(files.values, observed.updatedAt)
        }
        localSnapshot = snapshot
        LOGGER.info(
            "Prepared configuration snapshot for {} at {}: {} file(s), hash={}, updatedAt={}",
            scope.address,
            scope.waypointRoot,
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
    } catch (exception: Exception) {
        LOGGER.warn("Could not apply configuration sync for ${scope.address}.", exception)
        incoming = null
        reject("client_application_failed")
    }

    private fun upload(): List<SyncMessage> {
        val local = requireNotNull(localSnapshot) { "Sync has not started." }
        val transfer = SnapshotTransfer.from(local)
        LOGGER.info("Uploading local waypoint snapshot for {} (hash={}).", scope.address, local.hash)
        return listOf(transfer.start) + transfer.chunks
    }

    private fun acknowledgeInSync(message: SyncMessage.InSync): List<SyncMessage> {
        val local = requireNotNull(localSnapshot)
        require(local.hash == message.hash)
        val canonical = WaypointSnapshot.create(local.files, message.updatedAt)
        localSnapshot = canonical
        state.record(scope.address, canonical)
        LOGGER.info("Waypoint snapshot is already synchronized for {} (hash={}).", scope.address, canonical.hash)
        return emptyList()
    }

    private fun acceptDownloadChunk(chunk: SyncMessage.TransferChunk): List<SyncMessage> {
        val assembler = requireNotNull(incoming) { "Download has not started." }
        assembler.accept(chunk)
        if (!assembler.isComplete) {
            return emptyList()
        }

        val snapshot = assembler.finish()
        LOGGER.info(
            "Received waypoint download for {}: files={}, hash={}",
            scope.address,
            snapshot.files.map { it.path },
            snapshot.hash,
        )
        val legacy = snapshot.files.filter(WaypointSnapshotFiles::isLegacyAutomaticWorldFile)
        val oldTargets = staged?.targets.orEmpty()
        staged = PendingAutomaticWorldDownload(
            WaypointSnapshot.create(legacy, snapshot.updatedAt),
            oldTargets.filterKeys { source -> legacy.any { it.path == source } },
        )
        pendingDownloads.save(staged)
        WaypointSnapshotFiles.write(
            scope.waypointRoot,
            WaypointSnapshot.create(
                snapshot.files.filterNot(WaypointSnapshotFiles::isLegacyAutomaticWorldFile),
                snapshot.updatedAt,
            ),
        )
        val applied = WaypointSnapshotFiles.read(scope.waypointRoot, snapshot.updatedAt)
        state.record(scope.address, applied)
        localSnapshot = applied
        applyKnownTargets()
        if (hasStagedDownloads()) {
            LOGGER.info(
                "Staged automatic-world download for {}: {} dimension file(s) await Xaero initialization (hash={}).",
                scope.address,
                staged?.snapshot?.files?.size,
                snapshot.hash.take(12),
            )
        }
        incoming = null
        return listOf(SyncMessage.TransferAccepted(snapshot.hash, snapshot.updatedAt))
    }

    @Synchronized
    fun discoverTarget(target: Path): List<String> {
        val current = staged ?: return emptyList()
        val root = scope.waypointRoot.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        val relative = runCatching {
            root.relativize(normalizedTarget).joinToString("/")
        }.getOrNull() ?: return emptyList()
        if (!normalizedTarget.startsWith(root)) {
            if (rejectedTarget != normalizedTarget) {
                LOGGER.warn("Xaero automatic-world target {} is outside the sync directory {}.", normalizedTarget, root)
                rejectedTarget = normalizedTarget
            }
            return emptyList()
        }
        val directory = relative.substringBeforeLast('/', missingDelimiterValue = "")
        val sources = current.snapshot.files.filter { it.path.substringBeforeLast('/', "") == directory }
        if (sources.isEmpty()) return emptyList()
        val changed = sources.filter { current.targets[it.path] != relative }
        if (changed.isEmpty()) return emptyList()
        staged = current.copy(targets = current.targets + changed.associate { it.path to relative })
        pendingDownloads.save(staged)
        LOGGER.info(
            "Selected Xaero restore target {} for downloaded files {}.",
            normalizedTarget,
            changed.map { it.path },
        )
        return listOf(directory)
    }

    private fun acceptUpload(message: SyncMessage.TransferAccepted): List<SyncMessage> {
        val local = requireNotNull(localSnapshot)
        require(local.hash == message.hash && local.updatedAt == message.updatedAt)
        state.record(scope.address, local)
        return emptyList()
    }

    private fun applyKnownTargets() {
        val current = staged ?: return
        val root = scope.waypointRoot.toAbsolutePath().normalize()
        val files = current.snapshot.files.mapNotNull { source ->
            val target = current.targets[source.path] ?: return@mapNotNull null
            val destination = root.resolve(target).normalize()
            if (destination.startsWith(root)) {
                WaypointFile(target, source.contents)
            } else {
                null
            }
        }
        if (files.isEmpty()) return
        WaypointSnapshotFiles.write(scope.waypointRoot, WaypointSnapshot.create(files, current.snapshot.updatedAt))
        LOGGER.info(
            "Applied waypoint download before world loading at {}: files={}",
            scope.waypointRoot,
            files.map { it.path },
        )
        val appliedSources = current.targets.filterValues { target -> files.any { it.path == target } }.keys
        staged = current.copy(
            snapshot = WaypointSnapshot.create(
                current.snapshot.files.filterNot { it.path in appliedSources },
                current.snapshot.updatedAt,
            ),
            targets = current.targets,
        )
        pendingDownloads.save(staged)
        val applied = WaypointSnapshotFiles.read(scope.waypointRoot, current.snapshot.updatedAt)
        state.record(scope.address, applied)
        localSnapshot = applied
    }

    private fun reject(category: String) = listOf(SyncMessage.TransferRejected(category))

    private companion object {
        val LOGGER = LoggerFactory.getLogger("Xaero Sync")
    }
}
