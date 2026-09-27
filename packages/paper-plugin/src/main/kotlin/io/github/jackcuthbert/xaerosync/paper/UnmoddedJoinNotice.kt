package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object UnmoddedJoinNotice {
    private const val PROJECT_URL = "https://github.com/JackCuthbert/xaero-sync"
    private val pendingPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun shouldNotify(listeningChannels: Set<String>): Boolean = ConnectionSyncProtocol.CHANNEL !in listeningChannels

    fun recordConfiguration(playerId: UUID, listeningChannels: Set<String>): Boolean {
        val shouldNotify = shouldNotify(listeningChannels)
        if (shouldNotify) {
            pendingPlayers.add(playerId)
        } else {
            pendingPlayers.remove(playerId)
        }
        return shouldNotify
    }

    fun takeForJoin(playerId: UUID): Boolean = pendingPlayers.remove(playerId)

    fun message(): Component = Component.text("Install Xaero Sync to sync waypoints with this server: $PROJECT_URL")
        .clickEvent(ClickEvent.openUrl(PROJECT_URL))
}
