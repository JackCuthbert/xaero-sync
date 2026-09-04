package io.github.jackcuthbert.xaerosync.client

import java.nio.file.Path

/** Resolves Xaero's active automatic-world file without linking against its private API. */
internal object XaeroAutomaticWorldTarget {
    fun current(): Path? = runCatching {
        val sessionClass = Class.forName("xaero.common.XaeroMinimapSession")
        val xaeroSession = sessionClass.getMethod("getCurrentSession").invoke(null) ?: return null
        val processor = xaeroSession.javaClass.getMethod("getMinimapProcessor").invoke(xaeroSession)
        val minimapSession = processor.javaClass.getMethod("getSession").invoke(processor)
        val worldManager = minimapSession.javaClass.getMethod("getWorldManager").invoke(minimapSession)
        val automaticWorld = worldManager.javaClass.getMethod("getAutoWorld").invoke(worldManager) ?: return null
        val worldManagerIo = minimapSession.javaClass.getMethod("getWorldManagerIO").invoke(minimapSession)
        worldManagerIo.javaClass.methods
            .first { method -> method.name == "getWorldFile" && method.parameterCount == 1 }
            .invoke(worldManagerIo, automaticWorld) as Path
    }.getOrNull()
}
