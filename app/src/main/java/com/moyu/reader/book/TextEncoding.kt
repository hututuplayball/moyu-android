package com.moyu.reader.book

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Decides what a byte blob says: a BOM first, then strict UTF-8, then GBK as the Chinese fallback.
 * TXT novels are the one format where the bytes carry no declaration of their own encoding.
 */
object TextEncoding {
    private val GBK: Charset = Charset.forName("GBK")
    private const val MIN_PROBE_BYTES = 8
    private const val READABLE_RATIO = 0.85
    /** Longest character any supported encoding needs, so a probe is at most this far off a boundary. */
    private const val MAX_CHAR_BYTES = 4

    fun decode(bytes: ByteArray): String {
        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
            bytes.startsWith(0xFF, 0xFE) -> return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
            bytes.startsWith(0xFE, 0xFF) -> return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return decodeOrNull(bytes, StandardCharsets.UTF_8, CodingErrorAction.REPORT)
            ?: decodeOrNull(bytes, GBK, CodingErrorAction.REPLACE)
            ?: String(bytes, StandardCharsets.UTF_8)
    }

    /** A NUL-free prefix that decodes mostly as CJK/ASCII text is treated as a TXT novel. */
    fun looksLikeText(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val probeLength = length.coerceIn(0, bytes.size)
        if (probeLength < MIN_PROBE_BYTES) return false
        for (index in 0 until probeLength) if (bytes[index] == 0.toByte()) return false
        val text = decodeProbe(bytes, probeLength) ?: return false
        if (text.isBlank()) return false
        return readableChars(text) >= text.length * READABLE_RATIO
    }

    /**
     * Import sniffs only a prefix of the file, so its last character may be cut in half — a GBK
     * novel would then look like garbage. UTF-8 is tried first, and both encodings get a retry
     * with up to [MAX_CHAR_BYTES] trailing bytes dropped before the file is called unsupported.
     */
    private fun decodeProbe(bytes: ByteArray, length: Int): String? {
        for (charset in arrayOf(StandardCharsets.UTF_8, GBK)) {
            for (trim in 0 until MAX_CHAR_BYTES) {
                val end = length - trim
                if (end <= 0) return null
                val probe = if (end == bytes.size) bytes else bytes.copyOf(end)
                decodeOrNull(probe, charset, CodingErrorAction.REPORT)?.let { return it }
            }
        }
        return null
    }

    private fun readableChars(text: String): Int = text.count { char ->
        val value = char.code
        value in 0x20..0x7e || value == 0x09 || value == 0x0a || value == 0x0d ||
            value in 0x2000..0x206f || value in 0x3000..0x303f ||
            value in 0x3400..0x4dbf || value in 0x4e00..0x9fff || value in 0xff00..0xffef
    }

    private fun decodeOrNull(bytes: ByteArray, charset: Charset, action: CodingErrorAction): String? = try {
        charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
