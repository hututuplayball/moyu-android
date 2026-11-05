package com.moyu.reader.speech

/** One piece of text a speech engine reads, with the book offset it starts at. */
data class SpeechLine(val text: String, val offset: Int)

/**
 * Cuts book text into the pieces a speech engine can read one by one. Speaking a whole chapter in
 * one go loses the pauses that make it listenable, so every sentence is spoken on its own.
 */
object SpeechText {
    /** Paragraphs longer than this are cut at a soft break. */
    private const val MAX_SENTENCE = 220

    /** A sentence and where it starts inside the paragraph it came from. */
    data class Sentence(val text: String, val start: Int)

    /** Splits one paragraph into sentences, keeping the punctuation that ends them. */
    fun sentences(paragraph: String): List<Sentence> {
        val cleaned = normalize(paragraph)
        if (cleaned.isEmpty()) return emptyList()
        val result = ArrayList<Sentence>()
        var index = 0
        var start = 0
        while (index < cleaned.length) {
            val char = cleaned[index]
            index++
            val ends = when (char) {
                '。', '！', '？', '；', '…' -> true
                '.' -> {
                    val next = cleaned.getOrNull(index)
                    val previous = cleaned.getOrNull(index - 2)
                    previous?.isDigit() != true && (next == null || next.isWhitespace())
                }
                '\n' -> true
                else -> false
            }
            if (!ends) continue
            while (index < cleaned.length && cleaned[index] in CLOSING_MARKS) {
                index++
            }
            emit(result, cleaned, start, index)
            start = index
        }
        emit(result, cleaned, start, cleaned.length)
        return result
    }

    /** Trims the text a voice can actually say, and drops what it would only stumble over. */
    fun normalize(text: String): String = text
        .replace('\r', '\n')
        .replace(NON_BREAKING_SPACE, ' ')
        .replace(FULL_WIDTH_SPACE, ' ')
        .replace('\t', ' ')

    /**
     * Adds one sentence, cutting it at a soft break when it is too long. The offsets stay inside
     * the paragraph: they are what lets the reader show where the reading has reached.
     */
    private fun emit(target: MutableList<Sentence>, source: String, from: Int, to: Int) {
        var begin = from
        var end = to
        while (begin < end && source[begin].isWhitespace()) begin++
        while (end > begin && source[end - 1].isWhitespace()) end--
        if (begin >= end) return
        if (end - begin <= MAX_SENTENCE) {
            target += Sentence(source.substring(begin, end), begin)
            return
        }
        val head = source.substring(begin, end)
        var cut = head.lastIndexOfAny(SOFT_BREAKS, head.length - 1)
        if (cut <= 0) cut = MAX_SENTENCE
        target += Sentence(head.substring(0, cut).trim(), begin)
        emit(target, source, begin + cut, end)
    }

    private const val CLOSING_MARKS = "”’」』）)】》"
    private val SOFT_BREAKS = charArrayOf('，', ',', '、', '：', ':')
    private const val NON_BREAKING_SPACE = ' '
    private const val FULL_WIDTH_SPACE = '　'
}
