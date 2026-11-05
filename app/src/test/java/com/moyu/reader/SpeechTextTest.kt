package com.moyu.reader

import com.moyu.reader.speech.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun sentencesKeepTheirTextAndOrder() {
        val sentences = SpeechText.sentences("第一句。第二句！第三句？")
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), sentences.map { it.text })
    }

    @Test fun sentenceOffsetsPointBackIntoTheParagraph() {
        val paragraph = "第一句。第二句。"
        val sentences = SpeechText.sentences(paragraph)
        sentences.forEach { sentence ->
            assertEquals(sentence.text, paragraph.substring(sentence.start, sentence.start + sentence.text.length))
        }
    }

    @Test fun closingMarksStayWithTheirSentence() {
        val sentences = SpeechText.sentences("他说：“走吧。”然后离开了。")
        assertEquals("他说：“走吧。”", sentences.first().text)
    }

    @Test fun decimalPointsDoNotEndASentence() {
        val sentences = SpeechText.sentences("版本 1.5 已经发布。")
        assertEquals(listOf("版本 1.5 已经发布。"), sentences.map { it.text })
    }

    @Test fun longParagraphIsCutAtSoftBreaks() {
        val paragraph = "甲".repeat(200) + "，" + "乙".repeat(200)
        val sentences = SpeechText.sentences(paragraph)
        assertTrue(sentences.size >= 2)
        sentences.forEach { sentence ->
            assertTrue("句子太长：${sentence.text.length}", sentence.text.length <= 220)
        }
        // The pieces must still line up with the original text.
        sentences.forEach { sentence ->
            assertEquals(sentence.text, paragraph.substring(sentence.start, sentence.start + sentence.text.length))
        }
    }

    @Test fun blankParagraphProducesNothing() {
        assertTrue(SpeechText.sentences("   ").isEmpty())
    }

    @Test fun normalizeKeepsTheLengthSoOffsetsSurvive() {
        val raw = "第一句。\r\n第二句。\t第三句。　第四句。"
        val cleaned = SpeechText.normalize(raw)
        // Every replacement is one character for one character, so a paragraph offset still
        // points at the same character after cleaning.
        assertEquals(raw.length, cleaned.length)
        assertEquals(raw.replace('\r', '\n').replace('\t', ' ').replace('　', ' '), cleaned)
    }
}
