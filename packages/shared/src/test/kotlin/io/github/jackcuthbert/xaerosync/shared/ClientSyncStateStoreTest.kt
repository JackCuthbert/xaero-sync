package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ClientSyncStateStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `unchanged files retain their timestamp and changed files receive the observation time`() {
        val xaeroRoot = temporaryDirectory.resolve("xaero")
        val waypoint = xaeroRoot.resolve("dim%0/mw0.txt")
        Files.createDirectories(waypoint.parent)
        Files.writeString(waypoint, "$HEADER\nwaypoint:Home:H:1:2:3:1:false:0:set:false:0:0:false")
        val store = ClientSyncStateStore(temporaryDirectory.resolve("state/scope.json"))

        val first = store.observe("localhost:25565", xaeroRoot, Instant.ofEpochSecond(10))
        val unchanged = store.observe("localhost:25565", xaeroRoot, Instant.ofEpochSecond(20))
        assertEquals(first.updatedAt, unchanged.updatedAt)

        Files.writeString(waypoint, "$HEADER\nwaypoint:Mine:M:4:5:6:2:false:0:set:false:0:0:false")
        val changed = store.observe("localhost:25565", xaeroRoot, Instant.ofEpochSecond(30))
        assertNotEquals(first.hash, changed.hash)
        assertEquals(Instant.ofEpochSecond(30), changed.updatedAt)
    }

    @Test
    fun `recorded server snapshot becomes the next observed baseline`() {
        val xaeroRoot = temporaryDirectory.resolve("xaero")
        val snapshot = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%1/mw0.txt",
                    "$HEADER\nwaypoint:End:E:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(40),
        )
        WaypointSnapshotFiles.replace(xaeroRoot, snapshot)
        val store = ClientSyncStateStore(temporaryDirectory.resolve("state/scope.json"))
        store.record("localhost:25565", snapshot)

        assertEquals(
            snapshot.updatedAt,
            store.observe("localhost:25565", xaeroRoot, Instant.ofEpochSecond(99)).updatedAt,
        )
    }

    @Test
    fun `fresh empty installation starts at epoch so an existing server snapshot wins`() {
        val store = ClientSyncStateStore(temporaryDirectory.resolve("state/fresh.json"))

        val snapshot = store.observe(
            "localhost:25565",
            temporaryDirectory.resolve("missing-xaero-root"),
            Instant.ofEpochSecond(100),
        )

        assertEquals(Instant.EPOCH, snapshot.updatedAt)
        assertEquals(emptyList(), snapshot.files)
    }

    @Test
    fun `imported files without a sidecar use their newest filesystem timestamp`() {
        val xaeroRoot = temporaryDirectory.resolve("imported")
        val waypoint = xaeroRoot.resolve("dim%0/mw0.txt")
        Files.createDirectories(waypoint.parent)
        Files.writeString(waypoint, "$HEADER\nwaypoint:Import:I:1:2:3:1:false:0:set:false:0:0:false")
        val importedAt = Instant.ofEpochSecond(42)
        Files.setLastModifiedTime(waypoint, java.nio.file.attribute.FileTime.from(importedAt))

        val snapshot = ClientSyncStateStore(temporaryDirectory.resolve("state/imported.json"))
            .observe("localhost:25565", xaeroRoot, Instant.ofEpochSecond(100))

        assertEquals(importedAt, snapshot.updatedAt)
    }

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
