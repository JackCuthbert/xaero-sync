package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ModVersionReport
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

internal class VersionReportPayload(bytes: ByteArray) : CustomPacketPayload {
    init {
        require(bytes.size in 1..ModVersionReport.MAX_BYTES)
    }

    private val rawBytes = bytes.copyOf()

    override fun type(): CustomPacketPayload.Type<VersionReportPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<VersionReportPayload>(Identifier.parse(ModVersionReport.CHANNEL))
        val CODEC: StreamCodec<ByteBuf, VersionReportPayload> = object : StreamCodec<ByteBuf, VersionReportPayload> {
            override fun decode(buffer: ByteBuf) =
                VersionReportPayload(ByteArray(buffer.readableBytes()).also(buffer::readBytes))
            override fun encode(buffer: ByteBuf, payload: VersionReportPayload) {
                buffer.writeBytes(payload.rawBytes)
            }
        }
    }
}
