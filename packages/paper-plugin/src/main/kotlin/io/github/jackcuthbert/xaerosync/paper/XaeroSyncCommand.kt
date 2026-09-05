package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.StoredSnapshot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

internal class XaeroSyncCommand(
    private val runtime: CommandRuntime,
    private val repository: PlayerSnapshotRepository,
    private val subscriptions: WaypointSubscriptionManager? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CommandExecutor,
    TabCompleter {
    constructor(
        plugin: XaeroSyncPlugin,
        repository: PlayerSnapshotRepository,
        subscriptions: WaypointSubscriptionManager,
    ) : this(PaperCommandRuntime(plugin), repository, subscriptions)

    private val knownSnapshotIds = ConcurrentHashMap<UUID, List<String>>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        execute(sender, label, args)
        return true
    }

    internal fun execute(sender: CommandSender, label: String, args: Array<out String>) {
        if (!sender.hasPermission(COMMAND_PERMISSION)) {
            reply(sender, error("You don't have permission to use Xaero Sync."))
            return
        }
        val subcommand = args.firstOrNull()?.lowercase() ?: "status"
        if (subcommand == "help") {
            help(sender)
            return
        }
        if (subcommand == "subscription") {
            subscriptionAction(sender, args)
            return
        }
        if (subcommand !in SUBCOMMANDS) {
            reply(sender, error("Unknown command. Use /$label help."))
            return
        }
        if (subcommand == "diagnostics" && !sender.hasPermission(ADMIN_PERMISSION)) {
            reply(sender, error("You need administrator permission to view diagnostics."))
            return
        }
        if (subcommand in setOf("replace", "subscribe", "unsubscribe") && !sender.hasPermission(REPLACE_PERMISSION)) {
            reply(sender, error("You don't have permission to copy or follow waypoint backups."))
            return
        }
        if (
            subcommand in setOf("backups", "snapshots") &&
            args.getOrNull(1) != null &&
            args[1].toIntOrNull() == null &&
            !sender.hasPermission(ADMIN_PERMISSION)
        ) {
            reply(sender, error("Page must be a positive number."))
            return
        }
        val target = resolvePlayer(sender, targetArgument(subcommand, args)) ?: return
        val source = if (subcommand in setOf("replace", "subscribe", "unsubscribe")) {
            resolveReplacementSource(sender, args)
        } else {
            null
        }
        if (subcommand in setOf("replace", "subscribe", "unsubscribe") && source == null) return
        runtime.runStorage {
            runCatching {
                when (subcommand) {
                    "status" -> status(sender, target)
                    "backup" -> backup(sender, target)
                    "backups", "snapshots" -> backups(sender, target, requestedPage(args))
                    "replace" -> replace(sender, target, requireNotNull(source), args)
                    "subscribe" -> subscribe(sender, target, requireNotNull(source))
                    "unsubscribe" -> unsubscribe(sender, target, requireNotNull(source))
                    "restore" -> restore(sender, target, args)
                    "diagnostics" -> diagnostics(sender, target)
                }
            }.onFailure {
                runtime.logFailure(target.id, it)
                reply(sender, error(friendlyError(it)))
            }
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = complete(sender, args)

    internal fun complete(sender: CommandSender, args: Array<out String>): List<String> {
        if (!sender.hasPermission(COMMAND_PERMISSION)) return emptyList()
        val candidates = when (args.size) {
            1 -> buildList {
                addAll(listOf("status", "backup", "backups", "replace", "subscribe", "unsubscribe", "restore", "help"))
                if (sender.hasPermission(ADMIN_PERMISSION)) add("diagnostics")
            }
            2 -> when (args[0].lowercase()) {
                "restore" -> sender.playerId()?.let { knownSnapshotIds[it] }.orEmpty()
                "replace", "subscribe", "unsubscribe" -> sourcePlayers(sender)
                "backups", "snapshots" -> listOf("1") + adminTargets(sender)
                "status", "backup", "diagnostics" -> adminTargets(sender)
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "restore" -> listOf(CONFIRM_FLAG) + adminTargets(sender)
                "replace" -> listOf(CONFIRM_FLAG)
                "backups", "snapshots" -> if (args[1].toIntOrNull() != null) adminTargets(sender) else emptyList()
                else -> emptyList()
            }
            4 -> if (args[0].equals("restore", true) && args[2] == CONFIRM_FLAG) adminTargets(sender) else emptyList()
            else -> emptyList()
        }
        return candidates.filter { it.startsWith(args.last(), ignoreCase = true) }
    }

    private fun status(sender: CommandSender, target: TargetPlayer) {
        val status = repository.status(target.id)
        reply(sender, title("Xaero Sync"))
        val updatedAt = status.canonicalUpdatedAt
        if (updatedAt == null) {
            reply(sender, warning("No waypoint backup exists yet. It will be created when the client connects."))
        } else {
            reply(sender, success("Waypoints backed up ${CommandText.relativeTime(updatedAt, clock)}"))
            reply(sender, muted("${status.canonicalFileCount} waypoint files • ${status.snapshotCount} restore points"))
        }
        reply(sender, actions(target.argument))
        subscriptions?.sourceNames(target.id)?.takeIf { it.isNotEmpty() }?.let { sourceNames ->
            reply(sender, muted("Following waypoint updates from: ${sourceNames.joinToString()}"))
        }
    }

    private fun diagnostics(sender: CommandSender, target: TargetPlayer) {
        val status = repository.status(target.id)
        reply(sender, title("Xaero Sync diagnostics"))
        reply(sender, Component.text("Player: ${target.name} (${target.id})", NamedTextColor.GRAY))
        reply(sender, Component.text("Hash: ${status.canonicalHash?.take(12) ?: "none"}", NamedTextColor.GRAY))
        reply(sender, Component.text("Updated: ${status.canonicalUpdatedAt ?: "never"}", NamedTextColor.GRAY))
        reply(
            sender,
            Component.text(
                "Canonical: ${status.canonicalFileCount} files, ${CommandText.humanBytes(status.canonicalBytes)}; " +
                    "restore points: ${status.snapshotCount}, ${CommandText.humanBytes(status.snapshotBytes)}",
                NamedTextColor.GRAY,
            ),
        )
        val retention = status.snapshotRetentionLimit?.toString() ?: "unlimited"
        reply(sender, Component.text("Retention: $retention restore points per player", NamedTextColor.GRAY))
    }

    private fun backup(sender: CommandSender, target: TargetPlayer) {
        val snapshot = repository.backup(target.id)
        remember(target.id)
        reply(sender, success("Restore point created: ${CommandText.displayTime(snapshot.createdAt)}"))
        reply(sender, restoreAction(snapshot.id, target.argument))
    }

    private fun backups(sender: CommandSender, target: TargetPlayer, requestedPage: Int?) {
        if (requestedPage == null) {
            reply(sender, error("Page must be a positive number."))
            return
        }
        val snapshots = repository.listSnapshots(target.id)
        knownSnapshotIds[target.id] = snapshots.map { it.id }
        if (snapshots.isEmpty()) {
            reply(sender, warning("No restore points yet. Use /xaerosync backup to create one."))
            return
        }
        val pageCount = (snapshots.size + PAGE_SIZE - 1) / PAGE_SIZE
        if (requestedPage !in 1..pageCount) {
            reply(sender, error("Page must be between 1 and $pageCount."))
            return
        }
        reply(sender, title("Xaero Sync restore points — page $requestedPage of $pageCount"))
        val start = (requestedPage - 1) * PAGE_SIZE
        snapshots.drop(start).take(PAGE_SIZE).forEachIndexed { index, snapshot ->
            reply(sender, backupLine(start + index + 1, snapshot, target.argument))
        }
        reply(sender, pageActions(requestedPage, pageCount, target.argument))
        reply(sender, muted("Restoring saves your current backup first."))
    }

    private fun restore(sender: CommandSender, target: TargetPlayer, args: Array<out String>) {
        val snapshotId = args.getOrNull(1)
        if (snapshotId == null) {
            reply(sender, error("Choose a restore point with /xaerosync backups."))
            return
        }
        if (!isConfirmation(args.getOrNull(2))) {
            val command = CommandText.restoreCommand(snapshotId, target.argument)
            reply(sender, warning("Restore this backup? Your current server backup will be saved first."))
            reply(
                sender,
                Component.text("[Confirm restore]", NamedTextColor.RED)
                    .clickEvent(ClickEvent.runCommand(command))
                    .hoverEvent(HoverEvent.showText(Component.text(command))),
            )
            return
        }
        repository.restore(target.id, snapshotId)
        subscriptions?.sourceChanged(target.id)
        remember(target.id)
        reply(sender, success("Waypoints restored. Reconnect to load them into Xaero."))
        if (sender.playerId() != target.id) {
            runtime.notifyPlayer(target.id, success("Your waypoints were restored. Reconnect to load them into Xaero."))
        }
    }

    private fun replace(sender: CommandSender, target: TargetPlayer, source: OnlinePlayer, args: Array<out String>) {
        val sourceSnapshot = repository.load(source.id)
        if (sourceSnapshot == null) {
            reply(sender, error("${source.name} has no waypoint backup yet."))
            return
        }
        if (!isConfirmation(args.getOrNull(2))) {
            reply(sender, title("Replace your waypoints?"))
            reply(sender, Component.text("From: ${source.name}", NamedTextColor.WHITE))
            reply(
                sender,
                muted(
                    "${sourceSnapshot.files.dimensionCount()} dimensions • " +
                        "${sourceSnapshot.files.size} files • ${sourceSnapshot.files.waypointCount()} waypoints",
                ),
            )
            reply(sender, muted("Last updated ${CommandText.displayTime(sourceSnapshot.updatedAt)}"))
            reply(sender, warning("Your current server backup will be saved first. Reconnect to load the replacement."))
            val command = "/xaerosync replace ${source.name} $CONFIRM_FLAG"
            reply(sender, action("[Confirm replacement]", command, NamedTextColor.RED))
            return
        }
        repository.replace(target.id, source.id)
        subscriptions?.sourceChanged(target.id)
        remember(target.id)
        reply(sender, success("Waypoints replaced from ${source.name}. Reconnect to load them into Xaero."))
    }

    private fun subscribe(sender: CommandSender, target: TargetPlayer, source: OnlinePlayer) {
        val manager = requireNotNull(subscriptions) { "Waypoint subscriptions are unavailable." }
        manager.subscribe(target.id, source.id, source.name)
        reply(sender, title("Following ${source.name}'s waypoints"))
        reply(sender, success("You'll be notified when ${source.name} uploads an update."))
        reply(sender, muted("Your current waypoints are unchanged. Every replacement still needs your confirmation."))
        reply(sender, action("[Copy ${source.name}'s current waypoints]", "/xaerosync replace ${source.name}"))
    }

    private fun unsubscribe(sender: CommandSender, target: TargetPlayer, source: OnlinePlayer) {
        val removed = requireNotNull(subscriptions) { "Waypoint subscriptions are unavailable." }
            .unsubscribe(target.id, source.id)
        if (removed) {
            reply(sender, success("Unsubscribed from ${source.name}'s waypoint updates."))
        } else {
            reply(sender, warning("You aren't subscribed to ${source.name}."))
        }
    }

    private fun subscriptionAction(sender: CommandSender, args: Array<out String>) {
        val subscriberId = sender.playerId()
        if (subscriberId == null) {
            reply(sender, error("Subscription prompts can only be handled in game."))
            return
        }
        if (!sender.hasPermission(REPLACE_PERMISSION)) {
            reply(sender, error("You don't have permission to copy or follow waypoint backups."))
            return
        }
        val action = args.getOrNull(1)
        val sourceId = args.getOrNull(2)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val hash = args.getOrNull(3)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (action !in setOf("accept", "dismiss", "remind") || sourceId == null || hash == null) {
            reply(sender, error("That waypoint update action is no longer valid."))
            return
        }
        val manager = requireNotNull(subscriptions) { "Waypoint subscriptions are unavailable." }
        runtime.runStorage {
            val handled = when (action) {
                "accept" -> manager.accept(subscriberId, sourceId, hash)
                "dismiss" -> manager.dismiss(subscriberId, sourceId, hash)
                else -> manager.remindLater(subscriberId, sourceId, hash)
            }
            if (!handled) {
                reply(sender, warning("That waypoint update is no longer available."))
            } else {
                when (action) {
                    "accept" -> reply(sender, success("Waypoints replaced. Reconnect to load them into Xaero."))
                    "dismiss" -> reply(sender, muted("Waypoint update dismissed."))
                    else -> reply(sender, muted("You'll be reminded the next time you join."))
                }
            }
        }
    }

    private fun resolveReplacementSource(sender: CommandSender, args: Array<out String>): OnlinePlayer? {
        val sourceName = args.getOrNull(1)
        if (sourceName == null) {
            reply(sender, error("Choose a player whose waypoints you want to use."))
            return null
        }
        return runtime.findKnownPlayer(sourceName) ?: run {
            reply(sender, error("Player '$sourceName' is not known to this server."))
            null
        }
    }

    private fun resolvePlayer(sender: CommandSender, argument: String?): TargetPlayer? {
        val senderId = sender.playerId()
        if (argument == null && senderId != null) return TargetPlayer(senderId, sender.name, null)
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            reply(sender, error("You need administrator permission to manage another player."))
            return null
        }
        if (argument == null) {
            reply(sender, error("Console must specify an online player name or UUID."))
            return null
        }
        val online = runtime.findOnlinePlayer(argument)
        val id = online?.id ?: runCatching { UUID.fromString(argument) }.getOrNull()
        if (id == null) {
            reply(sender, error("Player '$argument' is not online. Use their UUID instead."))
            return null
        }
        return TargetPlayer(id, online?.name ?: argument, argument)
    }

    private fun help(sender: CommandSender) {
        reply(sender, title("Xaero Sync"))
        reply(sender, helpLine("/xaerosync", "backup status"))
        reply(sender, helpLine("/xaerosync backup", "create a restore point"))
        reply(sender, helpLine("/xaerosync backups [page]", "view and restore backups"))
        if (sender.hasPermission(REPLACE_PERMISSION)) {
            reply(sender, helpLine("/xaerosync replace <player>", "use another player's waypoint backup"))
            reply(sender, helpLine("/xaerosync subscribe <player>", "follow future waypoint updates"))
            reply(sender, helpLine("/xaerosync unsubscribe <player>", "stop following waypoint updates"))
        }
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            reply(sender, helpLine("/xaerosync diagnostics <player>", "technical backup details"))
            reply(sender, muted("Admins may use an online player name or UUID."))
        }
    }

    private fun helpLine(command: String, description: String) = Component.text(command, NamedTextColor.AQUA)
        .append(Component.text(" — $description", NamedTextColor.GRAY))

    private fun actions(target: String?) = Component.text()
        .append(action("[Create backup]", "/xaerosync backup${target.suffix()}"))
        .append(Component.space())
        .append(action("[View restore points]", "/xaerosync backups${target.suffix()}"))
        .build()

    private fun pageActions(page: Int, pageCount: Int, target: String?): Component {
        val result = Component.text()
        if (page > 1) result.append(action("[Previous]", "/xaerosync backups ${page - 1}${target.suffix()}"))
        if (page > 1 && page < pageCount) result.append(Component.space())
        if (page < pageCount) result.append(action("[Next]", "/xaerosync backups ${page + 1}${target.suffix()}"))
        return result.build()
    }

    private fun action(label: String, command: String, color: NamedTextColor = NamedTextColor.AQUA) =
        Component.text(label, color)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command)))

    private fun backupLine(number: Int, snapshot: StoredSnapshot, target: String?) = Component.text()
        .append(Component.text("$number. ${CommandText.displayTime(snapshot.createdAt)}", NamedTextColor.WHITE))
        .append(muted(" • ${snapshot.fileCount} files • ${CommandText.humanBytes(snapshot.bytes)} "))
        .append(restoreAction(snapshot.id, target))
        .build()

    private fun restoreAction(snapshotId: String, target: String?) = Component.text("[Restore]", NamedTextColor.AQUA)
        .clickEvent(ClickEvent.suggestCommand("/xaerosync restore $snapshotId${target.suffix()}"))
        .hoverEvent(HoverEvent.showText(Component.text("Restore point ID: $snapshotId")))

    private fun adminTargets(sender: CommandSender): List<String> =
        if (sender.hasPermission(ADMIN_PERMISSION)) runtime.onlinePlayers().map { it.name } else emptyList()

    private fun sourcePlayers(sender: CommandSender): List<String> =
        if (sender.hasPermission(REPLACE_PERMISSION)) runtime.knownPlayers().map { it.name } else emptyList()

    private fun targetArgument(subcommand: String, args: Array<out String>): String? = when (subcommand) {
        "backups", "snapshots" -> if (args.getOrNull(1)?.toIntOrNull() != null) args.getOrNull(2) else args.getOrNull(1)
        "replace", "subscribe", "unsubscribe" -> null
        "restore" -> if (isConfirmation(args.getOrNull(2))) args.getOrNull(3) else args.getOrNull(2)
        else -> args.getOrNull(1)
    }

    private fun requestedPage(args: Array<out String>): Int? = args.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
        ?: if (args.size <= 1 || args[1].toIntOrNull() == null) 1 else null

    private fun remember(playerId: UUID) {
        knownSnapshotIds[playerId] = repository.listSnapshots(playerId).map { it.id }
    }

    private fun reply(sender: CommandSender, message: Component) {
        runtime.runMain { sender.sendMessage(message) }
    }

    private fun friendlyError(error: Throwable): String = when (error.message) {
        "Player has no canonical snapshot." -> "No waypoint backup exists yet. Connect with the client mod first."
        "Source player has no waypoint backup." -> "That player has no waypoint backup yet."
        "Choose another player to subscribe to." -> "Choose another player to follow."
        "Snapshot does not exist." -> "That restore point no longer exists. Use /xaerosync backups to refresh the list."
        "Invalid snapshot identifier." -> "That restore point is invalid. Use /xaerosync backups to choose one."
        else -> "The operation failed. Check the server log for details."
    }

    private fun CommandSender.playerId(): UUID? = (this as? Player)?.uniqueId
    private fun String?.suffix(): String = this?.let { " $it" } ?: ""
    private fun isConfirmation(argument: String?): Boolean = argument == CONFIRM_FLAG || argument == "confirm"
    private fun Collection<io.github.jackcuthbert.xaerosync.shared.WaypointFile>.dimensionCount(): Int =
        map { it.path.substringBefore('/') }.distinct().size
    private fun Collection<io.github.jackcuthbert.xaerosync.shared.WaypointFile>.waypointCount(): Int =
        sumOf { it.contents.toString(Charsets.UTF_8).lineSequence().count { line -> line.startsWith("waypoint:") } }
    private data class TargetPlayer(val id: UUID, val name: String, val argument: String?)

    private companion object {
        const val COMMAND_PERMISSION = "xaerosync.command"
        const val ADMIN_PERMISSION = "xaerosync.admin"
        const val REPLACE_PERMISSION = "xaerosync.replace"
        const val CONFIRM_FLAG = "--confirm"
        const val PAGE_SIZE = 5
        val SUBCOMMANDS = setOf(
            "status", "backup", "backups", "snapshots", "replace", "subscribe", "unsubscribe", "restore", "diagnostics",
        )
        fun title(message: String): Component = Component.text(message, NamedTextColor.GOLD)
        fun success(message: String): Component = Component.text("✓ $message", NamedTextColor.GREEN)
        fun warning(message: String): Component = Component.text(message, NamedTextColor.YELLOW)
        fun muted(message: String): Component = Component.text(message, NamedTextColor.GRAY)
        fun error(message: String): Component = Component.text("Xaero Sync: $message", NamedTextColor.RED)
    }
}

