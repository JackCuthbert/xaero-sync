package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConnectionSyncProtocol
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

internal class ConfigurationSyncPayload(bytes: ByteArray) : CustomPacketPayload {
    private val rawBytes = bytes.copyOf()
    val bytes: ByteArray get() = rawBytes.copyOf()

    override fun type(): CustomPacketPayload.Type<ConfigurationSyncPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigurationSyncPayload>(Identifier.parse(ConnectionSyncProtocol.CHANNEL))
        val CODEC: StreamCodec<ByteBuf, ConfigurationSyncPayload> =
            object : StreamCodec<ByteBuf, ConfigurationSyncPayload> {
                override fun decode(buffer: ByteBuf): ConfigurationSyncPayload {
                    require(buffer.readableBytes() <= ConnectionSyncProtocol.MAX_FRAME_BYTES)
                    return ConfigurationSyncPayload(ByteArray(buffer.readableBytes()).also(buffer::readBytes))
                }

                override fun encode(buffer: ByteBuf, payload: ConfigurationSyncPayload) {
                    buffer.writeBytes(payload.rawBytes)
                }
            }
    }
}
