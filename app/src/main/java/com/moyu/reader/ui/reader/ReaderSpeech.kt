package com.moyu.reader.ui.reader

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.data.SpeechPreferences
import com.moyu.reader.speech.SpeechEngineInfo
import com.moyu.reader.speech.SpeechEngines
import com.moyu.reader.speech.SpeechReader
import com.moyu.reader.speech.SpeechVoice

/**
 * Reading aloud, as the reader sees it: which engines this device offers, which one is in use, and
 * how much of the sleep timer is left.
 *
 * The speech engine itself belongs to the device and outlives a book, so [reader] is handed in;
 * everything here is per-visit state that must be dropped with the book.
 */
internal class ReaderSpeech(val reader: SpeechReader) {
    var engines by mutableStateOf<List<SpeechEngineInfo>>(emptyList())
        private set

    /** Minutes chosen on the sleep timer; 0 means off. */
    var sleepMinutes by mutableIntStateOf(0)
        private set

    /** Only read inside the speech panel, so a ticking second does not redraw the page. */
    val sleepRemaining = mutableIntStateOf(0)

    /** The sentence being read, marked on the page itself so the eye can follow the voice. */
    val highlight: TextPageSource.Highlight?
        get() = reader.line?.let { line ->
            TextPageSource.Highlight(line.offset, line.offset + line.text.length)
        }

    fun useEngines(found: List<SpeechEngineInfo>) {
        engines = found
    }

    fun setSleep(minutes: Int) {
        sleepMinutes = minutes
    }

    fun clearSleep() {
        sleepMinutes = 0
    }
}

/** The engine and voice a reading should start with, once the device's engines are known. */
internal class SpeechChoice(
    val engine: String?,
    val voices: List<SpeechVoice>,
    val voice: SpeechVoice?,
)

/**
 * Resolves the stored preference against what this device actually has. A voice belongs to its
 * engine, so it is only looked for in the chosen engine's list; an engine that disappeared drops
 * back to the device default, then to the first engine that can read Chinese.
 */
internal fun chooseSpeech(
    engines: List<SpeechEngineInfo>,
    activity: Activity,
    preferences: SpeechPreferences,
): SpeechChoice {
    val engine = engines.firstOrNull { it.packageName == preferences.engine }?.packageName
        ?: engines.firstOrNull { it.packageName == SpeechEngines.defaultEngine(activity) }?.packageName
        ?: engines.firstOrNull()?.packageName
    val voices = engines.firstOrNull { it.packageName == engine }?.voices.orEmpty()
    // The stored value is the voice key (name + locale); older rows stored only the name.
    val voice = voices.firstOrNull { it.key == preferences.voice }
        ?: voices.firstOrNull { it.name == preferences.voice }
        // Nothing chosen yet: the engine's first voice is what it would use anyway.
        ?: voices.firstOrNull()
    return SpeechChoice(engine, voices, voice)
}
