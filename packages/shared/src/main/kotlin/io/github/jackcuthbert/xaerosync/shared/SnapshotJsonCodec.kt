package io.github.jackcuthbert.xaerosync.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
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
        val record = json.decodeFromString<SnapshotRecord>(encoded)
        require(record.formatVersion == FORMAT_VERSION) {
            "Unsupported snapshot format version: ${record.formatVersion}"
        }

        val snapshot = WaypointSnapshot.create(
            record.files.map { file ->
                WaypointFile(file.path, Base64.getDecoder().decode(file.contents))
            },
            Instant.parse(record.updatedAt),
        )
        require(snapshot.hash == record.hash) { "Snapshot content hash does not match its record." }
        snapshot
    } catch (exception: IllegalArgumentException) {
        throw InvalidSnapshotRecordException("Invalid snapshot record.", exception)
    } catch (exception: SerializationException) {
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
