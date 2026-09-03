package io.github.jackcuthbert.xaerosync.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

object ConnectionSyncProtocol {
    const val CHANNEL = "xaero-sync:configuration"
    const val PLAY_CHANNEL = "xaero-sync:play"
    const val VERSION = 1

    // JSON encodes chunk bytes as Base64. This keeps the complete plugin message below Paper's 32,766-byte limit.
    const val CHUNK_BYTES = 20 * 1_024
    const val MAX_FRAME_BYTES = 32_000
    const val MAX_CHUNKS = (SnapshotLimits.MAX_JSON_RECORD_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES

    fun decide(client: SnapshotMetadata, server: WaypointSnapshot?): SyncDecision = when {
        server == null -> SyncDecision.UploadRequired
        client.updatedAt > server.updatedAt -> SyncDecision.UploadRequired
        client.updatedAt == server.updatedAt && client.hash == server.hash -> SyncDecision.InSync(
            server.hash,
            server.updatedAt,
        )
        else -> SyncDecision.DownloadRequired(server)
    }
}

@Serializable
data class SnapshotMetadata(
    val hash: String,
    @Serializable(with = InstantAsStringSerializer::class)
    val updatedAt: Instant,
    val manifest: List<SnapshotManifestEntry>,
) {
    companion object {
        fun from(snapshot: WaypointSnapshot): SnapshotMetadata = SnapshotMetadata(
            snapshot.hash,
            snapshot.updatedAt,
            snapshot.manifest,
        )
    }
}

sealed interface SyncDecision {
    data object UploadRequired : SyncDecision

    data class DownloadRequired(val snapshot: WaypointSnapshot) : SyncDecision

    data class InSync(val hash: String, val updatedAt: Instant) : SyncDecision
}

@Serializable
sealed interface SyncMessage {
    @Serializable
    @SerialName("client_metadata")
    data class ClientMetadata(val metadata: SnapshotMetadata) : SyncMessage

    @Serializable
    @SerialName("upload_required")
    data object UploadRequired : SyncMessage

    @Serializable
    @SerialName("in_sync")
    data class InSync(
        val hash: String,
        @Serializable(with = InstantAsStringSerializer::class)
        val updatedAt: Instant,
    ) : SyncMessage

    @Serializable
    @SerialName("transfer_start")
    data class TransferStart(
        @Serializable(with = UuidAsStringSerializer::class)
        val transferId: UUID,
        val chunkCount: Int,
        val totalBytes: Int,
        val checksum: String,
    ) : SyncMessage

    @Serializable
    @SerialName("transfer_chunk")
    data class TransferChunk(
        @Serializable(with = UuidAsStringSerializer::class)
        val transferId: UUID,
        val index: Int,
        @Serializable(with = ByteArrayAsBase64Serializer::class)
        val bytes: ByteArray,
    ) : SyncMessage

    @Serializable
    @SerialName("transfer_accepted")
    data class TransferAccepted(
        val hash: String,
        @Serializable(with = InstantAsStringSerializer::class)
        val updatedAt: Instant,
    ) : SyncMessage

    @Serializable
    @SerialName("transfer_rejected")
    data class TransferRejected(val category: String) : SyncMessage
}

object SyncMessageCodec {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(message: SyncMessage): ByteArray = json.encodeToString(
        SyncEnvelope(ConnectionSyncProtocol.VERSION, message),
    )
        .toByteArray(Charsets.UTF_8)
        .also { require(it.size <= ConnectionSyncProtocol.MAX_FRAME_BYTES) { "Sync frame is too large." } }

    fun decode(bytes: ByteArray): SyncMessage = try {
        require(bytes.size <= ConnectionSyncProtocol.MAX_FRAME_BYTES) { "Sync frame is too large." }
        val envelope = json.decodeFromString<SyncEnvelope>(bytes.toString(Charsets.UTF_8))
        require(envelope.protocolVersion == ConnectionSyncProtocol.VERSION) { "Unsupported sync protocol." }
        envelope.message.also(::validate)
    } catch (exception: InvalidSyncMessageException) {
        throw exception
    } catch (exception: Exception) {
        throw InvalidSyncMessageException("Invalid sync message.", exception)
    }

