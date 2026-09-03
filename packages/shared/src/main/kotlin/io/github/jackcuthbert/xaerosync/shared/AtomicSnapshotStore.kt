package io.github.jackcuthbert.xaerosync.shared

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

/** Durably replaces a single JSON snapshot record without exposing partially-written data. */
class AtomicSnapshotStore(private val recordPath: Path) {
    fun size(): Long = if (Files.exists(recordPath)) Files.size(recordPath) else 0

    fun load(): WaypointSnapshot? {
        if (Files.notExists(recordPath)) {
            return null
        }
        return try {
            if (Files.size(recordPath) > SnapshotLimits.MAX_JSON_RECORD_BYTES) {
                throw InvalidSnapshotRecordException(
                    "Snapshot JSON record is too large.",
                    IllegalArgumentException("Record exceeds ${SnapshotLimits.MAX_JSON_RECORD_BYTES} bytes."),
                )
            }
            SnapshotJsonCodec.decode(Files.readString(recordPath))
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not read snapshot record at $recordPath.", exception)
        }
    }

    fun save(snapshot: WaypointSnapshot) {
        val parent = requireNotNull(recordPath.parent) { "Snapshot record needs a parent directory." }
        val temporary = try {
            Files.createDirectories(parent)
            Files.createTempFile(parent, ".${recordPath.fileName}.", ".tmp")
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not prepare snapshot record at $recordPath.", exception)
        }

        try {
            writeAndSync(temporary, SnapshotJsonCodec.encode(snapshot).toByteArray(Charsets.UTF_8))
            moveIntoPlace(temporary)
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not save snapshot record at $recordPath.", exception)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun writeAndSync(path: Path, contents: ByteArray) {
        FileChannel.open(path, CREATE, WRITE, TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(contents)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    private fun moveIntoPlace(temporary: Path) {
        try {
            Files.move(temporary, recordPath, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, recordPath, REPLACE_EXISTING)
        }
    }
}

class SnapshotStorageException(message: String, cause: Throwable) : IllegalStateException(message, cause)
