package com.fahadali.oneos.core.model

import java.util.Locale

/** Parses `getprop` output: one `[key]: [value]` per line. */
object Getprop {
    private val LINE = Regex("""^\[([^\]]+)]: \[(.*)]$""")

    fun parse(dump: String): Map<String, String> =
        dump.lineSequence()
            .mapNotNull { LINE.matchEntire(it.trimEnd()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
}

/** Plain-text helpers for texts that start with a name ("the other system" at the start of a sentence). */
object Sentence {
    fun start(s: String): String = s.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

object Units {
    /** Decimal units like Android's storage screen: "407 GB", "21.3 GB", "512 MB". */
    fun bytes(b: Long): String {
        val v = b.toDouble()
        return when {
            b < 0 -> "?"
            v >= 100e9 -> String.format(Locale.ROOT, "%.0f GB", v / 1e9)
            v >= 1e9 -> String.format(Locale.ROOT, "%.1f GB", v / 1e9)
            v >= 1e6 -> String.format(Locale.ROOT, "%.0f MB", v / 1e6)
            v >= 1e3 -> String.format(Locale.ROOT, "%.0f kB", v / 1e3)
            else -> "$b B"
        }
    }
}

object Hashes {
    fun sha256(bytes: ByteArray): String = hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(file: java.io.File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
