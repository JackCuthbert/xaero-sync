package io.github.jackcuthbert.xaerosync.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/** Strict JSON codec for snapshots stored locally and by the Paper plugin. */
object SnapshotJsonCodec {
    private const val FORMAT_VERSION = 1
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        prettyPrint = true
    }

    fun encode(snapshot: WaypointSnapshot): String = json.encodeToString(
        SnapshotRecord(
            formatVersion = FORMAT_VERSION,
            updatedAt = snapshot.updatedAt.toString(),
            hash = snapshot.hash,
            files = snapshot.files.map { file ->
                SnapshotFileRecord(file.path, Base64.getEncoder().encodeToString(file.contents))
            },
        ),
    )

    fun decode(encoded: String): WaypointSnapshot = try {
        require(encoded.toByteArray(Charsets.UTF_8).size <= SnapshotLimits.MAX_JSON_RECORD_BYTES) {
            "Snapshot JSON record is too large."
        }
        val record = json.decodeFromString<SnapshotRecord>(encoded)
        require(record.formatVersion == FORMAT_VERSION) {
            "Unsupported snapshot format version: ${record.formatVersion}"
        }

        require(record.files.size <= SnapshotLimits.MAX_FILES) {
            "Snapshot record contains too many files."
        }
        var totalBytes = 0L
        val files = record.files.map { file ->
            require(file.path.toByteArray(Charsets.UTF_8).size <= SnapshotLimits.MAX_PATH_BYTES) {
                "Snapshot path is too long."
            }
            val contents = Base64.getDecoder().decode(file.contents)
            require(contents.size <= SnapshotLimits.MAX_FILE_BYTES) { "Snapshot file is too large." }
            totalBytes += contents.size
            require(totalBytes <= SnapshotLimits.MAX_TOTAL_BYTES) { "Snapshot content is too large." }
            WaypointFile(file.path, contents)
        }
        val snapshot = WaypointSnapshot.create(
            files,
            Instant.parse(record.updatedAt),
        )
        require(snapshot.hash == record.hash) { "Snapshot content hash does not match its record." }
        snapshot
    } catch (exception: IllegalArgumentException) {
        throw InvalidSnapshotRecordException("Invalid snapshot record.", exception)
    } catch (exception: SerializationException) {
        throw InvalidSnapshotRecordException("Invalid snapshot record.", exception)
    } catch (exception: DateTimeParseException) {
        throw InvalidSnapshotRecordException("Invalid snapshot record.", exception)
    }

    @Serializable
    private data class SnapshotRecord(
        val formatVersion: Int,
        val updatedAt: String,
        val hash: String,
        val files: List<SnapshotFileRecord>,
    )

    @Serializable
    private data class SnapshotFileRecord(val path: String, val contents: String)
}

class InvalidSnapshotRecordException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)