internal fun subscriptionPrompt(update: SubscriptionUpdate): Component {
    val commandPrefix = "/xaerosync subscription"
    fun command(action: String) = "$commandPrefix $action ${update.sourceId} ${update.snapshot.hash}"
    fun promptAction(label: String, action: String, color: NamedTextColor) = Component.text(label, color)
        .clickEvent(ClickEvent.runCommand(command(action)))
        .hoverEvent(HoverEvent.showText(Component.text(command(action))))
    return Component.text()
        .append(Component.text("Xaero Sync — ${update.sourceName} has new waypoints", NamedTextColor.GOLD))
        .append(Component.newline())
        .append(
            Component.text(
                "${update.snapshot.files.map { it.path.substringBefore('/') }.distinct().size} dimensions • " +
                    "${update.snapshot.files.size} files • " +
                    "${update.snapshot.files.sumOf { file ->
                        file.contents.toString(Charsets.UTF_8).lineSequence().count { it.startsWith("waypoint:") }
                    }} waypoints",
                NamedTextColor.GRAY,
            ),
        )
        .append(Component.newline())
        .append(Component.text("Uploaded ${CommandText.displayTime(update.snapshot.updatedAt)}", NamedTextColor.GRAY))
        .append(Component.newline())
        .append(
            Component.text(
                "Replacing saves your current backup first and takes effect after reconnecting.",
                NamedTextColor.YELLOW,
            ),
        )
        .append(Component.newline())
        .append(promptAction("[Replace & reconnect]", "accept", NamedTextColor.RED))
        .append(Component.space())
        .append(promptAction("[Dismiss]", "dismiss", NamedTextColor.GRAY))
        .append(Component.space())
        .append(promptAction("[Remind later]", "remind", NamedTextColor.AQUA))
        .build()
}

