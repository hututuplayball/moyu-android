package com.moyu.reader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moyu.reader.book.TextLineSpacing
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextThemes
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// All repository instances share this one DataStore delegate.
private val Context.readerDataStore by preferencesDataStore(name = "reader_preferences")

data class ReaderPreferences(
    val theme: String = "system",
    val defaultMode: String = "vertical",
    val rightToLeft: Boolean = false,
    val keepScreenOn: Boolean = true,
    val lastReadingBookId: String? = null,
    // Text-book typesetting: global like WeChat Reading, applied to every EPUB/TXT.
    val textFontSize: Int = TextReadingSettings.DEFAULT_FONT_SIZE,
    val textLineSpacing: String = TextLineSpacing.STANDARD.id,
    val textThemeId: String = TextThemes.default.id,
    // Pages warmed on each side of the read page. Device-local: a modest phone can keep fewer.
    val prefetchRadius: Int = DEFAULT_PREFETCH_RADIUS,
    // Where the custom stepper reopens, even after a preset was picked in between.
    val lastCustomPrefetchRadius: Int = DEFAULT_CUSTOM_PREFETCH_RADIUS,
)

/** Three pages each way is the tuned default; the rest is a deliberate depth-versus-memory trade. */
const val PRESET_PREFETCH_RADIUS = 3
const val DEFAULT_PREFETCH_RADIUS = PRESET_PREFETCH_RADIUS
const val MIN_PREFETCH_RADIUS = 1
const val MAX_PREFETCH_RADIUS = 10
const val DEFAULT_CUSTOM_PREFETCH_RADIUS = 4

/** Reading-only brightness; -1 keeps the system level. Never leaves the device or the backup file. */
const val READER_BRIGHTNESS_FOLLOW_SYSTEM = -1f

internal data class PreferenceMetadata(val value: ReaderPreferences, val updatedAt: Long)
internal data class BackupConfiguration(val enabled: Boolean = true, val lastBackupAt: Long = 0, val pendingRestore: Boolean = false)

