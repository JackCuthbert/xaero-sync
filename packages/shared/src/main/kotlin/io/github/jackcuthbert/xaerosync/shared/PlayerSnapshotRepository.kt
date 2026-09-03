package io.github.jackcuthbert.xaerosync.shared

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Server-owned snapshot records, addressed only by the authenticated player's UUID. */
class PlayerSnapshotRepository(private val dataDirectory: Path, private val clock: Clock = Clock.systemUTC()) {
    private val playerLocks = ConcurrentHashMap<UUID, Any>()

    fun load(playerId: UUID): WaypointSnapshot? = synchronized(lockFor(playerId)) {
        storeFor(playerId).load()
    }

    fun save(playerId: UUID, snapshot: WaypointSnapshot) = synchronized(lockFor(playerId)) {
        storeFor(playerId).save(snapshot)
    }

    fun status(playerId: UUID): PlayerSnapshotStatus = synchronized(lockFor(playerId)) {
        val canonical = storeFor(playerId).load()
        val snapshots = listSnapshotsUnlocked(playerId)
        PlayerSnapshotStatus(
            canonical?.hash,
            canonical?.updatedAt,
            canonical?.files?.size ?: 0,
            storeFor(playerId).size(),
            snapshots.size,
            snapshots.sumOf {
                it.bytes
            },
        )
    }

    fun backup(playerId: UUID): StoredSnapshot = synchronized(lockFor(playerId)) {
        val snapshot = requireNotNull(storeFor(playerId).load()) { "Player has no canonical snapshot." }
        backupUnlocked(playerId, snapshot)
    }

    fun listSnapshots(playerId: UUID): List<StoredSnapshot> = synchronized(lockFor(playerId)) {
        listSnapshotsUnlocked(playerId)
    }

    fun restore(playerId: UUID, snapshotId: String): WaypointSnapshot = synchronized(lockFor(playerId)) {
        require(snapshotId.matches(SNAPSHOT_ID)) { "Invalid snapshot identifier." }
        val current = requireNotNull(storeFor(playerId).load()) { "Player has no canonical snapshot." }
        val selected = requireNotNull(AtomicSnapshotStore(snapshotPath(playerId, snapshotId)).load()) {
            "Snapshot does not exist."
        }
        backupUnlocked(playerId, current)
        val restored = WaypointSnapshot.create(selected.files, clock.instant())
        storeFor(playerId).save(restored)
        restored
    }

    fun replace(playerId: UUID, sourcePlayerId: UUID): ReplacementResult = withPlayerLocks(playerId, sourcePlayerId) {
        require(playerId != sourcePlayerId) { "Choose another player's waypoint backup." }
        val source = requireNotNull(storeFor(sourcePlayerId).load()) { "Source player has no waypoint backup." }
        val previous = storeFor(playerId).load()
        val backup = previous?.let { backupUnlocked(playerId, it) }
        val replacement = WaypointSnapshot.create(source.files, clock.instant())
        storeFor(playerId).save(replacement)
        ReplacementResult(replacement, backup)
    }

    private fun lockFor(playerId: UUID): Any = playerLocks.computeIfAbsent(playerId) { Any() }

    private fun <T> withPlayerLocks(firstPlayerId: UUID, secondPlayerId: UUID, operation: () -> T): T {
        val (first, second) = listOf(firstPlayerId, secondPlayerId).sortedBy(UUID::toString)
        return synchronized(lockFor(first)) {
            synchronized(lockFor(second)) {
                operation()
            }
        }
    }

    private fun storeFor(playerId: UUID): AtomicSnapshotStore =
        AtomicSnapshotStore(dataDirectory.resolve("players").resolve("$playerId.json"))

    private fun backupUnlocked(playerId: UUID, snapshot: WaypointSnapshot): StoredSnapshot {
        val directory = snapshotDirectory(playerId)
        Files.createDirectories(directory)
        val createdAt = clock.instant()
        val stem = "${createdAt.epochSecond}-${snapshot.hash.take(12)}"
        var id = stem
        var suffix = 1
        while (Files.exists(snapshotPath(playerId, id))) id = "$stem-${suffix++}"
        val path = snapshotPath(playerId, id)
        AtomicSnapshotStore(path).save(snapshot)
        return StoredSnapshot(id, snapshot.hash, snapshot.updatedAt, createdAt, snapshot.files.size, Files.size(path))
    }

    private fun listSnapshotsUnlocked(playerId: UUID): List<StoredSnapshot> {
        val directory = snapshotDirectory(playerId)
        if (Files.notExists(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { it.fileName.toString().endsWith(".json") }
                .map { path ->
                    val snapshot = requireNotNull(AtomicSnapshotStore(path).load())
                    StoredSnapshot(
                        path.fileName.toString().removeSuffix(".json"),
                        snapshot.hash,
                        snapshot.updatedAt,
                        snapshotCreatedAt(path),
                        snapshot.files.size,
                        Files.size(path),
                    )
                }
                .sortedByDescending { it.id }
                .toList()
        }
    }

    private fun snapshotDirectory(playerId: UUID) = dataDirectory.resolve("snapshots").resolve(playerId.toString())

    private fun snapshotPath(playerId: UUID, snapshotId: String) =
        snapshotDirectory(playerId).resolve("$snapshotId.json")

    private fun snapshotCreatedAt(path: Path): Instant =
        path.fileName.toString().substringBefore('-').toLongOrNull()?.let(Instant::ofEpochSecond)
            ?: Files.getLastModifiedTime(path).toInstant()

    private companion object {
        val SNAPSHOT_ID = Regex("[a-zA-Z0-9._-]{1,128}")
    }
}

data class StoredSnapshot(
    val id: String,
    val hash: String,
    val updatedAt: Instant,
    val createdAt: Instant,
    val fileCount: Int,
    val bytes: Long,
)

data class ReplacementResult(val snapshot: WaypointSnapshot, val previousBackup: StoredSnapshot?)

data class PlayerSnapshotStatus(
    val canonicalHash: String?,
    val canonicalUpdatedAt: Instant?,
    val canonicalFileCount: Int,
    val canonicalBytes: Long,
    val snapshotCount: Int,
    val snapshotBytes: Long,
)
