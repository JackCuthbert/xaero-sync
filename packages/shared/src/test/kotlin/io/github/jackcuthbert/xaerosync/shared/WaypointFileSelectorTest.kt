package io.github.jackcuthbert.xaerosync.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointFileSelectorTest {
    @Test
    fun `includes observed default and detected-world waypoint filenames`() {
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
        assertTrue(
            WaypointFileSelector.isEligible(
                "dim%0/mw0,1,0_2.txt",
                fixture("dim%0/mw0,1,0_2.txt", "Multiplayer_localhost"),
            ),
        )
    }

    @Test
    fun `excludes minimap configuration and non-waypoint files`() {
        assertFalse(WaypointFileSelector.isEligible("config.txt", fixture("config.txt")))
        assertFalse(
            WaypointFileSelector.isEligible(
                "config.txt",
                fixture("config.txt", "Multiplayer_localhost"),
            ),
        )
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

    @Test
    fun `rejects absolute paths and platform separators on every operating system`() {
        val waypoint = fixture("dim%0/mw\$default_1.txt")

        assertFalse(WaypointFileSelector.isEligible("/dim%0/mw\$default_1.txt", waypoint))
        assertFalse(WaypointFileSelector.isEligible("C:/dim%0/mw\$default_1.txt", waypoint))
        assertFalse(WaypointFileSelector.isEligible("dim%0\\mw\$default_1.txt", waypoint))
        assertFalse(WaypointFileSelector.isEligible("C:\\dim%0\\mw\$default_1.txt", waypoint))
    }

    private fun fixture(relativePath: String, connection: String = "Multiplayer_example.invalid"): ByteArray =
        requireNotNull(
            javaClass.getResourceAsStream("/fixtures/xaero-minimap/$connection/$relativePath"),
        )
            .readBytes()
}
