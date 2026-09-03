package io.github.jackcuthbert.xaerosync.paper

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class CommandTextTest {
    private val now = Instant.parse("2026-09-04T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `last backup time is expressed in useful relative units`() {
        assertEquals("just now", CommandText.relativeTime(now.minusSeconds(30), clock))
        assertEquals("1 minute ago", CommandText.relativeTime(now.minusSeconds(60), clock))
        assertEquals("2 minutes ago", CommandText.relativeTime(now.minusSeconds(120), clock))
        assertEquals("3 hours ago", CommandText.relativeTime(now.minusSeconds(10_800), clock))
        assertEquals("2 days ago", CommandText.relativeTime(now.minusSeconds(172_800), clock))
    }

    @Test
    fun `future clock differences are presented as just now`() {
        assertEquals("just now", CommandText.relativeTime(now.plusSeconds(60), clock))
    }

    @Test
    fun `restore confirmation preserves optional administrator target`() {
        assertEquals(
            "/xaerosync restore backup-1 --confirm",
            CommandText.restoreCommand("backup-1", null),
        )
        assertEquals(
            "/xaerosync restore backup-1 --confirm PlayerName",
            CommandText.restoreCommand("backup-1", "PlayerName"),
        )
    }

    @Test
    fun `backup sizes are readable`() {
        assertEquals("512 B", CommandText.humanBytes(512))
        assertEquals("2 KiB", CommandText.humanBytes(2048))
        assertEquals("3 MiB", CommandText.humanBytes(3 * 1024 * 1024))
    }
}
