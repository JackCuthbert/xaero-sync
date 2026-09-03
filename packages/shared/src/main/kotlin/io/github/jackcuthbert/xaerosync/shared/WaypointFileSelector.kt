package io.github.jackcuthbert.xaerosync.shared

/** Selects Xaero Minimap waypoint files without interpreting or rewriting their records. */
object WaypointFileSelector {
    private val waypointFilename = Regex("""mw\$.*\.txt""")

    fun isEligible(relativePath: String, contents: ByteArray): Boolean {
        val segments = relativePath.replace('\\', '/').split('/')
        if (segments.size < 2 || segments.any { it.isEmpty() || it == "." || it == ".." }) {
            return false
        }

        if (segments.dropLast(1).none { it.startsWith("dim%") }) {
            return false
        }

        if (!waypointFilename.matches(segments.last())) {
            return false
        }

        return contents
            .toString(Charsets.UTF_8)
            .lineSequence()
            .any { line -> line.startsWith("#waypoint:") || line.startsWith("waypoint:") }
    }
}
