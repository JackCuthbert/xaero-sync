package io.github.jackcuthbert.xaerosync.client

import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Resolves Xaero's active automatic-world file without linking against its private API. */
internal object XaeroAutomaticWorldTarget {
    private val logger = LoggerFactory.getLogger("Xaero Sync")
    private var discoveryFailureLogged = false
    private var reportedPaths: Pair<Path, Path?>? = null

    fun current(reportSelection: Boolean = false): Path? = runCatching {
        val sessionClass = Class.forName("xaero.common.XaeroMinimapSession")
        val xaeroSession = sessionClass.getMethod("getCurrentSession").invoke(null) ?: return null
        val processor = xaeroSession.javaClass.getMethod("getMinimapProcessor").invoke(xaeroSession)
        val minimapSession = processor.javaClass.getMethod("getSession").invoke(processor)
        val worldManager = minimapSession.javaClass.getMethod("getWorldManager").invoke(minimapSession)
        val automaticWorld = worldManager.javaClass.getMethod("getAutoWorld").invoke(worldManager) ?: return null
        val worldManagerIo = minimapSession.javaClass.getMethod("getWorldManagerIO").invoke(minimapSession)
        val worldFileMethod = worldManagerIo.javaClass.methods
            .first { method -> method.name == "getWorldFile" && method.parameterCount == 1 }
        val automaticFile = worldFileMethod.invoke(worldManagerIo, automaticWorld) as Path
        val selectedFile = runCatching {
            val selectedWorld = worldManager.javaClass.getMethod("getCurrentWorld").invoke(worldManager)
                ?: return@runCatching null
            worldFileMethod.invoke(worldManagerIo, selectedWorld) as Path
        }.getOrNull()
        val paths = automaticFile to selectedFile
        if (reportSelection || reportedPaths != paths) {
            logger.info("Xaero waypoint file selection: automatic={}, selected={}.", automaticFile, selectedFile)
            reportedPaths = paths
        }
        automaticFile
    }.onFailure { exception ->
        if (!discoveryFailureLogged) {
            logger.warn("Could not resolve Xaero's automatic-world waypoint file.", exception)
            discoveryFailureLogged = true
        }
    }.onSuccess {
        discoveryFailureLogged = false
    }.getOrNull()
}
