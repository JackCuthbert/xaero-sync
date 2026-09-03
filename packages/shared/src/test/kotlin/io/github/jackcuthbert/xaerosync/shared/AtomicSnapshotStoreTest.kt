package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AtomicSnapshotStoreTest {
    @TempDir
    lateinit var temporaryDirectory: java.nio.file.Path

    @Test
    fun `persists and atomically replaces the complete current snapshot`() {
        val store = AtomicSnapshotStore(temporaryDirectory.resolve("records/player.json"))
        val first = snapshot("First")
        val replacement = snapshot("Replacement")

        assertNull(store.load())
        store.save(first)
        store.save(replacement)

        assertEquals(replacement.hash, requireNotNull(store.load()).hash)
        assertEquals(1, Files.list(temporaryDirectory.resolve("records")).use { it.count() })
    }

    @Test
    fun `does not treat a truncated on-disk record as a snapshot`() {
        val record = temporaryDirectory.resolve("records/player.json")
        Files.createDirectories(record.parent)
        Files.writeString(record, "{\"formatVersion\":1")

        assertFailsWith<InvalidSnapshotRecordException> { AtomicSnapshotStore(record).load() }
    }

    private fun snapshot(name: String): WaypointSnapshot = WaypointSnapshot.create(
        listOf(
            WaypointFile(
                "dim%0/mw\$default_1.txt",
                "#waypoint:name\nwaypoint:$name:T:0:64:0:1:false:0:gui.xaero_default:false:0:0:false".toByteArray(),
            ),
        ),
        Instant.EPOCH,
    )
}