internal interface CommandRuntime {
    fun runStorage(operation: () -> Unit)
    fun runMain(operation: () -> Unit)
    fun findOnlinePlayer(name: String): OnlinePlayer?
    fun findKnownPlayer(name: String): OnlinePlayer?
    fun onlinePlayers(): Collection<OnlinePlayer>
    fun knownPlayers(): Collection<OnlinePlayer>
    fun notifyPlayer(playerId: UUID, message: Component)
    fun logFailure(playerId: UUID, error: Throwable)
}

internal data class OnlinePlayer(val id: UUID, val name: String)

private class PaperCommandRuntime(private val plugin: XaeroSyncPlugin) : CommandRuntime {
    override fun runStorage(operation: () -> Unit) = plugin.runStorage(operation)
    override fun runMain(operation: () -> Unit) {
        plugin.server.scheduler.runTask(plugin) { _ -> operation() }
    }
    override fun findOnlinePlayer(name: String): OnlinePlayer? =
        plugin.server.getPlayerExact(name)?.let { OnlinePlayer(it.uniqueId, it.name) }
    override fun findKnownPlayer(name: String): OnlinePlayer? = findOnlinePlayer(name)
        ?: plugin.server.getOfflinePlayer(name).takeIf { it.hasPlayedBefore() }?.let { offlinePlayer ->
            OnlinePlayer(offlinePlayer.uniqueId, offlinePlayer.name ?: name)
        }
    override fun onlinePlayers(): Collection<OnlinePlayer> =
        plugin.server.onlinePlayers.map { OnlinePlayer(it.uniqueId, it.name) }
    override fun knownPlayers(): Collection<OnlinePlayer> = onlinePlayers()
    override fun notifyPlayer(playerId: UUID, message: Component) {
        runMain { plugin.server.getPlayer(playerId)?.sendMessage(message) }
    }
    override fun logFailure(playerId: UUID, error: Throwable) {
        plugin.logger.log(Level.WARNING, "Xaero Sync command failed for $playerId.", error)
    }
}

internal object CommandText {
    private val displayTime: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
    fun relativeTime(instant: Instant, clock: Clock): String {
        val elapsed = Duration.between(instant, clock.instant()).coerceAtLeast(Duration.ZERO)
        return when {
            elapsed.seconds < 60 -> "just now"
            elapsed.toMinutes() < 60 -> ago(elapsed.toMinutes(), "minute")
            elapsed.toHours() < 24 -> ago(elapsed.toHours(), "hour")
            else -> ago(elapsed.toDays(), "day")
        }
    }
    fun displayTime(instant: Instant): String = displayTime.format(instant.atZone(ZoneId.systemDefault()))
    fun restoreCommand(id: String, target: String?): String =
        "/xaerosync restore $id --confirm${target?.let { " $it" } ?: ""}"
    fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
        else -> "${bytes / (1024 * 1024)} MiB"
    }
    private fun ago(amount: Long, unit: String): String = "$amount $unit${if (amount == 1L) "" else "s"} ago"
}
