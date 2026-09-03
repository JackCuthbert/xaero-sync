package io.github.jackcuthbert.xaerosync.shared

import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.HexFormat

/** An immutable, validated copy of every Xaero waypoint file for one server connection. */
class WaypointSnapshot private constructor(files: List<WaypointFile>, val updatedAt: Instant) {
    val files: List<WaypointFile> = Collections.unmodifiableList(files.map { it.copyOf() })
    val manifest: List<SnapshotManifestEntry> = Collections.unmodifiableList(
        this.files.map { SnapshotManifestEntry(it.path, it.byteSize) },
    )
    val hash: String = snapshotHash(this.files)

    companion object {
        fun create(files: Collection<WaypointFile>, updatedAt: Instant): WaypointSnapshot {
            require(files.size <= SnapshotLimits.MAX_FILES) {
                "A snapshot cannot contain more than ${SnapshotLimits.MAX_FILES} files."
            }
            val sortedFiles = files.map { it.copyOf() }.sortedBy { it.path }
            require(sortedFiles.map { it.path }.distinct().size == sortedFiles.size) {
                "A snapshot cannot contain duplicate paths."
            }
            var totalBytes = 0L
            sortedFiles.forEach { file ->
                require(file.path.toByteArray(Charsets.UTF_8).size <= SnapshotLimits.MAX_PATH_BYTES) {
                    "Snapshot path is too long: ${file.path}"
                }
                require(file.byteSize <= SnapshotLimits.MAX_FILE_BYTES) {
                    "Snapshot file is too large: ${file.path}"
                }
                totalBytes += file.byteSize
                require(totalBytes <= SnapshotLimits.MAX_TOTAL_BYTES) {
                    "Snapshot content exceeds ${SnapshotLimits.MAX_TOTAL_BYTES} bytes."
                }
                require(WaypointFileSelector.isEligible(file.path, file.contents)) {
                    "Snapshot file is not an eligible Xaero waypoint file: ${file.path}"
                }
            }
            return WaypointSnapshot(sortedFiles, updatedAt)
        }

        private fun snapshotHash(files: List<WaypointFile>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(intBytes(files.size))
            files.forEach { file ->
                val path = file.path.toByteArray(Charsets.UTF_8)
                digest.update(intBytes(path.size))
                digest.update(path)
                digest.update(intBytes(file.contents.size))
                digest.update(file.contents)
            }
            return HexFormat.of().formatHex(digest.digest())
        }

        private fun intBytes(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }
}

/** Raw waypoint bytes, intentionally never interpreted or normalized by Xaero Sync. */
class WaypointFile(val path: String, contents: ByteArray) {
    private val rawContents = contents.copyOf()
    val contents: ByteArray get() = rawContents.copyOf()
    val byteSize: Int get() = rawContents.size

    fun copyOf(): WaypointFile = WaypointFile(path, rawContents)
}

data class SnapshotManifestEntry(val path: String, val byteSize: Int)

object SnapshotLimits {
    const val MAX_FILES = 1_024
    const val MAX_PATH_BYTES = 512
    const val MAX_FILE_BYTES = 1 * 1_024 * 1_024
    const val MAX_TOTAL_BYTES = 32 * 1_024 * 1_024
    const val MAX_JSON_RECORD_BYTES = 48 * 1_024 * 1_024
}
