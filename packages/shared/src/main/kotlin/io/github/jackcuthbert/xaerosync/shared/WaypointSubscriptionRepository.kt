package io.github.jackcuthbert.xaerosync.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Persistent, server-owned opt-in subscriptions keyed by the authenticated subscriber UUID. */
class WaypointSubscriptionRepository(
    private val dataDirectory: Path,
    private val onUnreadableRecord: (Path, Exception) -> Unit = { _, _ -> },
) {
    private val locks = ConcurrentHashMap<UUID, Any>()

    fun subscribe(subscriberId: UUID, sourceId: UUID, sourceName: String, sourceHash: String): WaypointSubscription =
        synchronized(lockFor(subscriberId)) {
            require(subscriberId != sourceId) { "Choose another player to subscribe to." }
            val subscription = WaypointSubscription(sourceId, sourceName, SubscriptionPolicy.PROMPT, sourceHash)
            saveUnlocked(subscriberId, loadUnlocked(subscriberId).filterNot { it.sourceId == sourceId } + subscription)
            subscription
        }

    fun unsubscribe(subscriberId: UUID, sourceId: UUID): Boolean = synchronized(lockFor(subscriberId)) {
        val current = loadUnlocked(subscriberId)
        val remaining = current.filterNot { it.sourceId == sourceId }
        if (remaining.size == current.size) return@synchronized false
        saveUnlocked(subscriberId, remaining)
        true
    }

    fun subscriptions(subscriberId: UUID): List<WaypointSubscription> = synchronized(lockFor(subscriberId)) {
        loadUnlocked(subscriberId)
    }

    fun subscribers(sourceId: UUID): List<UUID> {
        val directory = subscriptionDirectory()
        if (Files.notExists(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.iterator().asSequence()
                .filter { it.fileName.toString().endsWith(".json") }
                .mapNotNull { path -> path.fileName.toString().removeSuffix(".json").toUuidOrNull() }
                .filter { subscriberId ->
                    runCatching { subscriptions(subscriberId).any { it.sourceId == sourceId } }
                        .onFailure { onUnreadableRecord(recordPath(subscriberId), it.asException()) }
                        .getOrDefault(false)
                }
                .toList()
        }
    }

    fun markPrompted(subscriberId: UUID, sourceId: UUID, hash: String): WaypointSubscription? =
        update(subscriberId, sourceId) { it.copy(lastPromptedHash = hash, remindOnNextJoin = false) }

    fun dismiss(subscriberId: UUID, sourceId: UUID, hash: String): WaypointSubscription? =
        updateIfPromptMatches(subscriberId, sourceId, hash) {
            it.copy(lastPromptedHash = hash, dismissedHash = hash, remindOnNextJoin = false)
        }

    fun remindLater(subscriberId: UUID, sourceId: UUID, hash: String): WaypointSubscription? =
        updateIfPromptMatches(subscriberId, sourceId, hash) { it.copy(remindOnNextJoin = true) }

    fun accept(subscriberId: UUID, sourceId: UUID, hash: String): WaypointSubscription? =
        updateIfPromptMatches(subscriberId, sourceId, hash) {
            it.copy(
                acceptedSourceHash = hash,
                lastPromptedHash = hash,
                dismissedHash = null,
                remindOnNextJoin = false,
            )
        }

    private fun updateIfPromptMatches(
        subscriberId: UUID,
        sourceId: UUID,
        hash: String,
        transform: (WaypointSubscription) -> WaypointSubscription,
    ): WaypointSubscription? = update(subscriberId, sourceId) {
        if (it.lastPromptedHash == hash) transform(it) else it
    }?.takeIf { it.lastPromptedHash == hash }

    private fun update(
        subscriberId: UUID,
        sourceId: UUID,
        transform: (WaypointSubscription) -> WaypointSubscription,
    ): WaypointSubscription? = synchronized(lockFor(subscriberId)) {
        val current = loadUnlocked(subscriberId)
        val index = current.indexOfFirst { it.sourceId == sourceId }
        if (index < 0) return@synchronized null
        val updated = transform(current[index])
        saveUnlocked(subscriberId, current.toMutableList().also { it[index] = updated })
        updated
    }

    private fun loadUnlocked(subscriberId: UUID): List<WaypointSubscription> {
        val path = recordPath(subscriberId)
        if (Files.notExists(path)) return emptyList()
        val record = json.decodeFromString<SubscriptionRecord>(Files.readString(path))
        require(record.version == FORMAT_VERSION) { "Unsupported subscription record." }
        return record.subscriptions.map { stored ->
            WaypointSubscription(
                UUID.fromString(stored.sourcePlayerId),
                stored.sourcePlayerName,
                stored.policy,
                stored.acceptedSourceHash,
                stored.lastPromptedHash,
                stored.dismissedHash,
                stored.remindOnNextJoin,
            )
        }
    }

    private fun saveUnlocked(subscriberId: UUID, subscriptions: List<WaypointSubscription>) {
        val path = recordPath(subscriberId)
        if (subscriptions.isEmpty()) {
            Files.deleteIfExists(path)
            return
        }
        val record = SubscriptionRecord(
            subscriptions = subscriptions.map {
                StoredSubscription(
                    it.sourceId.toString(),
                    it.sourceName,
                    it.policy,
                    it.acceptedSourceHash,
                    it.lastPromptedHash,
                    it.dismissedHash,
                    it.remindOnNextJoin,
                )
            },
        )
        val parent = requireNotNull(path.parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
        try {
            FileChannel.open(temporary, CREATE, WRITE, TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(json.encodeToString(record).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun lockFor(subscriberId: UUID): Any = locks.computeIfAbsent(subscriberId) { Any() }
    private fun subscriptionDirectory() = dataDirectory.resolve("subscriptions")
    private fun recordPath(subscriberId: UUID) = subscriptionDirectory().resolve("$subscriberId.json")

    private companion object {
        const val FORMAT_VERSION = 1
        val json = Json { prettyPrint = true }
    }
}

enum class SubscriptionPolicy { PROMPT }

data class WaypointSubscription(
    val sourceId: UUID,
    val sourceName: String,
    val policy: SubscriptionPolicy,
    val acceptedSourceHash: String,
    val lastPromptedHash: String? = null,
    val dismissedHash: String? = null,
    val remindOnNextJoin: Boolean = false,
) {
    fun shouldPrompt(sourceHash: String, joining: Boolean): Boolean = sourceHash != acceptedSourceHash &&
        sourceHash != dismissedHash &&
        (sourceHash != lastPromptedHash || joining && remindOnNextJoin)
}

@Serializable
private data class SubscriptionRecord(val version: Int = 1, val subscriptions: List<StoredSubscription>)

@Serializable
private data class StoredSubscription(
    val sourcePlayerId: String,
    val sourcePlayerName: String,
    val policy: SubscriptionPolicy,
    val acceptedSourceHash: String,
    val lastPromptedHash: String? = null,
    val dismissedHash: String? = null,
    val remindOnNextJoin: Boolean = false,
)

private fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()
private fun Throwable.asException(): Exception = this as? Exception ?: RuntimeException(this)
