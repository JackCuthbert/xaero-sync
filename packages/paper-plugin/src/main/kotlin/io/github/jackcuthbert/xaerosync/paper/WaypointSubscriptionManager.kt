package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import io.github.jackcuthbert.xaerosync.shared.WaypointSubscriptionRepository
import java.util.UUID

internal class WaypointSubscriptionManager(
    private val snapshots: PlayerSnapshotRepository,
    private val subscriptions: WaypointSubscriptionRepository,
    private val notify: (UUID, SubscriptionUpdate) -> Boolean,
) {
    fun subscribe(subscriberId: UUID, sourceId: UUID, sourceName: String) {
        val source = requireNotNull(snapshots.load(sourceId)) { "Source player has no waypoint backup." }
        subscriptions.subscribe(subscriberId, sourceId, sourceName, source.hash)
    }

    fun unsubscribe(subscriberId: UUID, sourceId: UUID): Boolean = subscriptions.unsubscribe(subscriberId, sourceId)

    fun sourceNames(subscriberId: UUID): List<String> = subscriptions.subscriptions(subscriberId).map { it.sourceName }

    fun sourceChanged(sourceId: UUID) {
        runCatching {
            subscriptions.subscribers(sourceId).forEach { deliver(it, sourceId, joining = false) }
        }
    }

    fun subscriberJoined(subscriberId: UUID) {
        subscriptions.subscriptions(subscriberId).forEach { deliver(subscriberId, it.sourceId, joining = true) }
    }

    fun dismiss(subscriberId: UUID, sourceId: UUID, hash: String): Boolean =
        subscriptions.dismiss(subscriberId, sourceId, hash) != null

    fun remindLater(subscriberId: UUID, sourceId: UUID, hash: String): Boolean =
        subscriptions.remindLater(subscriberId, sourceId, hash) != null

    fun accept(subscriberId: UUID, sourceId: UUID, hash: String): Boolean {
        val subscription = subscriptions.subscriptions(subscriberId).singleOrNull { it.sourceId == sourceId }
            ?: return false
        if (
            subscription.lastPromptedHash != hash ||
            subscription.dismissedHash == hash ||
            subscription.remindOnNextJoin
        ) {
            return false
        }
        val source = snapshots.load(sourceId) ?: return false
        if (source.hash != hash) return false
        snapshots.replace(subscriberId, sourceId)
        subscriptions.accept(subscriberId, sourceId, hash)
        sourceChanged(subscriberId)
        return true
    }

    private fun deliver(subscriberId: UUID, sourceId: UUID, joining: Boolean) {
        val subscription = subscriptions.subscriptions(subscriberId).singleOrNull { it.sourceId == sourceId } ?: return
        val source = snapshots.load(sourceId) ?: return
        if (!subscription.shouldPrompt(source.hash, joining)) return
        if (notify(subscriberId, SubscriptionUpdate(sourceId, subscription.sourceName, source))) {
            subscriptions.markPrompted(subscriberId, sourceId, source.hash)
        }
    }
}

internal data class SubscriptionUpdate(val sourceId: UUID, val sourceName: String, val snapshot: WaypointSnapshot)
