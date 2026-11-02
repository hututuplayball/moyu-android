package com.moyu.reader.book

/** One WeChat-Reader-like paper: background, ink and a dimmer tone for footers and page numbers. */
data class TextTheme(val id: String, val label: String, val bg: Long, val text: Long, val dim: Long)

object TextThemes {
    val all = listOf(
        TextTheme("white", "纯白", 0xFFFFFFFF, 0xFF262626, 0xFFB3B3B3),
        TextTheme("paper", "米黄", 0xFFF5EBD8, 0xFF403626, 0xFFB4A47C),
        TextTheme("green", "护眼", 0xFFCDE6D0, 0xFF2B3A2E, 0xFF7F9C84),
        TextTheme("night", "夜蓝", 0xFF252B35, 0xFFA8B3C2, 0xFF5B6470),
        TextTheme("black", "夜间", 0xFF121212, 0xFF9E9E9E, 0xFF555555),
    )
    val default: TextTheme = all.first { it.id == "paper" }

    fun byId(id: String): TextTheme = all.firstOrNull { it.id == id } ?: default
    fun isValid(id: String): Boolean = all.any { it.id == id }
}

/** The line heights the typesetting panel offers. Preferences store the [id]. */
enum class TextLineSpacing(val id: String, val label: String, val multiplier: Float) {
    // 1.28 is the comfortable Chinese reading rhythm; the other two step evenly around it.
    COMPACT("compact", "紧凑", 1.12f),
    STANDARD("standard", "标准", 1.28f),
    LOOSE("spacious", "宽松", 1.45f),
    ;

    companion object {
        fun byId(id: String): TextLineSpacing = entries.firstOrNull { it.id == id } ?: STANDARD
        fun isValid(id: String): Boolean = entries.any { it.id == id }
    }
}

/** Global typesetting; changing any knob repaginates the book and re-maps the reading position. */
data class TextReadingSettings(
    val fontSizeSp: Int = DEFAULT_FONT_SIZE,
    val lineSpacing: TextLineSpacing = TextLineSpacing.STANDARD,
    val theme: TextTheme = TextThemes.default,
) {
    /** One A−/A+ step, kept inside the range the typesetting panel and the preferences allow. */
    fun stepped(direction: Int): TextReadingSettings = copy(
        fontSizeSp = (fontSizeSp + direction * FONT_STEP).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
    )

    companion object {
        const val MIN_FONT_SIZE = 14
        const val MAX_FONT_SIZE = 26
        const val DEFAULT_FONT_SIZE = 18
        const val FONT_STEP = 2
    }
}
