package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.PlayerSnapshotRepository
import io.github.jackcuthbert.xaerosync.shared.WaypointFile
import io.github.jackcuthbert.xaerosync.shared.WaypointSnapshot
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XaeroSyncCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val playerId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val now = Instant.parse("2026-09-04T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `root command gives players readable status without diagnostics`() {
        val fixture = fixture()
        fixture.repository.save(playerId, snapshot("Home", now.minusSeconds(120)))

        fixture.command.execute(fixture.player.sender, "xaerosync", emptyArray())

        val output = fixture.player.text()
        assertContains(output, "Waypoints backed up 2 minutes ago")
        assertContains(output, "1 waypoint files")
        assertFalse(output.contains(playerId.toString()))
        assertFalse(output.contains("Hash:"))
    }

    @Test
    fun `restore requires confirmation and only tells the restoring player once`() {
        val fixture = fixture()
        val original = snapshot("Original", now.minusSeconds(30))
        fixture.repository.save(playerId, original)
        val restorePoint = fixture.repository.backup(playerId)
        val current = snapshot("Current", now)
        fixture.repository.save(playerId, current)

        fixture.command.execute(fixture.player.sender, "xaerosync", arrayOf("restore", restorePoint.id))

        assertEquals(current.hash, fixture.repository.load(playerId)?.hash)
        assertContains(fixture.player.text(), "current server backup will be saved first")
        assertTrue(fixture.player.clickCommands().contains("/xaerosync restore ${restorePoint.id} --confirm"))

        fixture.player.messages.clear()
        fixture.command.execute(
            fixture.player.sender,
            "xaerosync",
            arrayOf("restore", restorePoint.id, "--confirm"),
        )

        assertEquals(original.hash, fixture.repository.load(playerId)?.hash)
        assertEquals(1, fixture.player.messages.count { it.plainText().contains("Waypoints restored") })
        assertTrue(fixture.runtime.notifications.isEmpty())
    }

    @Test
    fun `backups are paginated and completion offers commands and restore points`() {
        val fixture = fixture()
        fixture.repository.save(playerId, snapshot("Home", now))
        repeat(7) { fixture.repository.backup(playerId) }

        fixture.command.execute(fixture.player.sender, "xaerosync", arrayOf("backups", "2"))

        assertContains(fixture.player.text(), "page 2 of 2")
        assertEquals(2, fixture.player.messages.count { it.plainText().contains("files •") })
        assertEquals(listOf("backup", "backups"), fixture.command.complete(fixture.player.sender, arrayOf("back")))
        val restoreIds = fixture.command.complete(fixture.player.sender, arrayOf("restore", ""))
        assertEquals(7, restoreIds.size)
        assertEquals(
            listOf("--confirm"),
            fixture.command.complete(fixture.player.sender, arrayOf("restore", restoreIds.first(), "--")),
        )
    }

    @Test
    fun `permission denial is explicit`() {
        val fixture = fixture(commandPermission = false)

        fixture.command.execute(fixture.player.sender, "xaerosync", emptyArray())

        assertContains(fixture.player.text(), "don't have permission")
        assertTrue(fixture.command.complete(fixture.player.sender, arrayOf("")).isEmpty())
    }

    @Test
    fun `technical diagnostics require administrator permission`() {
        val fixture = fixture()

        fixture.command.execute(fixture.player.sender, "xaerosync", arrayOf("diagnostics"))

        assertContains(fixture.player.text(), "administrator permission")
    }

    @Test
    fun `console diagnostics contain exact operational details`() {
        val fixture = fixture()
        val snapshot = snapshot("Home", now.minusSeconds(42))
        fixture.repository.save(playerId, snapshot)

        fixture.command.execute(
            fixture.console.sender,
            "xaerosync",
            arrayOf("diagnostics", playerId.toString()),
        )

        val output = fixture.console.text()
        assertContains(output, playerId.toString())
        assertContains(output, snapshot.hash.take(12))
        assertContains(output, snapshot.updatedAt.toString())
        assertContains(output, "Canonical: 1 files")
    }

    @Test
    fun `console can restore non-interactively with the confirm flag`() {
        val fixture = fixture()
        val original = snapshot("Original", now.minusSeconds(60))
        fixture.repository.save(playerId, original)
        val restorePoint = fixture.repository.backup(playerId)
        fixture.repository.save(playerId, snapshot("Current", now))

        fixture.command.execute(
            fixture.console.sender,
            "xaerosync",
            arrayOf("restore", restorePoint.id, "--confirm", playerId.toString()),
        )

        assertEquals(original.hash, fixture.repository.load(playerId)?.hash)
        assertContains(fixture.console.text(), "Waypoints restored")
        assertEquals(playerId, fixture.runtime.notifications.single().first)
    }

    private fun fixture(commandPermission: Boolean = true): Fixture {
        val repository = PlayerSnapshotRepository(temporaryDirectory, clock)
        val player = SenderProbe.player(playerId, "TestPlayer", commandPermission, adminPermission = false)
        val console = SenderProbe.console(commandPermission = true, adminPermission = true)
        val runtime = FakeRuntime(OnlinePlayer(playerId, "TestPlayer"))
        return Fixture(repository, player, console, runtime, XaeroSyncCommand(runtime, repository, clock))
    }

    private fun snapshot(name: String, updatedAt: Instant) = WaypointSnapshot.create(
        listOf(
            WaypointFile(
                "dim%0/mw0.txt",
                "#\nwaypoint:$name:H:1:2:3:1:false:0:set:false:0:0:false".toByteArray(),
            ),
        ),
        updatedAt,
    )

    private data class Fixture(
        val repository: PlayerSnapshotRepository,
        val player: SenderProbe,
        val console: SenderProbe,
        val runtime: FakeRuntime,
        val command: XaeroSyncCommand,
    )
}

