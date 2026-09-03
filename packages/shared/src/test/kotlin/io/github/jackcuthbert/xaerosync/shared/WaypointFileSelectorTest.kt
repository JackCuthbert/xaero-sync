package io.github.jackcuthbert.xaerosync.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointFileSelectorTest {
    @Test
    fun `includes observed overworld and nether waypoint fixtures`() {
        assertTrue(
            WaypointFileSelector.isEligible(
                "dim%0/mw\$default_1.txt",
                fixture("dim%0/mw\$default_1.txt"),
            ),
        )
        assertTrue(
            WaypointFileSelector.isEligible(
                "dim%-1/mw\$default_1.txt",
                fixture("dim%-1/mw\$default_1.txt"),
            ),
        )
    }

    @Test
    fun `excludes minimap configuration and non-waypoint files`() {
        assertFalse(WaypointFileSelector.isEligible("config.txt", fixture("config.txt")))
        assertFalse(
            WaypointFileSelector.isEligible(
                "dim%0/notes.txt",
                "waypoint:Not a Xaero waypoint file".toByteArray(),
            ),
        )
    }

    @Test
    fun `requires a dimension directory and Xaero waypoint content`() {
        assertFalse(
            WaypointFileSelector.isEligible(
                "mw\$default_1.txt",
                fixture("dim%0/mw\$default_1.txt"),
            ),
        )
        assertFalse(
            WaypointFileSelector.isEligible(
                "dim%0/mw\$default_1.txt",
                "not a waypoint file".toByteArray(),
            ),
        )
        assertFalse(
            WaypointFileSelector.isEligible(
                "../dim%0/mw\$default_1.txt",
                fixture("dim%0/mw\$default_1.txt"),
            ),
        )
    }

    private fun fixture(relativePath: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/xaero-minimap/Multiplayer_example.invalid/$relativePath"),
    )
        .readBytes()
}
