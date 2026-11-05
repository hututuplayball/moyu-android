package com.moyu.reader.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Chinese voice an engine offers. [name] is the identifier the engine itself uses, which is
 * usually something like "cmn-cn-x-ccc-local", so [label] is the readable line shown to the reader.
 * Engines are free to repeat a voice name across locales (iFlytek does), so [key] is the identity
 * the UI and the preferences store.
 */
data class SpeechVoice(val name: String, val locale: String, val label: String) {
    val key: String get() = "$name|$locale"
}

/** A speech engine on this device, with the Chinese voices it can read with. */
data class SpeechEngineInfo(
    val packageName: String,
    val label: String,
    /** The engine can read Chinese, either through a Chinese voice or a Chinese language. */
    val chinese: Boolean,
    val voices: List<SpeechVoice>,
)

/**
 * Finds the speech engines installed on the device. Nothing is assumed: an engine that cannot
 * read Chinese is reported as such, so the reader can refuse to offer reading aloud.
 */
object SpeechEngines {
    private val lock = Mutex()
    private var cached: List<SpeechEngineInfo>? = null

    /**
     * The engine the device is set to use. The public way to ask is to resolve the TTS service
     * action, which the system points at whichever engine the user picked.
     */
    fun defaultEngine(context: Context): String? {
        val manager = context.applicationContext.packageManager
        val fromAction = manager.resolveService(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            ?.serviceInfo?.packageName
        if (!fromAction.isNullOrBlank()) return fromAction
        return runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** The engines on this device. The result is cached: probing starts real engines. */
    suspend fun load(context: Context): List<SpeechEngineInfo> {
        cached?.let { return it }
        return lock.withLock {
            cached ?: discover(context).also { cached = it }
        }
    }

    private suspend fun discover(context: Context): List<SpeechEngineInfo> {
        val app = context.applicationContext
        val found = ArrayList<SpeechEngineInfo>()
        for ((packageName, label) in installed(app)) {
            found += probe(app, packageName, label) ?: continue
        }
        return found
    }

    private fun installed(context: Context): List<Pair<String, String>> {
        val manager = context.packageManager
        // Engines are services answering the TTS service action; "android.speech.tts.engine" is
        // only the prefix of the check-data actions, not the discovery action.
        val engines = manager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), PackageManager.GET_META_DATA,
        ).mapNotNull { info ->
            val packageName = info.serviceInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(manager)?.toString() }.getOrNull()
            packageName to (label?.takeIf { it.isNotBlank() } ?: packageName)
        }.distinctBy { it.first }
        val fallback = defaultEngine(context)
        if (fallback == null || engines.any { it.first == fallback }) return engines
        // The system reports a package, not a name: ask the package for the label it shows.
        val name = runCatching {
            manager.getApplicationLabel(manager.getApplicationInfo(fallback, 0)).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
        return engines + (fallback to name)
    }

    /**
     * Starts one engine and asks it what it can do. The engine is created on the main thread:
     * its callbacks arrive there, and a callback that beats the instance assignment would
     * leave us without an engine to shut down.
     */
    private suspend fun probe(context: Context, packageName: String, label: String): SpeechEngineInfo? {
        val ready = CompletableDeferred<Boolean>()
        val engine = withContext(Dispatchers.Main.immediate) {
            try {
                TextToSpeech(context, { status -> ready.complete(status == TextToSpeech.SUCCESS) }, packageName)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return try {
            // A broken engine must not hang the whole discovery: give it a few seconds.
            if (withTimeoutOrNull(PROBE_TIMEOUT_MS) { ready.await() } != true) {
                shutdown(engine)
                return null
            }
            val language = runCatching { engine.isLanguageAvailable(Locale.CHINA) }.getOrDefault(-2)
            val chineseVoices = runCatching { engine.voices.orEmpty() }
                .getOrDefault(emptySet())
                .filter { it.locale.language == "zh" }
                .sortedWith(
                    compareBy(
                        { localeRank(it.locale.toLanguageTag()) },
                        { it.locale.toLanguageTag() },
                        { it.name },
                    ),
                )
                // An engine voice is often exposed under several locale aliases (iFlytek lists
                // "zh", "zh-Hans" and "zh-Hant" for the same "zh" voice); show one row per voice.
                .distinctBy { it.name.ifBlank { it.locale.toLanguageTag() } }
            val voices = voiceLabels(chineseVoices).map { (voice, label) ->
                SpeechVoice(voice.name, voice.locale.toLanguageTag(), label)
            }
            shutdown(engine)
            SpeechEngineInfo(
                packageName = packageName,
                label = label,
                chinese = voices.isNotEmpty() || language >= TextToSpeech.LANG_AVAILABLE,
                voices = voices,
            )
        } catch (_: Exception) {
            shutdown(engine)
            null
        }
    }

    /**
     * Turns the engine's own voice ids into readable lines. A voice id says whether it runs on the
     * device or over the network, and the locale says which Chinese it speaks. Two voices that
     * would read the same get a number, so every line in the list is distinguishable.
     */
    private fun voiceLabels(voices: List<Voice>): List<Pair<Voice, String>> {
        // An engine with a single Chinese voice has nothing to choose. Naming it after the locale
        // ("简体中文") would read like a setting the reader can change, so it is just the default.
        if (voices.size == 1) return listOf(voices.first() to "默认音色")
        val bases = voices.map { voice ->
            val kind = when {
                voice.name.endsWith("-local") -> "本地"
                voice.name.endsWith("-network") -> "在线"
                else -> null
            }
            val locale = voice.locale.toLanguageTag()
            val localeLabel = when (locale) {
                "zh-Hans", "zh-CN", "zh-SG" -> "简体中文"
                "zh-Hant", "zh-TW", "zh-HK", "zh-MO" -> "繁体中文"
                else -> "中文"
            }
            if (kind == null) localeLabel else "$localeLabel · $kind"
        }
        val counts = bases.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        return voices.mapIndexed { index, voice ->
            val base = bases[index]
            val label = if (counts[base] == 1) base else {
                val number = (seen[base] ?: 0) + 1
                seen[base] = number
                "$base $number"
            }
            voice to label
        }
    }

    private suspend fun shutdown(engine: TextToSpeech) {
        withContext(Dispatchers.Main.immediate) { runCatching { engine.shutdown() } }
    }

    /** Long enough for a cold engine to start, short enough not to look broken. */
    private const val PROBE_TIMEOUT_MS = 4_000L

    /** Simplified Chinese comes first: an alias list from the same voice keeps this locale. */
    private val LOCALE_PREFERENCE =
        listOf("zh-Hans", "zh-CN", "zh-SG", "zh", "zh-Hant", "zh-TW", "zh-HK", "zh-MO")

    private fun localeRank(tag: String): Int =
        LOCALE_PREFERENCE.indexOf(tag).let { if (it >= 0) it else LOCALE_PREFERENCE.size }
}
