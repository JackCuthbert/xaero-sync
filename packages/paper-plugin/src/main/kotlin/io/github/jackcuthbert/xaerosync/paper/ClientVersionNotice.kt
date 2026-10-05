package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.github.jackcuthbert.xaerosync.shared.ModVersion
import io.github.jackcuthbert.xaerosync.shared.ModVersionReport
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object ClientVersionNotice {
    private const val RELEASES_URL = "https://github.com/JackCuthbert/xaero-sync/releases"
    private data class Session(
        var channels: Set<String> = emptySet(),
        var joined: Boolean = false,
        var reportedVersion: String? = null,
        var notified: Boolean = false,
    )

    private val sessions = ConcurrentHashMap<UUID, Session>()

    fun decode(bytes: ByteArray): String? {
        if (bytes.size !in 1..ModVersionReport.MAX_BYTES) return null
        return runCatching { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }

    fun shouldNotify(clientVersion: String, serverVersion: String): Boolean =
        ModVersion.compare(clientVersion, serverVersion)?.let { it < 0 } == true

    fun configure(playerId: UUID, channels: Set<String>) {
        val session = sessions.computeIfAbsent(playerId) { Session() }
        synchronized(session) { session.channels = channels.toSet() }
    }

    fun join(playerId: UUID, serverVersion: String): Boolean {
        val session = sessions.computeIfAbsent(playerId) { Session() }
        synchronized(session) {
            session.joined = true
            return shouldNotifyAtJoin(session, serverVersion)
        }
    }

    fun report(playerId: UUID, version: String, serverVersion: String): Boolean {
        if (ModVersion.compare(version, version) == null) return false
        val session = sessions.computeIfAbsent(playerId) { Session() }
        synchronized(session) {
            session.reportedVersion = version
            return session.joined && shouldNotifyAtJoin(session, serverVersion)
        }
    }

    private fun shouldNotifyAtJoin(session: Session, serverVersion: String): Boolean {
        if (session.notified) return false
        val outdated = session.reportedVersion?.let { shouldNotify(it, serverVersion) }
            ?: (ConnectionSyncProtocol.CHANNEL in session.channels && ModVersionReport.CHANNEL !in session.channels)
        if (outdated) session.notified = true
        return outdated
    }

    fun clear(playerId: UUID) {
        sessions.remove(playerId)
    }

    fun message(): Component =
        Component.text("Your Xaero Sync client is outdated. Download the latest version: $RELEASES_URL")
            .clickEvent(ClickEvent.openUrl(RELEASES_URL))
}
