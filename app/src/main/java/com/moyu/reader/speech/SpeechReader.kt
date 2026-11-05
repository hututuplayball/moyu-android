package com.moyu.reader.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Reads a book out loud with the device's own speech engine.
 *
 * The engine has no seek and no real pause, so the reading advances one sentence at a time:
 * pausing lets the current sentence finish and stops at the sentence boundary. Nothing about the
 * reading is stored; starting again simply begins at the place the reader is on.
 */
class SpeechReader(context: Context) {
    enum class Status { Idle, Playing, Paused, Failed }

    var status by mutableStateOf(Status.Idle)
        private set

    /** The sentence being read right now. */
    var line by mutableStateOf<SpeechLine?>(null)
        private set

    /** Why the reading stopped, when it stopped badly. */
    var failure by mutableStateOf<String?>(null)
        private set

    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var enginePackage: String? = null
    private var ready = false
    private var rate = 1f
    private var voice: SpeechVoice? = null
    private var paragraphs: List<Pair<Int, String>> = emptyList()
    private var paragraphIndex = 0
    private var sentences: List<SpeechText.Sentence> = emptyList()
    private var sentenceIndex = 0
    private var base = 0
    private var stopped = false
    private var counter = 0
    // The sentence being spoken right now, so another engine can take over without skipping it.
    private var spoken: String? = null
    private var spokenOffset = 0
    // The utterance the engine is working on; callbacks from an older one are ignored.
    private var currentUtterance: String? = null

    /**
     * Reads [paragraphs] from the beginning. Each entry is the book offset the text starts at and
     * the text itself, so every spoken sentence can be traced back to its place in the book.
     */
    suspend fun start(
        paragraphs: List<Pair<Int, String>>,
        engine: String?,
        voice: SpeechVoice?,
        rate: Float,
    ) {
        stop()
        this.paragraphs = paragraphs
        this.voice = voice
        this.rate = rate
        failure = null
        if (!ensureEngine(engine)) {
            status = Status.Failed
            failure = "语音引擎启动失败"
            return
        }
        applyVoice()
        paragraphIndex = 0
        sentenceIndex = 0
        sentences = emptyList()
        speakNext()
    }

    /**
     * Switches to another engine while the book is being read. The current sentence is spoken
     * again by the new engine instead of being skipped.
     */
    suspend fun useEngine(packageName: String?) {
        if (packageName == enginePackage && ready) return
        val reading = status == Status.Playing
        val paused = status == Status.Paused
        // A voice belongs to the engine it came from, so the old one goes with it.
        voice = null
        if (!reading && !paused) {
            // Nothing is being read: the next start simply uses the new engine.
            enginePackage = packageName
            return
        }
        val text = spoken
        val offset = spokenOffset
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        enginePackage = null
        if (!ensureEngine(packageName)) {
            status = Status.Failed
            failure = "语音引擎启动失败"
            return
        }
        applyVoice()
        // A paused reading keeps its place: the new engine takes over at the next sentence.
        if (reading && text != null) speak(text, offset)
    }

    /** Changes the speed of the reading; the current sentence keeps going. */
    fun setRate(value: Float) {
        rate = value
        runCatching { tts?.setSpeechRate(value) }
    }

    /**
     * Applies a speed the reader picked in the panel. Engines only honour a new rate when a
     * sentence starts, so the sentence being spoken is started again to make the change audible
     * right away instead of one sentence later.
     */
    fun applyRate(value: Float) {
        val changed = abs(value - rate) >= 0.005f
        setRate(value)
        if (!changed || status != Status.Playing) return
        val text = spoken ?: return
        speak(text, spokenOffset)
    }

    /** Changes the voice of the reading; the current sentence keeps going. */
    fun setVoice(value: SpeechVoice?) {
        voice = value
        applyVoice()
    }

    /**
     * Stops at the sentence being spoken right now. An engine cannot seek, so resuming repeats that
     * sentence from its start; waiting for a whole sentence (up to 220 characters) read as a dead
     * button instead.
     */
    fun pause() {
        if (status != Status.Playing) return
        // Cancel the running utterance before the state flips, so its callbacks are ignored.
        currentUtterance = null
        runCatching { tts?.stop() }
        status = Status.Paused
    }