private class FakeRuntime(vararg players: OnlinePlayer) : CommandRuntime {
    private val players = players.associateBy { it.name }
    val notifications = mutableListOf<Pair<UUID, Component>>()
    override fun runStorage(operation: () -> Unit) = operation()
    override fun runMain(operation: () -> Unit) = operation()
    override fun findOnlinePlayer(name: String): OnlinePlayer? = players[name]
    override fun onlinePlayers(): Collection<OnlinePlayer> = players.values
    override fun notifyPlayer(playerId: UUID, message: Component) {
        notifications += playerId to message
    }
    override fun logFailure(playerId: UUID, error: Throwable) = Unit
}

private class SenderProbe private constructor(val sender: CommandSender, val messages: MutableList<Component>) {
    fun text(): String = messages.joinToString("\n") { it.plainText() }
    fun clickCommands(): List<String> = messages.flatMap { it.clickCommands() }

    companion object {
        fun player(id: UUID, name: String, commandPermission: Boolean, adminPermission: Boolean): SenderProbe =
            create(Player::class.java, id, name, commandPermission, adminPermission)
        fun console(commandPermission: Boolean, adminPermission: Boolean): SenderProbe =
            create(CommandSender::class.java, null, "CONSOLE", commandPermission, adminPermission)

        private fun create(
            type: Class<out CommandSender>,
            id: UUID?,
            name: String,
            commandPermission: Boolean,
            adminPermission: Boolean,
        ): SenderProbe {
            val messages = mutableListOf<Component>()
            val sender = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                when (method.name) {
                    "getUniqueId" -> id
                    "getName" -> name
                    "hasPermission" -> when (arguments?.firstOrNull()) {
                        "xaerosync.command" -> commandPermission
                        "xaerosync.admin" -> adminPermission
                        else -> false
                    }
                    "sendMessage" -> {
                        arguments?.filterIsInstance<Component>()?.let(messages::addAll)
                        Unit
                    }
                    "equals" -> proxy === arguments?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> name
                    else -> primitiveDefault(method.returnType)
                }
            } as CommandSender
            return SenderProbe(sender, messages)
        }

        private fun primitiveDefault(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
}

private fun Component.plainText(): String =
    ((this as? TextComponent)?.content().orEmpty()) + children().joinToString("") { it.plainText() }

private fun Component.clickCommands(): List<String> = buildList {
    (clickEvent()?.payload() as? ClickEvent.Payload.Text)?.value()?.let(::add)
    children().forEach { addAll(it.clickCommands()) }
}
