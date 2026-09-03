package io.github.jackcuthbert.xaerosync.shared

import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** An immutable, validated copy of every Xaero waypoint file for one server connection. */
class WaypointSnapshot private constructor(files: List<WaypointFile>, val updatedAt: Instant) {
    val files: List<WaypointFile> = files.map { it.copyOf() }
    val manifest: List<SnapshotManifestEntry> = this.files.map { SnapshotManifestEntry(it.path, it.contents.size) }
    val hash: String = snapshotHash(this.files)

    companion object {
        fun create(files: Collection<WaypointFile>, updatedAt: Instant): WaypointSnapshot {
            val sortedFiles = files.map { it.copyOf() }.sortedBy { it.path }
            require(sortedFiles.map { it.path }.distinct().size == sortedFiles.size) {
                "A snapshot cannot contain duplicate paths."
            }
            sortedFiles.forEach { file ->
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

    fun copyOf(): WaypointFile = WaypointFile(path, rawContents)
}

data class SnapshotManifestEntry(val path: String, val byteSize: Int)
