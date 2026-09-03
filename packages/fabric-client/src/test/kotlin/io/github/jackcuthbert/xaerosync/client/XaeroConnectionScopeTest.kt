package io.github.jackcuthbert.xaerosync.client

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class XaeroConnectionScopeTest {
    @Test
    fun `address selects Xaero server directory while port remains part of sidecar scope`() {
        val gameDirectory = Path.of("game")
        val standard = XaeroConnectionScope.from(gameDirectory, "Example.COM:25565")
        val alternatePort = XaeroConnectionScope.from(gameDirectory, "example.com:25566")

        assertEquals(gameDirectory.resolve("xaero/minimap/Multiplayer_example.com"), standard.waypointRoot)
        assertEquals("example.com:25565", standard.address)
        assertNotEquals(standard.sidecarPath, alternatePort.sidecarPath)
    }
}
