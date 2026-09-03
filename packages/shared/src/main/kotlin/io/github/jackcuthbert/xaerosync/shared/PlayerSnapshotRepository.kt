package io.github.jackcuthbert.xaerosync.shared

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Server-owned snapshot records, addressed only by the authenticated player's UUID. */
class PlayerSnapshotRepository(private val dataDirectory: Path) {
    private val playerLocks = ConcurrentHashMap<UUID, Any>()

    fun load(playerId: UUID): WaypointSnapshot? = synchronized(lockFor(playerId)) {
        storeFor(playerId).load()
    }

    fun save(playerId: UUID, snapshot: WaypointSnapshot) = synchronized(lockFor(playerId)) {
        storeFor(playerId).save(snapshot)
    }

    private fun lockFor(playerId: UUID): Any = playerLocks.computeIfAbsent(playerId) { Any() }

    private fun storeFor(playerId: UUID): AtomicSnapshotStore =
        AtomicSnapshotStore(dataDirectory.resolve("players").resolve("$playerId.json"))
}