    fun resume() {
        if (status != Status.Paused) return
        val text = spoken
        if (text != null) speak(text, spokenOffset) else speakNext()
    }

    fun stop() {
        stopped = true
        currentUtterance = null
        runCatching { tts?.stop() }
        status = Status.Idle
        line = null
        spoken = null
        paragraphs = emptyList()
        sentences = emptyList()
    }

    /** Drops the engine; the device may take it back at any time, so it is rebuilt when needed. */
    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        enginePackage = null
    }

    /** Starts the chosen engine and waits until it can speak. */
    private suspend fun ensureEngine(packageName: String?): Boolean {
        val existing = tts
        if (existing != null && enginePackage == packageName && ready) return true
        runCatching { existing?.shutdown() }
        tts = null
        ready = false
        enginePackage = packageName
        val holder = arrayOfNulls<TextToSpeech>(1)
        val started = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                var answered = false
                val instance = try {
                    TextToSpeech(app, { state ->
                        if (answered) return@TextToSpeech
                        answered = true
                        continuation.resume(state == TextToSpeech.SUCCESS)
                    }, packageName)
                } catch (_: Exception) {
                    null
                }
                holder[0] = instance
                if (instance == null) {
                    if (!answered) {
                        answered = true
                        continuation.resume(false)
                    }
                }
            }
        }
        if (!started) {
            runCatching { holder[0]?.shutdown() }
            return false
        }
        tts = holder[0]
        ready = true
        attachListener()
        return true
    }

    private fun applyVoice() {
        val engine = tts ?: return
        runCatching { engine.setSpeechRate(rate) }
        val chosen = voice ?: return
        // A voice is identified by its name and locale: some engines repeat the name per locale.
        val match = runCatching {
            engine.voices?.firstOrNull {
                it.name == chosen.name && it.locale.toLanguageTag() == chosen.locale
            }
        }.getOrNull()
        if (match != null) runCatching { engine.voice = match }
    }

    /** Speaks the next sentence, moving through paragraphs until the book runs out. */
    private fun speakNext() {
        val engine = tts
        if (engine == null || !ready) {
            status = Status.Failed
            failure = "语音引擎未就绪"
            return
        }
        stopped = false
        while (sentenceIndex >= sentences.size) {
            if (paragraphIndex >= paragraphs.size) {
                status = Status.Idle
                line = null
                paragraphs = emptyList()
                sentences = emptyList()
                return
            }
            val (offset, text) = paragraphs[paragraphIndex]
            paragraphIndex++
            base = offset
            sentences = SpeechText.sentences(text)
            sentenceIndex = 0
        }
        val sentence = sentences[sentenceIndex]
        sentenceIndex++
        speak(sentence.text, base + sentence.start)
    }

    /** Hands one sentence to the engine and shows it as the line being read. */
    private fun speak(text: String, offset: Int) {
        val engine = tts ?: return
        stopped = false
        spoken = text
        spokenOffset = offset
        line = SpeechLine(text, offset)
        status = Status.Playing
        counter++
        val utterance = "moyu-${counter}"
        currentUtterance = utterance
        val result = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            status = Status.Failed
            failure = "无法朗读这段文字"
        }
    }

    // Utterance callbacks arrive on a binder thread; the reading itself is moved onto the main
    // thread so the state it changes never leaves the thread the rest of the screen runs on.
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            main.post { finishSentence(utteranceId) }
        }

        override fun onError(utteranceId: String?) {
            main.post { failSentence(utteranceId) }
        }
    }

    /** Only the sentence that is still current may advance the reading. */
    private fun finishSentence(utteranceId: String?) {
        if (stopped || utteranceId != currentUtterance) return
        speakNext()
    }

    private fun failSentence(utteranceId: String?) {
        if (stopped || utteranceId != currentUtterance) return
        status = Status.Failed
        failure = "朗读中断，请重试"
        line = null
    }

    /** Attaches the progress listener to the engine that was just created. */
    private fun attachListener() {
        runCatching { tts?.setOnUtteranceProgressListener(listener) }
    }
}
