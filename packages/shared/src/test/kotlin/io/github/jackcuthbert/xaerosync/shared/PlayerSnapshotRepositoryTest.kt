package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSnapshotRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `snapshot survives repository restart and is isolated by UUID`() {
        val owner = UUID.fromString("9bb53c09-dd4e-46f0-9177-f9c64959432c")
        val other = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val snapshot = WaypointSnapshot.create(
            listOf(
                WaypointFile(
                    "dim%0/mw0.txt",
                    "$HEADER\nwaypoint:Home:H:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
                ),
            ),
            Instant.ofEpochSecond(10),
        )
        PlayerSnapshotRepository(temporaryDirectory).save(owner, snapshot)

        val restarted = PlayerSnapshotRepository(temporaryDirectory)
        assertEquals(snapshot.hash, restarted.load(owner)?.hash)
        assertNull(restarted.load(other))
    }

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
