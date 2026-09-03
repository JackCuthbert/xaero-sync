package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientConfigurationSyncTest {
    @TempDir
    lateinit var gameDirectory: Path

    @Test
    fun `legacy automatic download waits for Xaero configuration before applying`() {
        val scope = XaeroConnectionScope.from(gameDirectory, "example.com:25565")
        val sync = ClientConfigurationSync(scope)
        val download = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%0/mw\$default_1.txt",
                    "$HEADER\nwaypoint:Server:S:4:5:6:2:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(2),
        )
        val transfer = SnapshotTransfer.from(download)

        sync.receive(transfer.start)
        val acknowledgement = sync.receive(transfer.chunks.single())

        assertEquals(1, acknowledgement.size)
        assertFalse(Files.exists(scope.waypointRoot.resolve("dim%0/mw\$default_1.txt")))
        assertNull(sync.applyPendingAutomaticWorldDownload())

        Files.createDirectories(scope.waypointRoot)
        Files.writeString(scope.waypointRoot.resolve("config.txt"), "defaultMultiworldId:mw0,1,0")

        val applied = assertNotNull(sync.applyPendingAutomaticWorldDownload())

        assertEquals(listOf("dim%0/mw0,1,0_1.txt"), applied.files.map { it.path })
        assertTrue(Files.exists(scope.waypointRoot.resolve("dim%0/mw0,1,0_1.txt")))
        assertFalse(Files.exists(scope.waypointRoot.resolve("dim%0/mw\$default_1.txt")))
        assertNull(sync.applyPendingAutomaticWorldDownload())
    }

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
