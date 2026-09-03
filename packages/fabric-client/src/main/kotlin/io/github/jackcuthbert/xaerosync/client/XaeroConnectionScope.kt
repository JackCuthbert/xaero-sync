package io.github.jackcuthbert.xaerosync.client

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

internal data class XaeroConnectionScope(val address: String, val waypointRoot: Path, val sidecarPath: Path) {
    companion object {
        fun from(gameDirectory: Path, address: String): XaeroConnectionScope {
            val normalizedAddress = address.trim().lowercase()
            require(normalizedAddress.isNotEmpty())
            val host = when {
                normalizedAddress.startsWith('[') -> normalizedAddress.substringAfter('[').substringBefore(']')
                else -> normalizedAddress.substringBefore(':')
            }
            require(host.isNotEmpty())
            val xaeroName = host.map { character ->
                if (character.isLetterOrDigit() || character in "._-") character else '_'
            }.joinToString("")
            val scopeHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(normalizedAddress.toByteArray()),
            )
            return XaeroConnectionScope(
                normalizedAddress,
                gameDirectory.resolve("xaero/minimap/Multiplayer_$xaeroName"),
                gameDirectory.resolve(".xaero-sync/connections/$scopeHash.json"),
            )
        }
    }
}