class PreferencesRepository internal constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.readerDataStore)

    // Backup reads must never turn a storage failure into an apparently empty/default store.
    internal val metadata: Flow<PreferenceMetadata> = store.data.map { prefs ->
        val radius = prefs[PREFETCH_RADIUS] ?: DEFAULT_PREFETCH_RADIUS
        PreferenceMetadata(ReaderPreferences(
            theme = prefs[THEME] ?: "system",
            defaultMode = prefs[DEFAULT_MODE] ?: "vertical",
            rightToLeft = prefs[RIGHT_TO_LEFT] ?: false,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: true,
            lastReadingBookId = prefs[LAST_BOOK],
            textFontSize = prefs[TEXT_FONT_SIZE] ?: TextReadingSettings.DEFAULT_FONT_SIZE,
            // 0.12.41 called 1.28 "loose"; it is now the standard step, so keep the same rhythm.
            textLineSpacing = when (val saved = prefs[TEXT_LINE_SPACING]) {
                "loose" -> TextLineSpacing.STANDARD.id
                else -> saved ?: TextLineSpacing.STANDARD.id
            },
            textThemeId = prefs[TEXT_THEME] ?: TextThemes.default.id,
            prefetchRadius = radius,
            // On the first run after this setting shipped, a custom depth is its own starting point.
            lastCustomPrefetchRadius = (prefs[LAST_CUSTOM_PREFETCH_RADIUS]
                ?: if (radius > PRESET_PREFETCH_RADIUS) radius else DEFAULT_CUSTOM_PREFETCH_RADIUS)
                .coerceIn(MIN_PREFETCH_RADIUS, MAX_PREFETCH_RADIUS),
        ), prefs[UPDATED_AT] ?: 0)
    }.distinctUntilChanged()

    val preferences: Flow<ReaderPreferences> = metadata.map { it.value }.catch { error ->
        if (error is IOException) emit(ReaderPreferences()) else throw error
    }.distinctUntilChanged()

    val readerBrightness: Flow<Float> = store.data
        .map { it[READER_BRIGHTNESS] ?: READER_BRIGHTNESS_FOLLOW_SYSTEM }
        .distinctUntilChanged()

    internal val backupConfiguration: Flow<BackupConfiguration> = store.data.map {
        BackupConfiguration(it[BACKUP_ENABLED] ?: true, it[LAST_BACKUP] ?: 0, it[PENDING_RESTORE] ?: false)
    }.distinctUntilChanged()

    suspend fun setTheme(value: String) {
        require(value in setOf("system", "light", "dark")) { "主题无效。" }
        change { if ((it[THEME] ?: "system") != value) { it[THEME] = value; true } else false }
    }

    suspend fun setDefaultMode(value: String) {
        MetadataRules.mode(value, false)
        change { if ((it[DEFAULT_MODE] ?: "vertical") != value) { it[DEFAULT_MODE] = value; true } else false }
    }

    suspend fun setRightToLeft(value: Boolean) {
        change { if ((it[RIGHT_TO_LEFT] ?: false) != value) { it[RIGHT_TO_LEFT] = value; true } else false }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        change { if ((it[KEEP_SCREEN_ON] ?: true) != value) { it[KEEP_SCREEN_ON] = value; true } else false }
    }

    suspend fun setReaderBrightness(value: Float) {
        require(value == READER_BRIGHTNESS_FOLLOW_SYSTEM || value in 0f..1f) { "亮度无效。" }
        // Deliberately skips the backup timestamp: display brightness is device-local.
        store.edit { if ((it[READER_BRIGHTNESS] ?: READER_BRIGHTNESS_FOLLOW_SYSTEM) != value) it[READER_BRIGHTNESS] = value }
    }

    suspend fun setPrefetchRadius(value: Int) {
        require(value in MIN_PREFETCH_RADIUS..MAX_PREFETCH_RADIUS) { "预渲染页数无效。" }
        // Also device-local: how much memory a phone can spare for warming pages is not a preference
        // worth carrying to another device, so this never touches the backup timestamp either.
        store.edit {
            if ((it[PREFETCH_RADIUS] ?: DEFAULT_PREFETCH_RADIUS) != value) it[PREFETCH_RADIUS] = value
            // Presets do not overwrite the custom starting point; only a custom pick moves it.
            if (value > PRESET_PREFETCH_RADIUS &&
                (it[LAST_CUSTOM_PREFETCH_RADIUS] ?: DEFAULT_CUSTOM_PREFETCH_RADIUS) != value
            ) {
                it[LAST_CUSTOM_PREFETCH_RADIUS] = value
            }
        }
    }

    suspend fun setTextReading(fontSize: Int? = null, lineSpacing: String? = null, themeId: String? = null) {
        fontSize?.let {
            require(it in TextReadingSettings.MIN_FONT_SIZE..TextReadingSettings.MAX_FONT_SIZE) { "字号无效。" }
        }
        lineSpacing?.let { require(TextLineSpacing.isValid(it)) { "行距无效。" } }
        themeId?.let { require(TextThemes.isValid(it)) { "阅读主题无效。" } }
        change {
            var changed = false
            if (fontSize != null && (it[TEXT_FONT_SIZE] ?: TextReadingSettings.DEFAULT_FONT_SIZE) != fontSize) {
                it[TEXT_FONT_SIZE] = fontSize; changed = true
            }
            if (lineSpacing != null &&
                (it[TEXT_LINE_SPACING] ?: TextLineSpacing.STANDARD.id) != lineSpacing) {
                it[TEXT_LINE_SPACING] = lineSpacing; changed = true
            }
            if (themeId != null && (it[TEXT_THEME] ?: TextThemes.default.id) != themeId) {
                it[TEXT_THEME] = themeId; changed = true
            }
            changed
        }
    }

    suspend fun setLastReadingBook(id: String?) {
        id?.let(MetadataRules::bookId)
        change {
            if (it[LAST_BOOK] == id) false else {
                if (id == null) it.remove(LAST_BOOK) else it[LAST_BOOK] = id
                true
            }
        }
    }

    internal suspend fun clearLastReadingBooks(ids: Set<String>) {
        change {
            val current = it[LAST_BOOK]
            if (current != null && current in ids) { it.remove(LAST_BOOK); true } else false
        }
    }

    private suspend fun change(block: (MutablePreferences) -> Boolean) {
        store.edit { if (block(it)) it[UPDATED_AT] = MetadataRules.nextTimestamp(it[UPDATED_AT] ?: 0) }
    }

    internal suspend fun mergeRestored(value: ReaderPreferences, updatedAt: Long) {
        MetadataRules.preferences(value)
        MetadataRules.restoredTimestamp(updatedAt)
        store.edit {
            // A freshly installed default store has no timestamp; ties favor existing local edits.
            if (it[UPDATED_AT] == null || updatedAt > (it[UPDATED_AT] ?: 0)) {
                it[THEME] = value.theme
                it[DEFAULT_MODE] = value.defaultMode
                it[RIGHT_TO_LEFT] = value.rightToLeft
                it[KEEP_SCREEN_ON] = value.keepScreenOn
                if (value.lastReadingBookId == null) it.remove(LAST_BOOK) else it[LAST_BOOK] = value.lastReadingBookId
                it[UPDATED_AT] = updatedAt
            }
        }
    }

    /** False only on a fresh installation, where a backup from the previous install may be restored. */
    internal suspend fun shelfInitialized(): Boolean = store.data.first()[SHELF_INITIALIZED] ?: false

    internal suspend fun markShelfInitialized() {
        store.edit { if (it[SHELF_INITIALIZED] != true) it[SHELF_INITIALIZED] = true }
    }

    internal suspend fun readBackupConfiguration(): BackupConfiguration = backupConfiguration.first()

    internal suspend fun setBackupConfiguration(value: BackupConfiguration) {
        store.edit {
            it[BACKUP_ENABLED] = value.enabled
            it[LAST_BACKUP] = value.lastBackupAt
            it[PENDING_RESTORE] = value.pendingRestore
        }
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val RIGHT_TO_LEFT = booleanPreferencesKey("right_to_left")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val LAST_BOOK = stringPreferencesKey("last_reading_book_id")
        val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val PREFETCH_RADIUS = intPreferencesKey("prefetch_radius")
        val LAST_CUSTOM_PREFETCH_RADIUS = intPreferencesKey("last_custom_prefetch_radius")
        val TEXT_FONT_SIZE = intPreferencesKey("text_font_size")
        val TEXT_LINE_SPACING = stringPreferencesKey("text_line_spacing")
        val TEXT_THEME = stringPreferencesKey("text_theme")
        val UPDATED_AT = longPreferencesKey("reader_metadata_updated_at")
        val BACKUP_ENABLED = booleanPreferencesKey("backup_enabled")
        val LAST_BACKUP = longPreferencesKey("last_metadata_backup_at")
        val PENDING_RESTORE = booleanPreferencesKey("backup_pending_restore")
        // Device-local: wiped on uninstall, which is exactly when a restore should be allowed again.
        val SHELF_INITIALIZED = booleanPreferencesKey("shelf_initialized")
    }
}
