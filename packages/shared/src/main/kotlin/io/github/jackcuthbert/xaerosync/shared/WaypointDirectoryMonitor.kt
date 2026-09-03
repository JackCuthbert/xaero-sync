package io.github.jackcuthbert.xaerosync.shared

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.time.Clock
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory

/** Watches one Xaero connection tree and backs event delivery with complete periodic rescans. */
class WaypointDirectoryMonitor(
    private val root: Path,
    private val stateStore: ClientSyncStateStore,
    private val scope: String,
    initialSyncedHash: String,
    private val onChanged: (WaypointSnapshot) -> Unit,
    private val clock: Clock = Clock.systemUTC(),
    private val debounce: Duration = Duration.ofSeconds(2),
    rescanInterval: Duration = Duration.ofMinutes(2),
) : Closeable {
    private val watchService: WatchService = root.fileSystem.newWatchService()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread.ofPlatform().daemon().name("xaero-sync-monitor").unstarted(task)
    }
    private val watcherThread = Thread.ofPlatform().daemon().name("xaero-sync-watcher").unstarted(::watch)
    private val keys = mutableMapOf<WatchKey, Path>()
    private var syncedHash = initialSyncedHash
    private var offeredHash: String? = null
    private var debounceTask: ScheduledFuture<*>? = null

    init {
        Files.createDirectories(root)
        registerTree()
        scheduler.scheduleWithFixedDelay(
            ::scanSafely,
            rescanInterval.toMillis(),
            rescanInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        watcherThread.start()
    }

    @Synchronized
    fun acknowledge(hash: String) {
        syncedHash = hash
        if (offeredHash == hash) offeredHash = null
    }

    @Synchronized
    fun retry() {
        offeredHash = null
        scheduleScan(Duration.ZERO)
    }

    fun flush() = scanSafely()

    override fun close() {
        watchService.close()
        watcherThread.interrupt()
        scheduler.shutdownNow()
    }

    private fun watch() {
        while (!scheduler.isShutdown) {
            val key = runCatching { watchService.take() }.getOrNull() ?: return
            val directory = synchronized(this) { keys[key] } ?: continue
            key.pollEvents().forEach { event ->
                if (event.kind() == ENTRY_CREATE) {
                    val created = directory.resolve(event.context() as Path)
                    if (created.isDirectory()) registerTree(created)
                }
            }
            if (!key.reset()) synchronized(this) { keys.remove(key) }
            scheduleScan(debounce)
        }
    }

    @Synchronized
    private fun scheduleScan(delay: Duration) {
        debounceTask?.cancel(false)
        debounceTask = scheduler.schedule(::scanSafely, delay.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun scanSafely() {
        runCatching {
            val snapshot = stateStore.observe(scope, root, clock.instant())
            val shouldOffer = synchronized(this) {
                snapshot.hash != syncedHash && snapshot.hash != offeredHash
            }
            if (shouldOffer) {
                onChanged(snapshot)
                synchronized(this) { offeredHash = snapshot.hash }
            }
        }
    }

    private fun registerTree(start: Path = root) {
        Files.walk(start).use { paths ->
            paths.iterator().asSequence().filter(Path::isDirectory).forEach { directory ->
                val key = directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                synchronized(this) { keys[key] = directory }
            }
        }
    }
}
