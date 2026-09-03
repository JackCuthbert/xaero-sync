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
    private val clock: Clock = Clock.systemUTC(),
) : CommandExecutor,
    TabCompleter {
    constructor(plugin: XaeroSyncPlugin, repository: PlayerSnapshotRepository) :
        this(PaperCommandRuntime(plugin), repository)

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
        if (subcommand !in SUBCOMMANDS) {
            reply(sender, error("Unknown command. Use /$label help."))
            return
        }
        if (subcommand == "diagnostics" && !sender.hasPermission(ADMIN_PERMISSION)) {
            reply(sender, error("You need administrator permission to view diagnostics."))
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
        runtime.runStorage {
            runCatching {
                when (subcommand) {
                    "status" -> status(sender, target)
                    "backup" -> backup(sender, target)
                    "backups", "snapshots" -> backups(sender, target, requestedPage(args))
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
                addAll(listOf("status", "backup", "backups", "restore", "help"))
                if (sender.hasPermission(ADMIN_PERMISSION)) add("diagnostics")
            }
            2 -> when (args[0].lowercase()) {
                "restore" -> sender.playerId()?.let { knownSnapshotIds[it] }.orEmpty()
                "backups", "snapshots" -> listOf("1") + adminTargets(sender)
                "status", "backup", "diagnostics" -> adminTargets(sender)
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "restore" -> listOf(CONFIRM_FLAG) + adminTargets(sender)
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
        remember(target.id)
        reply(sender, success("Waypoints restored. Reconnect to load them into Xaero."))
        if (sender.playerId() != target.id) {
            runtime.notifyPlayer(target.id, success("Your waypoints were restored. Reconnect to load them into Xaero."))
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

    private fun action(label: String, command: String) = Component.text(label, NamedTextColor.AQUA)
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

    private fun targetArgument(subcommand: String, args: Array<out String>): String? = when (subcommand) {
        "backups", "snapshots" -> if (args.getOrNull(1)?.toIntOrNull() != null) args.getOrNull(2) else args.getOrNull(1)
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
        "Snapshot does not exist." -> "That restore point no longer exists. Use /xaerosync backups to refresh the list."
        "Invalid snapshot identifier." -> "That restore point is invalid. Use /xaerosync backups to choose one."
        else -> "The operation failed. Check the server log for details."
    }

    private fun CommandSender.playerId(): UUID? = (this as? Player)?.uniqueId
    private fun String?.suffix(): String = this?.let { " $it" } ?: ""
    private fun isConfirmation(argument: String?): Boolean = argument == CONFIRM_FLAG || argument == "confirm"
    private data class TargetPlayer(val id: UUID, val name: String, val argument: String?)

    private companion object {
        const val COMMAND_PERMISSION = "xaerosync.command"
        const val ADMIN_PERMISSION = "xaerosync.admin"
        const val CONFIRM_FLAG = "--confirm"
        const val PAGE_SIZE = 5
        val SUBCOMMANDS = setOf("status", "backup", "backups", "snapshots", "restore", "diagnostics")
        fun title(message: String): Component = Component.text(message, NamedTextColor.GOLD)
        fun success(message: String): Component = Component.text("✓ $message", NamedTextColor.GREEN)
        fun warning(message: String): Component = Component.text(message, NamedTextColor.YELLOW)
        fun muted(message: String): Component = Component.text(message, NamedTextColor.GRAY)
        fun error(message: String): Component = Component.text("Xaero Sync: $message", NamedTextColor.RED)
    }
}

internal interface CommandRuntime {
    fun runStorage(operation: () -> Unit)
    fun runMain(operation: () -> Unit)
    fun findOnlinePlayer(name: String): OnlinePlayer?
    fun onlinePlayers(): Collection<OnlinePlayer>
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
    override fun onlinePlayers(): Collection<OnlinePlayer> =
        plugin.server.onlinePlayers.map { OnlinePlayer(it.uniqueId, it.name) }
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
