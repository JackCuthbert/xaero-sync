package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointSnapshotFilesTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `reads every dimension but ignores Xaero configuration`() {
        write("config.txt", "settings")
        write("dim%0/mw0,1,0_2.txt", "$HEADER\nwaypoint:Home:H:1:2:3:1:false:0:set:false:0:0:false")
        write("dim%-1/mw0,1,0_1.txt", "$HEADER\nwaypoint:Portal:P:4:5:6:2:false:0:set:false:0:0:false")

        val snapshot = WaypointSnapshotFiles.read(temporaryDirectory, Instant.EPOCH)

        assertEquals(listOf("dim%-1/mw0,1,0_1.txt", "dim%0/mw0,1,0_2.txt"), snapshot.files.map { it.path })
    }

    @Test
    fun `replacement removes stale waypoints and preserves unrelated files`() {
        write("config.txt", "keep me")
        write("dim%0/mw-old.txt", "$HEADER\nwaypoint:Old:O:1:2:3:1:false:0:set:false:0:0:false")
        val replacement = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%1/mw-new.txt",
                    "$HEADER\nwaypoint:End:E:4:5:6:2:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(2),
        )

        WaypointSnapshotFiles.replace(temporaryDirectory, replacement)

        assertFalse(Files.exists(temporaryDirectory.resolve("dim%0/mw-old.txt")))
        assertEquals("keep me", Files.readString(temporaryDirectory.resolve("config.txt")))
        assertEquals(replacement.hash, WaypointSnapshotFiles.read(temporaryDirectory, replacement.updatedAt).hash)
    }

    @Test
    fun `download merges legacy automatic world into the local automatic world`() {
        write("config.txt", "defaultMultiworldId:mw0,1,0")
        write(
            "dim%0/mw0,1,0_2.txt",
            "$HEADER\nwaypoint:Local:L:1:2:3:1:false:0:set:false:0:0:false\n" +
                "waypoint:Server:X:4:5:6:9:true:0:set:false:0:0:false",
        )
        val download = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%0/mw\$default_1.txt",
                    "$HEADER\nwaypoint:Server:S:4:5:6:2:false:0:set:false:0:0:false".toByteArray(),
                ),
                WaypointFile(
                    "dim%0/mwcastle_1.txt",
                    "$HEADER\nwaypoint:Castle:C:7:8:9:3:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(2),
        )

        val applied = WaypointSnapshotFiles.applyDownload(temporaryDirectory, download)

        val automaticWorld = Files.readString(temporaryDirectory.resolve("dim%0/mw0,1,0_2.txt"))
        assertTrue(automaticWorld.contains("waypoint:Local:"))
        assertTrue(automaticWorld.contains("waypoint:Server:"))
        assertFalse(automaticWorld.contains("waypoint:Server:X:"))
        assertEquals(1, automaticWorld.lineSequence().count { it.startsWith("waypoint:Server:") })
        assertFalse(Files.exists(temporaryDirectory.resolve("dim%0/mw\$default_1.txt")))
        assertEquals("defaultMultiworldId:mw0,1,0", Files.readString(temporaryDirectory.resolve("config.txt")))
        assertEquals(
            "$HEADER\nwaypoint:Castle:C:7:8:9:3:false:0:set:false:0:0:false",
            Files.readString(temporaryDirectory.resolve("dim%0/mwcastle_1.txt")),
        )
        assertEquals(listOf("dim%0/mw0,1,0_2.txt", "dim%0/mwcastle_1.txt"), applied.files.map { it.path })
    }

    @Test
    fun `download creates the local automatic world when Xaero has not created it yet`() {
        write("config.txt", "defaultMultiworldId:mw0,1,0")
        val download = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%0/mw\$default_1.txt",
                    "$HEADER\nwaypoint:Server:S:4:5:6:2:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(2),
        )

        val applied = WaypointSnapshotFiles.applyDownload(temporaryDirectory, download)

        assertTrue(Files.exists(temporaryDirectory.resolve("dim%0/mw0,1,0_1.txt")))
        assertFalse(Files.exists(temporaryDirectory.resolve("dim%0/mw\$default_1.txt")))
        assertEquals(listOf("dim%0/mw0,1,0_1.txt"), applied.files.map { it.path })
    }

    private fun write(relative: String, contents: String) {
        val path = temporaryDirectory.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, contents)
    }

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
