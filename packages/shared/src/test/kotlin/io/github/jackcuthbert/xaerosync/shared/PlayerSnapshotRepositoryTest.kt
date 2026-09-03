package io.github.jackcuthbert.xaerosync.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerSnapshotRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `snapshot survives repository restart and is isolated by UUID`() {
        val owner = UUID.fromString("00000000-0000-4000-8000-000000000001")
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
        assertEquals(1, restarted.status(owner).canonicalFileCount)
        assertNull(restarted.load(other))
    }

    @Test
    fun `restore backs up current state and makes selected files newly canonical`() {
        val player = UUID.randomUUID()
        val restoreTime = Instant.ofEpochSecond(100)
        val repository = PlayerSnapshotRepository(
            dataDirectory = temporaryDirectory,
            clock = Clock.fixed(restoreTime, ZoneOffset.UTC),
        )
        val original = snapshot(10, "Original")
        repository.save(player, original)
        val restorePoint = repository.backup(player)
        assertEquals(1, restorePoint.fileCount)
        assertEquals(restoreTime, restorePoint.createdAt)
        val current = snapshot(20, "Current")
        repository.save(player, current)

        val restored = repository.restore(player, restorePoint.id)

        assertEquals(original.hash, restored.hash)
        assertEquals(restoreTime, restored.updatedAt)
        assertEquals(2, repository.status(player).snapshotCount)
        assertEquals(current.hash, repository.listSnapshots(player).first { it.hash == current.hash }.hash)
    }

    @Test
    fun `restore rejects traversal identifiers without changing canonical state`() {
        val player = UUID.randomUUID()
        val repository = PlayerSnapshotRepository(temporaryDirectory)
        val canonical = snapshot(10, "Safe")
        repository.save(player, canonical)

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            repository.restore(player, "../players/$player")
        }

        assertEquals(canonical.hash, repository.load(player)?.hash)
    }

    @Test
    fun `replace copies another players files with a new timestamp and preserves the previous record`() {
        val player = UUID.randomUUID()
        val sourcePlayer = UUID.randomUUID()
        val replacementTime = Instant.ofEpochSecond(100)
        val repository = PlayerSnapshotRepository(temporaryDirectory, Clock.fixed(replacementTime, ZoneOffset.UTC))
        val original = snapshot(10, "Original")
        val source = snapshot(20, "Source")
        repository.save(player, original)
        repository.save(sourcePlayer, source)

        val result = repository.replace(player, sourcePlayer)

        assertEquals(source.hash, result.snapshot.hash)
        assertEquals(replacementTime, result.snapshot.updatedAt)
        assertEquals(original.hash, repository.listSnapshots(player).single().hash)
    }

    private fun snapshot(second: Long, name: String) = WaypointSnapshot.create(
        listOf(
            WaypointFile(
                "dim%0/mw0.txt",
                "$HEADER\nwaypoint:$name:N:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
            ),
        ),
        Instant.ofEpochSecond(second),
    )

    private companion object {
        const val HEADER = "#waypoint:name:initials:x:y:z:color:disabled:type:set:" +
            "rotate_on_tp:tp_yaw:visibility_type:destination"
    }
}
