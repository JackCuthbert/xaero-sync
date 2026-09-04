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
    fun `writes raw staged waypoint bytes without removing unrelated files`() {
        write("dim%0/mw0,1,0_2.txt", "$HEADER\nwaypoint:Local:L:1:2:3:1:false:0:set:false:0:0:false")
        val staged = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%0/mw0,1,0_2.txt",
                    "$HEADER\nwaypoint:Server:S:4:5:6:2:false:0:server:false:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(2),
        )

        WaypointSnapshotFiles.write(temporaryDirectory, staged)

        assertEquals(
            staged.files.single().contents.toString(Charsets.UTF_8),
            Files.readString(temporaryDirectory.resolve("dim%0/mw0,1,0_2.txt")),
        )
    }

    @Test
    fun `recognizes only legacy automatic world filenames`() {
        assertTrue(
            WaypointSnapshotFiles.isLegacyAutomaticWorldFile(
                WaypointFile(
                    "dim%0/mw\$default_1.txt",
                    "$HEADER\nwaypoint:A:A:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
        )
        assertFalse(
            WaypointSnapshotFiles.isLegacyAutomaticWorldFile(
                WaypointFile(
                    "dim%0/mwcastle_1.txt",
                    "$HEADER\nwaypoint:A:A:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
        )
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
