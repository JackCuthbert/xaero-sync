package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.SnapshotJsonCodec
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64

internal data class PendingAutomaticWorldDownload(val snapshot: WaypointSnapshot, val targets: Map<String, String>)

internal class PendingAutomaticWorldDownloadStore(private val path: Path) {
    fun load(): PendingAutomaticWorldDownload? = runCatching {
        if (Files.notExists(path)) return null
        val lines = Files.readAllLines(path)
        require(lines.firstOrNull() == VERSION)
        val snapshot = SnapshotJsonCodec.decode(
            Base64.getDecoder().decode(requireNotNull(lines.getOrNull(1))).toString(Charsets.UTF_8),
        )
        val targets = lines.drop(2).associate { line ->
            val (source, target) = line.split('\t', limit = 2)
            Base64.getDecoder().decode(source).toString(Charsets.UTF_8) to
                Base64.getDecoder().decode(target).toString(Charsets.UTF_8)
        }
        PendingAutomaticWorldDownload(snapshot, targets)
    }.getOrElse {
        runCatching { Files.deleteIfExists(path) }
        null
    }

    fun save(download: PendingAutomaticWorldDownload?) {
        if (download == null || (download.snapshot.files.isEmpty() && download.targets.isEmpty())) {
            Files.deleteIfExists(path)
            return
        }

        val parent = requireNotNull(path.parent)
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
            val encodedSnapshot = Base64.getEncoder().encodeToString(
                SnapshotJsonCodec.encode(download.snapshot).toByteArray(),
            )
            val lines = mutableListOf(VERSION, encodedSnapshot)
            lines += download.targets.entries.map { (source, target) ->
                "${Base64.getEncoder().encodeToString(source.toByteArray())}\t" +
                    Base64.getEncoder().encodeToString(target.toByteArray())
            }
            Files.writeString(temporary, lines.joinToString("\n"))
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } catch (exception: IOException) {
            throw IllegalStateException("Could not save pending automatic-world download at $path.", exception)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private companion object {
        const val VERSION = "xaero-sync-pending-v2"
    }
}
