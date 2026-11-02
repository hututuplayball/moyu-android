package com.moyu.reader.book

import java.io.InputStream

/**
 * Tells an EPUB apart from a comic archive. Both are zips, so the decision comes from the package
 * marker: a `META-INF/container.xml` entry or an `application/epub+zip` mimetype entry up front.
 */
object EpubSniffer {
    private const val MAX_ENTRIES = 64
    private const val HEAD_BYTES = 20
    private const val CONTAINER_ENTRY = "META-INF/container.xml"
    private const val MIMETYPE_ENTRY = "mimetype"
    private const val EPUB_MIME = "application/epub+zip"

    /**
     * Walks the first [MAX_ENTRIES] entries without reading the whole archive. [next] yields the
     * next entry name (null at the end), [head] the leading bytes of the entry [next] returned.
     */
    fun looksLikeEpub(next: () -> String?, head: () -> ByteArray?): Boolean {
        var scanned = 0
        while (scanned < MAX_ENTRIES) {
            val name = next() ?: return false
            scanned++
            if (name.equals(CONTAINER_ENTRY, true)) return true
            if (name.equals(MIMETYPE_ENTRY, true)) {
                val mime = head()?.toString(Charsets.US_ASCII)?.trim()
                if (mime.equals(EPUB_MIME, true)) return true
            }
        }
        return false
    }

    /** Reads at most [HEAD_BYTES] bytes of the current entry, tolerating very short entries. */
    fun head(input: InputStream): ByteArray {
        val buffer = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count <= 0) break
            read += count
        }
        return if (read == buffer.size) buffer else buffer.copyOf(read)
    }
}
