package io.github.jackcuthbert.xaerosync.shared

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import kotlin.io.path.isRegularFile

/** Reads and replaces only waypoint files beneath one Xaero server directory. */
object WaypointSnapshotFiles {
    fun read(root: Path, updatedAt: Instant): WaypointSnapshot {
        if (Files.notExists(root)) {
            return WaypointSnapshot.create(emptyList(), updatedAt)
        }

        try {
            val files = Files.walk(root).use { paths ->
                paths.iterator().asSequence().filter(Path::isRegularFile).mapNotNull { path ->
                    val relativePath = relativePath(root, path)
                    val size = Files.size(path)
                    require(size <= SnapshotLimits.MAX_FILE_BYTES) { "Xaero file is too large: $relativePath" }
                    val contents = Files.readAllBytes(path)
                    if (WaypointFileSelector.isEligible(relativePath, contents)) {
                        WaypointFile(relativePath, contents)
                    } else {
                        null
                    }
                }.toList()
            }
            return WaypointSnapshot.create(files, updatedAt)
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not read Xaero waypoint files at $root.", exception)
        }
    }

    fun replace(root: Path, snapshot: WaypointSnapshot) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val staged = mutableListOf<Pair<Path, Path>>()
        try {
            Files.createDirectories(normalizedRoot)
            snapshot.files.forEach { file ->
                val destination = normalizedRoot.resolve(file.path).normalize()
                require(destination.startsWith(normalizedRoot)) { "Waypoint path escapes its Xaero directory." }
                Files.createDirectories(requireNotNull(destination.parent))
                val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}.", ".tmp")
                Files.write(temporary, file.contents)
                staged += temporary to destination
            }

            val retained = snapshot.files.mapTo(mutableSetOf()) { it.path }
            existingWaypointFiles(normalizedRoot)
                .filterNot { relativePath(normalizedRoot, it) in retained }
                .forEach(Files::deleteIfExists)

            staged.forEach { (temporary, destination) -> moveIntoPlace(temporary, destination) }
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not replace Xaero waypoint files at $root.", exception)
        } finally {
            staged.forEach { (temporary) -> runCatching { Files.deleteIfExists(temporary) } }
        }
    }

    /**
     * Applies a server download while translating Xaero's legacy automatic-world filename into this
     * installation's local automatic-world filename. Xaero keeps that identifier in `config.txt`,
     * which intentionally never leaves the client.
     */
    fun applyDownload(root: Path, snapshot: WaypointSnapshot): WaypointSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val applied = remapAutomaticWorldFiles(normalizedRoot, snapshot)
        replace(normalizedRoot, applied)
        return applied
    }

    fun newestModifiedAt(root: Path, snapshot: WaypointSnapshot): Instant =
        snapshot.files.maxOfOrNull { file -> Files.getLastModifiedTime(root.resolve(file.path)).toInstant() }
            ?: Instant.EPOCH

    private fun remapAutomaticWorldFiles(root: Path, snapshot: WaypointSnapshot): WaypointSnapshot {
        val automaticWorldId = readAutomaticWorldId(root) ?: return snapshot
        val localFiles = existingWaypointFiles(root).associate { path ->
            relativePath(root, path) to Files.readAllBytes(path)
        }
        val remapped = linkedMapOf<String, ByteArray>()

        snapshot.files.forEach { file ->
            val destination = automaticWorldDestination(file.path, automaticWorldId, localFiles.keys)
            val current = remapped[destination] ?: localFiles[destination]
            remapped[destination] = if (destination == file.path || current == null) {
                file.contents
            } else {
                mergeWaypointContents(current, file.contents)
            }
        }

        return WaypointSnapshot.create(
            remapped.map { (path, contents) -> WaypointFile(path, contents) },
            snapshot.updatedAt,
        )
    }

    private fun readAutomaticWorldId(root: Path): String? {
        val configuration = root.resolve("config.txt")
        if (!configuration.isRegularFile()) return null
        return Files.readAllLines(configuration)
            .firstOrNull { it.startsWith("defaultMultiworldId:") }
            ?.substringAfter(':')
            ?.takeIf(String::isNotBlank)
    }

    private fun automaticWorldDestination(path: String, automaticWorldId: String, localPaths: Set<String>): String {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        val filename = path.substringAfterLast('/')
        val suffix = LEGACY_AUTOMATIC_WORLD_FILENAME.matchEntire(filename)?.groupValues?.get(1) ?: return path
        val prefix = "$directory/$automaticWorldId"
        return localPaths.firstOrNull { it.startsWith("${prefix}_") && it.endsWith(".txt") }
            ?: "$prefix$suffix.txt"
    }

    private fun mergeWaypointContents(existing: ByteArray, downloaded: ByteArray): ByteArray {
        val existingText = existing.toString(Charsets.UTF_8)
        val existingWaypoints = existingText.lineSequence()
            .filterTo(mutableSetOf()) { it.startsWith("waypoint:") }
        val additionalWaypoints = downloaded.toString(Charsets.UTF_8).lineSequence()
            .filter { it.startsWith("waypoint:") && existingWaypoints.add(it) }
            .toList()
        if (additionalWaypoints.isEmpty()) return existing

        val separator = if (existingText.endsWith("\n")) "" else "\n"
        return (existingText + separator + additionalWaypoints.joinToString("\n") + "\n").toByteArray()
    }

    private fun existingWaypointFiles(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.iterator().asSequence().filter(Path::isRegularFile).filter { path ->
            val relativePath = relativePath(root, path)
            Files.size(path) <= SnapshotLimits.MAX_FILE_BYTES &&
                WaypointFileSelector.isEligible(relativePath, Files.readAllBytes(path))
        }.toList()
    }

    private fun relativePath(root: Path, path: Path): String = root.relativize(path).joinToString("/")

    private fun moveIntoPlace(source: Path, destination: Path) {
        try {
            Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, REPLACE_EXISTING)
        }
    }

    private val LEGACY_AUTOMATIC_WORLD_FILENAME = Regex("""mw\${'$'}default(_\d+)\.txt""")
}
