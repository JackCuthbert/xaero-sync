package io.github.jackcuthbert.xaerosync.paper

import io.github.jackcuthbert.xaerosync.shared.ConfigurationProbe

internal object ConfigurationProbeResponder {
    fun respond(channel: String, isConfiguration: Boolean, message: ByteArray): ProbeResult {
        if (channel != ConfigurationProbe.CHANNEL || !isConfiguration) {
            return ProbeResult.Ignored
        }

        val version = message.decodeCanonicalVarInt()
        if (version == null || !ConfigurationProbe.accepts(version)) {
            return ProbeResult.Rejected
        }

        return ProbeResult.Response(ConfigurationProbe.PROTOCOL_VERSION.encodeVarInt())
    }

    private fun ByteArray.decodeCanonicalVarInt(): Int? {
        if (isEmpty() || size > MAX_VAR_INT_BYTES) {
            return null
        }

        var result = 0
        forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xFF
            if (index == MAX_VAR_INT_BYTES - 1 && unsigned and 0xF0 != 0) {
                return null
            }
            result = result or ((unsigned and 0x7F) shl (index * 7))
            if (unsigned and 0x80 == 0) {
                if (index != lastIndex || index > 0 && unsigned == 0) {
                    return null
                }
                return result
            }
        }
        return null
    }

    private fun Int.encodeVarInt(): ByteArray {
        var remaining = this
        val encoded = ArrayList<Byte>(MAX_VAR_INT_BYTES)
        do {
            var next = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) {
                next = next or 0x80
            }
            encoded += next.toByte()
        } while (remaining != 0)
        return encoded.toByteArray()
    }

    private const val MAX_VAR_INT_BYTES = 5
}

internal sealed interface ProbeResult {
    data object Ignored : ProbeResult

    data object Rejected : ProbeResult

    class Response(payload: ByteArray) : ProbeResult {
        private val rawPayload = payload.copyOf()
        val payload: ByteArray get() = rawPayload.copyOf()
    }
}