    private fun validate(message: SyncMessage) {
        when (message) {
            is SyncMessage.ClientMetadata -> validate(message.metadata)
            SyncMessage.UploadRequired -> Unit
            is SyncMessage.InSync -> validateHash(message.hash)
            is SyncMessage.TransferStart -> {
                require(message.chunkCount in 1..ConnectionSyncProtocol.MAX_CHUNKS)
                require(message.totalBytes in 1..SnapshotLimits.MAX_JSON_RECORD_BYTES)
                validateHash(message.checksum)
            }
            is SyncMessage.TransferChunk -> {
                require(message.index >= 0)
                require(message.bytes.size <= ConnectionSyncProtocol.CHUNK_BYTES)
            }
            is SyncMessage.TransferAccepted -> validateHash(message.hash)
            is SyncMessage.TransferRejected -> require(message.category.matches(Regex("[a-z_]{1,64}")))
        }
    }

    private fun validate(metadata: SnapshotMetadata) {
        validateHash(metadata.hash)
        require(metadata.manifest.size <= SnapshotLimits.MAX_FILES)
        require(metadata.manifest.map { it.path }.distinct().size == metadata.manifest.size)
        require(metadata.manifest.sumOf { it.byteSize.toLong() } <= SnapshotLimits.MAX_TOTAL_BYTES)
        metadata.manifest.forEach { entry ->
            require(entry.path.toByteArray(Charsets.UTF_8).size <= SnapshotLimits.MAX_PATH_BYTES)
            require(WaypointFileSelector.isEligiblePath(entry.path))
            require(entry.byteSize in 0..SnapshotLimits.MAX_FILE_BYTES)
        }
    }

    private fun validateHash(hash: String) {
        require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 hash." }
    }
}

@Serializable
private data class SyncEnvelope(val protocolVersion: Int, val message: SyncMessage)

class InvalidSyncMessageException(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

data class SnapshotTransfer(val start: SyncMessage.TransferStart, val chunks: List<SyncMessage.TransferChunk>) {
    companion object {
        fun from(snapshot: WaypointSnapshot, transferId: UUID = UUID.randomUUID()): SnapshotTransfer {
            val contents = SnapshotJsonCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
            val chunks = contents.toList().chunked(ConnectionSyncProtocol.CHUNK_BYTES).mapIndexed { index, part ->
                SyncMessage.TransferChunk(transferId, index, part.toByteArray())
            }
            return SnapshotTransfer(
                SyncMessage.TransferStart(transferId, chunks.size, contents.size, sha256(contents)),
                chunks,
            )
        }
    }
}

class SnapshotTransferAssembler(start: SyncMessage.TransferStart) {
    private val expected = start
    private val output = ByteArrayOutputStream(start.totalBytes)
    private var nextIndex = 0

    val isComplete: Boolean
        get() = nextIndex == expected.chunkCount && output.size() == expected.totalBytes

    fun accept(chunk: SyncMessage.TransferChunk) {
        require(chunk.transferId == expected.transferId && chunk.index == nextIndex) { "Unexpected transfer chunk." }
        val bytes = chunk.bytes
        require(output.size() + bytes.size <= expected.totalBytes) { "Transfer exceeds declared size." }
        output.write(bytes)
        nextIndex++
    }

    fun finish(): WaypointSnapshot {
        require(isComplete) { "Transfer is incomplete." }
        val bytes = output.toByteArray()
        require(sha256(bytes) == expected.checksum) { "Transfer checksum mismatch." }
        return SnapshotJsonCodec.decode(bytes.toString(Charsets.UTF_8))
    }
}

private object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = try {
        Instant.parse(decoder.decodeString())
    } catch (exception: RuntimeException) {
        throw SerializationException("Invalid instant.", exception)
    }
}

private object UuidAsStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): UUID = try {
        UUID.fromString(decoder.decodeString())
    } catch (exception: IllegalArgumentException) {
        throw SerializationException("Invalid UUID.", exception)
    }
}

private object ByteArrayAsBase64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(Base64.getEncoder().encodeToString(value))

    override fun deserialize(decoder: Decoder): ByteArray = try {
        Base64.getDecoder().decode(decoder.decodeString())
    } catch (exception: IllegalArgumentException) {
        throw SerializationException("Invalid Base64.", exception)
    }
}

private fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
