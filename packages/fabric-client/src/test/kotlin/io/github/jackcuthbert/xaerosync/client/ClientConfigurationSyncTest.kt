package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.SnapshotTransfer
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientConfigurationSyncTest {
    @TempDir
    lateinit var gameDirectory: Path

    @Test
    fun `named waypoint sets restore on reconnect when Xaero has not saved the selected file`() {
        val scope = XaeroConnectionScope.from(gameDirectory, "example.com:25565")
        val contents = requireNotNull(javaClass.getResourceAsStream("/fixtures/named-sets.txt")).use { it.readBytes() }
        val download = WaypointSnapshot.create(
            listOf(WaypointFile("dim%0/mw\$default_1.txt", contents)),
            Instant.ofEpochSecond(2),
        )
        assertEquals("91bdddb4eac112d60a67827340471d3bbaee82b8200244bf0bc83e6c2b14cc89", download.hash)
        val sync = ClientConfigurationSync(scope)
        val transfer = SnapshotTransfer.from(download)
        sync.receive(transfer.start)
        sync.receive(transfer.chunks.single())
        val target = scope.waypointRoot.resolve("dim%0/mw0,1,0_2.txt")

        assertEquals(listOf("dim%0"), sync.discoverTarget(target))
        assertFalse(Files.exists(target))

        val reconnected = ClientConfigurationSync(scope)
        reconnected.start()

        assertFalse(reconnected.hasStagedDownloads())
        assertContentEquals(contents, Files.readAllBytes(target))
    }

    @Test
    fun `automatic waypoints are staged during play then replaced before the next Xaero load`() {
        val scope = XaeroConnectionScope.from(gameDirectory, "example.com:25565")
        val sync = ClientConfigurationSync(scope)
        val download = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%-1/mw\$default_1.txt",
                    "$HEADER\nwaypoint:Server:S:4:5:6:2:false:0:nether:false:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(2),
        )
        val transfer = SnapshotTransfer.from(download)

        sync.receive(transfer.start)
        sync.receive(transfer.chunks.single())

        assertTrue(Files.exists(scope.pendingDownloadPath))
        assertFalse(Files.exists(scope.waypointRoot.resolve("dim%-1/mw\$default_1.txt")))

        val target = scope.waypointRoot.resolve("dim%-1/mw0,1,0_1.txt")
        Files.createDirectories(target.parent)
        Files.writeString(scope.waypointRoot.resolve("config.txt"), "defaultMultiworldId:mw0,1,0")
        Files.writeString(target, "$HEADER\nwaypoint:Local:L:1:2:3:1:false:0:local:false:0:false")

        assertEquals(listOf("dim%-1"), sync.discoverTarget(target))
        assertTrue(Files.readString(target).contains("waypoint:Local:"))
        assertFalse(Files.readString(target).contains("waypoint:Server:"))

        val reconnected = ClientConfigurationSync(scope)
        reconnected.start()

        assertTrue(Files.exists(scope.pendingDownloadPath))
        assertFalse(reconnected.hasStagedDownloads())
        assertEquals(download.files.single().contents.toString(Charsets.UTF_8), Files.readString(target))

        val repeatTransfer = SnapshotTransfer.from(download)
        reconnected.receive(repeatTransfer.start)
        reconnected.receive(repeatTransfer.chunks.single())

        assertFalse(reconnected.hasStagedDownloads())
        assertTrue(reconnected.discoverTarget(target).isEmpty())
    }

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
