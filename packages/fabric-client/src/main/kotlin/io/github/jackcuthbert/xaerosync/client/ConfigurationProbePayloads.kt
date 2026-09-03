package io.github.jackcuthbert.xaerosync.client

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

internal data class ConfigurationProbeRequest(val protocolVersion: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ConfigurationProbeRequest> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigurationProbeRequest>(Identifier.parse(ConfigurationProbe.CHANNEL))
        val CODEC: StreamCodec<ByteBuf, ConfigurationProbeRequest> =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                ConfigurationProbeRequest::protocolVersion,
                ::ConfigurationProbeRequest,
            )
    }
}

internal data class ConfigurationProbeResponse(val protocolVersion: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ConfigurationProbeResponse> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ConfigurationProbeResponse>(Identifier.parse(ConfigurationProbe.CHANNEL))
        val CODEC: StreamCodec<ByteBuf, ConfigurationProbeResponse> =
            StreamCodec.composite(
                ByteBufCodecs.VAR_INT,
                ConfigurationProbeResponse::protocolVersion,
                ::ConfigurationProbeResponse,
            )
    }
}
