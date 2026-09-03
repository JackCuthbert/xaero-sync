package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

internal class XaeroSyncCommand(
    private val plugin: XaeroSyncPlugin,
    private val repository: PlayerSnapshotRepository,
) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("xaerosync.command")) return true
        if (args.isEmpty()) {
            reply(sender, usage())
            return true
        }

        val targetArgument = when (args[0].lowercase()) {
            "restore" -> args.getOrNull(3)
            else -> args.getOrNull(1)
        }
        val playerId = resolvePlayer(sender, targetArgument) ?: return true
        plugin.runStorage {
            runCatching {
                when (args[0].lowercase()) {
                    "status" -> status(sender, playerId)
                    "backup" -> backup(sender, playerId)
                    "snapshots" -> snapshots(sender, playerId)
                    "restore" -> restore(sender, playerId, args)
                    else -> reply(sender, usage())
                }
            }.onFailure { reply(sender, "Xaero Sync: ${it.message ?: "operation failed"}") }
        }
        return true
    }

    private fun status(sender: CommandSender, playerId: UUID) {
        val status = repository.status(playerId)
        val canonical = status.canonicalHash?.take(12) ?: "none"
        reply(
            sender,
            "Xaero Sync: $playerId canonical=$canonical updated=${status.canonicalUpdatedAt ?: "never"}; " +
                "snapshots=${status.snapshotCount}, bytes=${status.snapshotBytes}",
        )
    }

    private fun backup(sender: CommandSender, playerId: UUID) {
        val snapshot = repository.backup(playerId)
        reply(sender, "Xaero Sync: created ${snapshot.id} (${snapshot.hash.take(12)}).")
    }

    private fun snapshots(sender: CommandSender, playerId: UUID) {
        val snapshots = repository.listSnapshots(playerId)
        if (snapshots.isEmpty()) {
            reply(sender, "Xaero Sync: no snapshots for $playerId.")
        } else {
            reply(sender, "Xaero Sync snapshots for $playerId:")
            snapshots.forEach { reply(sender, "${it.id} ${it.updatedAt} ${it.hash.take(12)} ${it.bytes} bytes") }
        }
    }

    private fun restore(sender: CommandSender, playerId: UUID, args: Array<out String>) {
        val snapshotId = args.getOrNull(1)
        if (snapshotId == null || args.getOrNull(2) != "confirm") {
            reply(sender, "Usage: /xaerosync restore <snapshot> confirm [uuid]")
            return
        }
        val restored = repository.restore(playerId, snapshotId)
        reply(sender, "Xaero Sync: restored ${restored.hash.take(12)}. Reconnect to apply it.")
        plugin.server.scheduler.runTask(plugin) { _ ->
            plugin.server.getPlayer(playerId)?.sendMessage(
                "Xaero Sync: your waypoints were restored; reconnect to apply them.",
            )
        }
    }

    private fun resolvePlayer(sender: CommandSender, argument: String?): UUID? {
        if (argument == null && sender is Player) return sender.uniqueId
        if (!sender.hasPermission("xaerosync.admin")) {
            reply(sender, "Xaero Sync: xaerosync.admin is required to target another UUID.")
            return null
        }
        return runCatching { UUID.fromString(requireNotNull(argument)) }.getOrElse {
            reply(sender, "Xaero Sync: provide a valid player UUID.")
            null
        }
    }

    private fun reply(sender: CommandSender, message: String) {
        plugin.server.scheduler.runTask(plugin) { _ -> sender.sendMessage(message) }
    }

    private fun usage() = "Usage: /xaerosync <status|backup|snapshots> [uuid] or " +
        "/xaerosync restore <snapshot> confirm [uuid]"
}
