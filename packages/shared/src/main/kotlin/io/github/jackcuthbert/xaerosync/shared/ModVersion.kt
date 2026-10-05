package io.github.jackcuthbert.xaerosync.shared

import java.math.BigInteger

object ModVersion {
    fun compare(left: String, right: String): Int? {
        val a = parse(left) ?: return null
        val b = parse(right) ?: return null
        for (index in 0..2) {
            val result = a.core[index].compareTo(b.core[index])
            if (result != 0) return result
        }
        if (a.pre.isEmpty() || b.pre.isEmpty()) {
            return when {
                a.pre.isEmpty() && b.pre.isEmpty() -> 0
                a.pre.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until maxOf(a.pre.size, b.pre.size)) {
            if (index == a.pre.size) return -1
            if (index == b.pre.size) return 1
            val x = a.pre[index]
            val y = b.pre[index]
            val result = if (x.all { it.isAsciiDigit() } && y.all { it.isAsciiDigit() }) {
                x.toBigInteger().compareTo(y.toBigInteger())
            } else if (x.all { it.isAsciiDigit() }) {
                -1
            } else if (y.all { it.isAsciiDigit() }) {
                1
            } else {
                x.compareTo(y)
            }
            if (result != 0) return result
        }
        return 0
    }

    private data class Parsed(val core: List<BigInteger>, val pre: List<String>)

    private fun parse(value: String): Parsed? {
        if (value.length !in 1..128) return null
        if (value.count { it == '+' } > 1) return null
        val build = value.substringAfter('+', "")
        if ('+' in value &&
            (
                build.isEmpty() ||
                    build.split('.').any { it.isEmpty() || it.any { c -> !c.isAsciiLetterOrDigit() && c != '-' } }
                )
        ) {
            return null
        }
        val normalized = value.substringBefore('+')
        val coreAndPre = normalized.split('-', limit = 2)
        val core = coreAndPre[0].split('.')
        if (core.size != 3 ||
            core.any { it.isEmpty() || it.any { c -> !c.isAsciiDigit() } || it.length > 1 && it[0] == '0' }
        ) {
            return null
        }
        val pre = coreAndPre.getOrNull(1)?.split('.') ?: emptyList()
        if (pre.any {
                it.isEmpty() ||
                    it.any { c -> !c.isAsciiLetterOrDigit() && c != '-' } ||
                    it.all { c -> c.isAsciiDigit() } &&
                    it.length > 1 &&
                    it[0] == '0'
            }
        ) {
            return null
        }
        return runCatching { Parsed(core.map(String::toBigInteger), pre) }.getOrNull()
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiDigit() || this in 'a'..'z' || this in 'A'..'Z'
}
