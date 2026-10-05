package io.github.jackcuthbert.xaerosync.client

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompatibilityMetadataTest {
    @Test
    fun `metadata keeps minimum loader and both Minecraft minor versions`() {
        val metadata = requireNotNull(javaClass.getResource("/fabric.mod.json")).readText()
        assertTrue("\"fabricloader\": \">=0.19.3\"" in metadata)
        assertTrue("\"minecraft\": \">=26.2 <=26.3\"" in metadata)
    }

    @Test
    fun `version report payload rejects oversized version strings`() {
        assertFailsWith<IllegalArgumentException> { VersionReportPayload(ByteArray(129)) }
    }
}
