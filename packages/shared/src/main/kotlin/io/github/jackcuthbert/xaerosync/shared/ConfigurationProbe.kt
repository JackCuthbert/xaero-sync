package io.github.jackcuthbert.xaerosync.shared

/** The minimal configuration-phase exchange used to validate the target platforms. */
object ConfigurationProbe {
    const val CHANNEL = "xaero-sync:configuration-probe"
    const val PROTOCOL_VERSION = 1

    fun accepts(version: Int): Boolean = version == PROTOCOL_VERSION
}
