package com.moyu.reader.book

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded reads shared by the text parsers: an oversized file fails before it exhausts the heap. */
object Streams {
    fun readCapped(input: InputStream, limit: Long, subject: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            total += count
            if (total > limit) throw TextBookException("${subject}过大，暂时无法导入。")
            buffer.write(chunk, 0, count)
        }
        return buffer.toByteArray()
    }
}
