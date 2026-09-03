package io.github.jackcuthbert.xaerosync.shared

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotJsonCodecTest {
    @Test
    fun `round trips raw waypoint records including unknown Xaero fields`() {
        val snapshot = WaypointSnapshot.create(
            listOf(WaypointFile("dim%1/mw\$default_1.txt", fixture("dim%1/mw\$default_1.txt"))),
            Instant.parse("2026-09-03T05:00:00.123Z"),
        )

        val decoded = SnapshotJsonCodec.decode(SnapshotJsonCodec.encode(snapshot))

        assertEquals(snapshot.hash, decoded.hash)
        assertEquals(snapshot.updatedAt, decoded.updatedAt)
        assertContentEquals(snapshot.files.single().contents, decoded.files.single().contents)
    }

    @Test
    fun `round trips an empty manifest`() {
        val snapshot = WaypointSnapshot.create(emptyList(), Instant.EPOCH)

        val decoded = SnapshotJsonCodec.decode(SnapshotJsonCodec.encode(snapshot))

        assertEquals(emptyList(), decoded.files)
        assertEquals(snapshot.hash, decoded.hash)
    }

    @Test
    fun `rejects truncated corrupt tampered and traversal records`() {
        assertFailsWith<InvalidSnapshotRecordException> {
            SnapshotJsonCodec.decode("{\"formatVersion\":1")
        }
        assertFailsWith<InvalidSnapshotRecordException> {
            SnapshotJsonCodec.decode(
                """{"formatVersion":1,"updatedAt":"2026-09-03T05:00:00Z","hash":"bad","files":[]}""",
            )
        }
        assertFailsWith<InvalidSnapshotRecordException> {
            SnapshotJsonCodec.decode(
                """{"formatVersion":1,"updatedAt":"2026-09-03T05:00:00Z","hash":"bad","files":[{"path":"../dim%0/mw${'$'}default_1.txt","contents":"d2F5cG9pbnQ6"}]}""",
            )
        }
    }

    private fun fixture(relativePath: String): ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fixtures/xaero-minimap/Multiplayer_mc.cloud.jckcthbrt.io/$relativePath"),
    ).readBytes()
}
