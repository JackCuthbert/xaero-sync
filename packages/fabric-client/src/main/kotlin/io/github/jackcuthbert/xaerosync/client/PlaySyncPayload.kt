package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

internal class PlaySyncPayload(bytes: ByteArray) : CustomPacketPayload {
    private val rawBytes = bytes.copyOf()
    val bytes: ByteArray get() = rawBytes.copyOf()

    override fun type(): CustomPacketPayload.Type<PlaySyncPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PlaySyncPayload>(Identifier.parse(ConnectionSyncProtocol.PLAY_CHANNEL))
        val CODEC: StreamCodec<ByteBuf, PlaySyncPayload> = object : StreamCodec<ByteBuf, PlaySyncPayload> {
            override fun decode(buffer: ByteBuf) =
                PlaySyncPayload(ByteArray(buffer.readableBytes()).also(buffer::readBytes))

            override fun encode(buffer: ByteBuf, payload: PlaySyncPayload) {
                buffer.writeBytes(payload.rawBytes)
            }
        }
    }
}
