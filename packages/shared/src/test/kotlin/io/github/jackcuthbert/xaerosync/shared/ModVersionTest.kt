package io.github.jackcuthbert.xaerosync.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModVersionTest {
    @Test
    fun `compares numeric release and prerelease versions`() {
        assertEquals(-1, ModVersion.compare("0.9.10", "0.10.0"))
        assertEquals(-1, ModVersion.compare("1.0.0-rc.2", "1.0.0-rc.10"))
        assertEquals(-1, ModVersion.compare("1.0.0-beta", "1.0.0"))
        assertEquals(0, ModVersion.compare("1.0.0+one", "1.0.0+two"))
        assertNull(ModVersion.compare("bad", "1.0.0"))
        assertNull(ModVersion.compare("01.0.0", "1.0.0"))
        assertNull(ModVersion.compare("1.0.0+", "1.0.0"))
    }

    @Test
    fun `rejects non-ASCII version digits and identifiers`() {
        assertNull(ModVersion.compare("١.0.0", "1.0.0"))
        assertNull(ModVersion.compare("1.0.0-pré", "1.0.0"))
        assertNull(ModVersion.compare("1.0.0+méta", "1.0.0"))
    }
}
