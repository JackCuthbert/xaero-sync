package io.github.jackcuthbert.xaerosync.shared

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WaypointSnapshotTest {
    @Test
    fun `creates a deterministic snapshot across all observed dimensions`() {
        val timestamp = Instant.parse("2026-09-03T05:00:00.123Z")
        val first = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%1/mw0,1,0_1.txt",
                    fixture("dim%1/mw0,1,0_1.txt", "Multiplayer_localhost"),
                ),
                WaypointFile("dim%0/mw\$default_1.txt", fixture("dim%0/mw\$default_1.txt")),
                WaypointFile("dim%-1/mw\$default_1.txt", fixture("dim%-1/mw\$default_1.txt")),
            ),
            timestamp,
        )
        val reordered = WaypointSnapshot.create(first.files.reversed(), timestamp)

        assertEquals(first.hash, reordered.hash)
        assertEquals(
            listOf("dim%-1/mw\$default_1.txt", "dim%0/mw\$default_1.txt", "dim%1/mw0,1,0_1.txt"),
            first.manifest.map { it.path },
        )
    }

    @Test
    fun `hash includes unambiguous paths and raw waypoint bytes`() {
        val timestamp = Instant.parse("2026-09-03T05:00:00Z")
        val first = WaypointSnapshot.create(listOf(waypointFile("dim%0/mw\$default_1.txt", "A")), timestamp)
        val changedPath = WaypointSnapshot.create(listOf(waypointFile("dim%1/mw\$default_1.txt", "A")), timestamp)
        val changedContents = WaypointSnapshot.create(listOf(waypointFile("dim%0/mw\$default_1.txt", "B")), timestamp)

        assertNotEquals(first.hash, changedPath.hash)
        assertNotEquals(first.hash, changedContents.hash)
    }

    @Test
    fun `allows an empty manifest`() {
        val snapshot = WaypointSnapshot.create(emptyList(), Instant.parse("2026-09-03T05:00:00Z"))

        assertTrue(snapshot.files.isEmpty())
        assertEquals(64, snapshot.hash.length)
    }

    @Test
    fun `rejects ignored files duplicate paths and traversal attempts`() {
        val timestamp = Instant.parse("2026-09-03T05:00:00Z")

        assertFailsWith<IllegalArgumentException> {
            WaypointSnapshot.create(listOf(WaypointFile("config.txt", fixture("config.txt"))), timestamp)
        }
        assertFailsWith<IllegalArgumentException> {
            WaypointSnapshot.create(
                listOf(waypointFile("dim%0/mw\$default_1.txt", "A"), waypointFile("dim%0/mw\$default_1.txt", "B")),
                timestamp,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WaypointSnapshot.create(listOf(waypointFile("../dim%0/mw\$default_1.txt", "A")), timestamp)
        }
    }

    @Test
    fun `defensively copies waypoint content`() {
        val path = "dim%1/mw0,1,0_1.txt"
        val contents = fixture(path, "Multiplayer_localhost")
        val snapshot = WaypointSnapshot.create(listOf(WaypointFile(path, contents)), Instant.EPOCH)
        contents[0] = '!'.code.toByte()
        snapshot.files.single().contents[0] = '!'.code.toByte()

        assertContentEquals(fixture(path, "Multiplayer_localhost"), snapshot.files.single().contents)
    }

    @Test
    fun `does not expose mutable snapshot collections`() {
        val snapshot = WaypointSnapshot.create(
            listOf(waypointFile("dim%0/mw\$default_1.txt", "A")),
            Instant.EPOCH,
        )

        assertFailsWith<UnsupportedOperationException> { (snapshot.files as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (snapshot.manifest as MutableList).clear() }
    }

    @Test
    fun `enforces file count path and file size safety ceilings`() {
        val tooManyFiles = (0..SnapshotLimits.MAX_FILES).map { index ->
            waypointFile("dim%0/mw\$$index.txt", "A")
        }
        val longName = "a".repeat(SnapshotLimits.MAX_PATH_BYTES) + ".txt"
        val oversizedContents = ByteArray(SnapshotLimits.MAX_FILE_BYTES + 1).also { contents ->
            "#waypoint:name\n".toByteArray().copyInto(contents)
        }

        assertFailsWith<IllegalArgumentException> { WaypointSnapshot.create(tooManyFiles, Instant.EPOCH) }
        assertFailsWith<IllegalArgumentException> {
            WaypointSnapshot.create(listOf(waypointFile("dim%0/mw\$$longName", "A")), Instant.EPOCH)
        }
        assertFailsWith<IllegalArgumentException> {
            WaypointSnapshot.create(
                listOf(WaypointFile("dim%0/mw\$oversized.txt", oversizedContents)),
                Instant.EPOCH,
            )
        }
    }

    @Test
    fun `enforces the total decoded-content safety ceiling`() {
        val fileContents = ByteArray(SnapshotLimits.MAX_FILE_BYTES).also { contents ->
            "#waypoint:name\n".toByteArray().copyInto(contents)
        }
        val files = (0..SnapshotLimits.MAX_TOTAL_BYTES / SnapshotLimits.MAX_FILE_BYTES).map { index ->
            WaypointFile("dim%0/mw\$$index.txt", fileContents)
        }

        assertFailsWith<IllegalArgumentException> { WaypointSnapshot.create(files, Instant.EPOCH) }
    }

    private fun waypointFile(path: String, suffix: String): WaypointFile = WaypointFile(
        path,
        "#waypoint:name\nwaypoint:Test:T:0:64:0:1:false:0:gui.xaero_default:false:0:0:false$suffix".toByteArray(),
    )

    private fun fixture(relativePath: String, connection: String = "Multiplayer_example.invalid"): ByteArray =
        requireNotNull(
            javaClass.getResourceAsStream("/fixtures/xaero-minimap/$connection/$relativePath"),
        ).readBytes()
}
