package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaypointDirectoryMonitorTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `new dimension and burst writes produce one stable changed snapshot`() {
        val xaeroRoot = temporaryDirectory.resolve("xaero")
        Files.createDirectories(xaeroRoot)
        val state = ClientSyncStateStore(temporaryDirectory.resolve("state.json"))
        val initial = state.observe("localhost", xaeroRoot, Instant.EPOCH)
        val snapshots = CopyOnWriteArrayList<WaypointSnapshot>()
        val changed = CountDownLatch(1)

        WaypointDirectoryMonitor(
            xaeroRoot,
            state,
            "localhost",
            initial.hash,
            {
                snapshots += it
                changed.countDown()
            },
            debounce = Duration.ofMillis(150),
            rescanInterval = Duration.ofSeconds(10),
        ).use {
            val waypoint = xaeroRoot.resolve("dim%-1/mw0.txt")
            Files.createDirectories(waypoint.parent)
            Files.writeString(waypoint, HEADER)
            Thread.sleep(30)
            Files.writeString(waypoint, "$HEADER\nwaypoint:Portal:P:1:2:3:1:false:0:set:false:0:0:false")

            assertTrue(changed.await(3, TimeUnit.SECONDS))
            Thread.sleep(250)
        }

        assertEquals(1, snapshots.size)
        assertEquals(listOf("dim%-1/mw0.txt"), snapshots.single().files.map { it.path })
    }

    @Test
    fun `shutdown flush observes a change before the debounce expires`() {
        val xaeroRoot = temporaryDirectory.resolve("xaero")
        Files.createDirectories(xaeroRoot)
        val state = ClientSyncStateStore(temporaryDirectory.resolve("flush-state.json"))
        val initial = state.observe("localhost", xaeroRoot, Instant.EPOCH)
        val changed = CountDownLatch(1)

        WaypointDirectoryMonitor(
            xaeroRoot,
            state,
            "localhost",
            initial.hash,
            { changed.countDown() },
            debounce = Duration.ofSeconds(30),
            rescanInterval = Duration.ofMinutes(30),
        ).use { monitor ->
            val waypoint = xaeroRoot.resolve("dim%0/mw0.txt")
            Files.createDirectories(waypoint.parent)
            Files.writeString(waypoint, "$HEADER\nwaypoint:Home:H:1:2:3:1:false:0:set:false:0:0:false")
            monitor.flush()
            assertTrue(changed.await(1, TimeUnit.SECONDS))
        }
    }

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
