package io.github.jackcuthbert.xaerosync.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant

data class ClientSyncState(val scope: String, val hash: String, val updatedAt: Instant)

/** Maintains the client timestamp sidecar without writing anything into Xaero's directory. */
class ClientSyncStateStore(private val statePath: Path) {
    fun observe(scope: String, xaeroRoot: Path, now: Instant): WaypointSnapshot {
        val observed = WaypointSnapshotFiles.read(xaeroRoot, now)
        val prior = load()
        if (prior?.scope == scope && prior.hash == observed.hash) {
            return WaypointSnapshot.create(observed.files, prior.updatedAt)
        }

        save(ClientSyncState(scope, observed.hash, observed.updatedAt))
        return observed
    }

    fun record(scope: String, snapshot: WaypointSnapshot) =
        save(ClientSyncState(scope, snapshot.hash, snapshot.updatedAt))

    fun load(): ClientSyncState? {
        if (Files.notExists(statePath)) {
            return null
        }
        return try {
            val record = JSON.decodeFromString<StateRecord>(Files.readString(statePath))
            require(record.version == FORMAT_VERSION)
            val state = ClientSyncState(record.scope, record.hash, Instant.parse(record.updatedAt))
            validate(state)
            state
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not read client sync state at $statePath.", exception)
        } catch (exception: SerializationException) {
            throw InvalidClientSyncStateException("Invalid client sync state.", exception)
        } catch (exception: IllegalArgumentException) {
            throw InvalidClientSyncStateException("Invalid client sync state.", exception)
        }
    }

    private fun save(state: ClientSyncState) {
        validate(state)
        val parent = requireNotNull(statePath.parent)
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            temporary = Files.createTempFile(parent, ".${statePath.fileName}.", ".tmp")
            Files.writeString(
                temporary,
                JSON.encodeToString(StateRecord(FORMAT_VERSION, state.scope, state.hash, state.updatedAt.toString())),
            )
            try {
                Files.move(temporary, statePath, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, statePath, REPLACE_EXISTING)
            }
        } catch (exception: IOException) {
            throw SnapshotStorageException("Could not save client sync state at $statePath.", exception)
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun validate(state: ClientSyncState) {
        require(state.scope.isNotBlank() && state.scope.toByteArray().size <= MAX_SCOPE_BYTES)
        require(state.hash.matches(Regex("[0-9a-f]{64}")))
    }

    @Serializable
    private data class StateRecord(val version: Int, val scope: String, val hash: String, val updatedAt: String)

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_SCOPE_BYTES = 1_024
        val JSON = Json { ignoreUnknownKeys = false }
    }
}

class InvalidClientSyncStateException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)
