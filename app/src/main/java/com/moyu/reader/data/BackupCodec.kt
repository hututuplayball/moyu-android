package com.moyu.reader.data

import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Only portable metadata: no filenames, source URIs, grants, covers, or PDF bytes. */
data class BookMetadata(
    val id: String,
    val title: String,
    val pageCount: Int,
    val currentPage: Int,
    val pageOffset: Int,
    val mode: String,
    val importedAt: Long,
    val updatedAt: Long,
    val groupId: String?,
    val sortOrder: Long,
    val bookmarks: List<Int>,
    val kind: Int = KIND_COMIC,
) {
    companion object {
        fun from(book: BookEntity, bookmarks: List<Int>) = BookMetadata(book.id, book.title,
            book.pageCount, book.currentPage, book.pageOffset, book.mode, book.importedAt,
            book.updatedAt, book.groupId, book.sortOrder, bookmarks, book.kind)
    }
}

data class MetadataSnapshot(
    val createdAt: Long,
    val groups: List<GroupEntity>,
    val books: List<BookMetadata>,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val preferencesUpdatedAt: Long = 0,
)

internal object MetadataRules {
    val hash = Regex("[0-9a-f]{64}")
    private val groupId = Regex("[A-Za-z0-9_-]{1,64}")
    const val MAX_BOOKS = 10_000
    const val MAX_GROUPS = 1_000
    const val MAX_BOOKMARKS = 100_000
    const val MAX_PAGE_COUNT = 1_000_000
    const val MAX_OFFSET = 100_000_000
    const val MAX_ORDER = 1_000_000_000_000L
    const val MAX_TIME = 253_402_300_799_999L
    const val MAX_CLOCK_SKEW_MS = 24 * 60 * 60 * 1000L

    fun bookId(id: String) { require(hash.matches(id)) { "书籍 SHA256 标识无效。" } }
    fun groupId(id: String) { require(groupId.matches(id)) { "分组标识无效。" } }
    fun name(name: String) {
        require(name.isNotBlank() && name.length <= 255 && name.none { it.isISOControl() }) {
            "名称不能为空、超过 255 字或包含控制字符。"
        }
    }
    fun ids(ids: List<String>) {
        require(ids.size <= MAX_BOOKS && ids.distinct().size == ids.size) { "书籍列表无效。" }
        ids.forEach(::bookId)
    }
    fun mode(mode: String, allowDefault: Boolean = true) {
        require(mode == "vertical" || mode == "horizontal" || (allowDefault && mode.isEmpty())) {
            "阅读模式无效。"
        }
    }
    fun kind(value: Int) {
        require(value == KIND_COMIC || value == KIND_EBOOK) { "书架类型无效。" }
    }
    fun textState(value: Int) {
        require(value in TEXT_UNKNOWN..TEXT_UNSUPPORTED) { "文字版状态无效。" }
    }
    fun preferences(value: ReaderPreferences) {
        require(value.theme in setOf("system", "light", "dark")) { "主题无效。" }
        mode(value.defaultMode, false)
        value.lastReadingBookId?.let(::bookId)
    }
    fun timestamp(value: Long) { require(value in 0..MAX_TIME) { "时间无效。" } }
    fun restoredTimestamp(value: Long, now: Long = System.currentTimeMillis()) {
        timestamp(value)
        require(value <= now.coerceIn(0, MAX_TIME) + MAX_CLOCK_SKEW_MS) { "备份时间超前，请检查设备时钟。" }
    }
    fun nextTimestamp(previous: Long): Long {
        timestamp(previous)
        return maxOf(System.currentTimeMillis().coerceIn(0, MAX_TIME), (previous + 1).coerceAtMost(MAX_TIME))
    }
    fun order(value: Long) { require(value in 0..MAX_ORDER) { "排序值无效。" } }
    fun book(value: BookMetadata) {
        bookId(value.id)
        name(value.title)
        require(value.pageCount in 1..MAX_PAGE_COUNT) { "页数无效。" }
        require(value.currentPage in 0 until value.pageCount) { "阅读页码无效。" }
        require(value.pageOffset in 0..MAX_OFFSET) { "阅读偏移无效。" }
        mode(value.mode)
        timestamp(value.importedAt)
        timestamp(value.updatedAt)
        order(value.sortOrder)
        kind(value.kind)
        value.groupId?.let(::groupId)
        require(value.bookmarks.size <= MAX_BOOKMARKS &&
            value.bookmarks.distinct().size == value.bookmarks.size &&
            value.bookmarks.all { it in 0 until value.pageCount }) { "书签无效。" }
    }
}

/** A checksummed, versioned envelope allows torn writes to be ignored safely. */
object BackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private const val FORMAT = "com.moyu.reader.metadata"
    private const val VERSION = 1

    fun validate(snapshot: MetadataSnapshot) {
        MetadataRules.timestamp(snapshot.createdAt)
        MetadataRules.timestamp(snapshot.preferencesUpdatedAt)
        MetadataRules.preferences(snapshot.preferences)
        require(snapshot.groups.size <= MetadataRules.MAX_GROUPS) { "分组过多。" }
        require(snapshot.books.size <= MetadataRules.MAX_BOOKS) { "书籍过多。" }
        val groups = snapshot.groups.associate { it.id to it.kind }
        require(groups.size == snapshot.groups.size) { "分组标识重复。" }
        require(snapshot.books.map { it.id }.toSet().size == snapshot.books.size) { "书籍标识重复。" }
        snapshot.groups.forEach {
            MetadataRules.groupId(it.id)
            MetadataRules.name(it.name)
            MetadataRules.order(it.sortOrder)
            MetadataRules.kind(it.kind)
            MetadataRules.timestamp(it.updatedAt)
        }
        require(snapshot.books.sumOf { it.bookmarks.size.toLong() } <= MetadataRules.MAX_BOOKMARKS) {
            "书签过多。"
        }
        snapshot.books.forEach {
            MetadataRules.book(it)
            require(it.groupId == null || groups[it.groupId] == it.kind) {
                "书籍与分组类型不一致。"
            }
        }
    }

    internal fun validateForRestore(snapshot: MetadataSnapshot) {
        validate(snapshot)
        val now = System.currentTimeMillis()
        MetadataRules.restoredTimestamp(snapshot.createdAt, now)
        MetadataRules.restoredTimestamp(snapshot.preferencesUpdatedAt, now)
        snapshot.groups.forEach { MetadataRules.restoredTimestamp(it.updatedAt, now) }
        snapshot.books.forEach {
            MetadataRules.restoredTimestamp(it.importedAt, now)
            MetadataRules.restoredTimestamp(it.updatedAt, now)
        }
    }

    /** Structural writes reserve the maximum encoded growth of progress/revisions/settings.
     * This also covers whichever preferences win a concurrent DataStore merge, without scanning
     * or serializing the library on every scroll frame or settings edit. */
    internal fun requireCapacity(snapshot: MetadataSnapshot) {
        validate(snapshot)
        encode(snapshot.copy(
            createdAt = MetadataRules.MAX_TIME,
            preferencesUpdatedAt = MetadataRules.MAX_TIME,
            preferences = ReaderPreferences("system", "horizontal", false, false, "f".repeat(64)),
            groups = snapshot.groups.map { it.copy(updatedAt = MetadataRules.MAX_TIME) },
            books = snapshot.books.map { it.copy(currentPage = it.pageCount - 1,
                pageOffset = MetadataRules.MAX_OFFSET, mode = "horizontal", updatedAt = MetadataRules.MAX_TIME) },
        ))
    }

    fun encode(snapshot: MetadataSnapshot): ByteArray {
        validate(snapshot)
        val prefs = snapshot.preferences
        val payload = JSONObject()
            .put("createdAt", snapshot.createdAt)
            .put("preferencesUpdatedAt", snapshot.preferencesUpdatedAt)
            .put("preferences", JSONObject().put("theme", prefs.theme)
                .put("defaultMode", prefs.defaultMode).put("rightToLeft", prefs.rightToLeft)
                .put("keepScreenOn", prefs.keepScreenOn)
                .put("lastReadingBookId", prefs.lastReadingBookId ?: JSONObject.NULL))
            .put("groups", JSONArray().apply {
                snapshot.groups.forEach { group ->
                    put(JSONObject().put("id", group.id).put("name", group.name)
                        .put("sortOrder", group.sortOrder).put("updatedAt", group.updatedAt)
                        .put("kind", group.kind))
                }
            })
            .put("books", JSONArray().apply {
                snapshot.books.forEach { book ->
                    put(JSONObject().put("id", book.id).put("title", book.title)
                        .put("pageCount", book.pageCount).put("currentPage", book.currentPage)
                        .put("pageOffset", book.pageOffset).put("mode", book.mode)
                        .put("importedAt", book.importedAt).put("updatedAt", book.updatedAt)
                        .put("groupId", book.groupId ?: JSONObject.NULL).put("sortOrder", book.sortOrder)
                        .put("bookmarks", JSONArray(book.bookmarks)).put("kind", book.kind))
                }
            }).toString()
        val bytes = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("payload", payload).put("sha256", sha256(payload.toByteArray(Charsets.UTF_8)))
            .put("complete", true).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "备份超过 8 MiB 上限。" }
        return bytes
    }

    fun decode(bytes: ByteArray): MetadataSnapshot {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "备份大小无效。" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val envelope = jsonObject(text, JsonKind.ENVELOPE)
        require(envelope.string("format") == FORMAT && envelope.number("version") == VERSION.toLong()) {
            "不支持的备份格式或版本。"
        }
        require(envelope.bool("complete")) { "备份尚未完成。" }
        val payload = envelope.string("payload")
        require(envelope.string("sha256") == sha256(payload.toByteArray(Charsets.UTF_8))) { "备份校验失败。" }
        val root = jsonObject(payload, JsonKind.PAYLOAD)
        val groupArray = root.getJSONArray("groups")
        val bookArray = root.getJSONArray("books")
        require(groupArray.length() <= MetadataRules.MAX_GROUPS && bookArray.length() <= MetadataRules.MAX_BOOKS)
        val groups = List(groupArray.length()) { index ->
            val item = groupArray.getJSONObject(index)
            GroupEntity(item.string("id"), item.string("name"), item.number("sortOrder"), item.number("updatedAt"),
                item.optInt("kind", KIND_COMIC))
        }
        var bookmarkCount = 0L
        val books = List(bookArray.length()) { index ->
            val item = bookArray.getJSONObject(index)
            val marks = item.getJSONArray("bookmarks")
            bookmarkCount += marks.length()
            require(bookmarkCount <= MetadataRules.MAX_BOOKMARKS)
            BookMetadata(item.string("id"), item.string("title"), item.integer("pageCount"),
                item.integer("currentPage"), item.integer("pageOffset"), item.string("mode"),
                item.number("importedAt"), item.number("updatedAt"), item.nullableString("groupId"),
                item.number("sortOrder"), List(marks.length()) { i -> exactInt(marks.get(i)) },
                kind = item.optInt("kind", KIND_COMIC))
        }
        val prefs = root.getJSONObject("preferences")
        return MetadataSnapshot(root.number("createdAt"), groups, books,
            ReaderPreferences(prefs.string("theme"), prefs.string("defaultMode"), prefs.bool("rightToLeft"),
                prefs.bool("keepScreenOn"), prefs.nullableString("lastReadingBookId")),
            root.number("preferencesUpdatedAt")).also(::validateForRestore)
    }

    fun read(input: InputStream): MetadataSnapshot {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "备份超过 8 MiB 上限。" }
            output.write(buffer, 0, count)
        }
        return decode(output.toByteArray())
    }

    private enum class JsonKind {
        ENVELOPE, PAYLOAD, PREFERENCES, GROUP, BOOK, GROUPS, BOOKS, BOOKMARKS,
        TEXT, PAYLOAD_TEXT, NULLABLE_TEXT, NUMBER, BOOLEAN,
    }

    private val envelopeFields = mapOf("format" to JsonKind.TEXT, "version" to JsonKind.NUMBER,
        "payload" to JsonKind.PAYLOAD_TEXT, "sha256" to JsonKind.TEXT, "complete" to JsonKind.BOOLEAN)
    private val payloadFields = mapOf("createdAt" to JsonKind.NUMBER, "preferencesUpdatedAt" to JsonKind.NUMBER,
        "preferences" to JsonKind.PREFERENCES, "groups" to JsonKind.GROUPS, "books" to JsonKind.BOOKS)
    private val preferenceFields = mapOf("theme" to JsonKind.TEXT, "defaultMode" to JsonKind.TEXT,
        "rightToLeft" to JsonKind.BOOLEAN, "keepScreenOn" to JsonKind.BOOLEAN,
        "lastReadingBookId" to JsonKind.NULLABLE_TEXT)
    // 0.2.0 snapshots also carried the removed reduce-motion toggle; keep them restorable.
    private val readablePreferenceFields = preferenceFields + ("reduceMotion" to JsonKind.BOOLEAN)
    private val groupFields = mapOf("id" to JsonKind.TEXT, "name" to JsonKind.TEXT,
        "sortOrder" to JsonKind.NUMBER, "updatedAt" to JsonKind.NUMBER, "kind" to JsonKind.NUMBER)
    private val bookFields = mapOf("id" to JsonKind.TEXT, "title" to JsonKind.TEXT,
        "pageCount" to JsonKind.NUMBER, "currentPage" to JsonKind.NUMBER, "pageOffset" to JsonKind.NUMBER,
        "mode" to JsonKind.TEXT, "importedAt" to JsonKind.NUMBER, "updatedAt" to JsonKind.NUMBER,
        "groupId" to JsonKind.NULLABLE_TEXT, "sortOrder" to JsonKind.NUMBER, "bookmarks" to JsonKind.BOOKMARKS,
        "kind" to JsonKind.NUMBER)
    // Snapshots written before the ebook shelf have no kind; their books and groups are comics.
    private val optionalFields = mapOf(
        JsonKind.GROUP to setOf("kind"),
        JsonKind.BOOK to setOf("kind"),
    )

    private class JsonBudget(var nodes: Int = 0, var containers: Int = 0, var bookmarks: Int = 0)
    private const val MAX_JSON_CONTAINERS = 2 * MetadataRules.MAX_BOOKS + MetadataRules.MAX_GROUPS + 4
    private const val MAX_JSON_NODES = 13 * MetadataRules.MAX_BOOKS + 6 * MetadataRules.MAX_GROUPS +
        MetadataRules.MAX_BOOKMARKS + 32

    private fun jsonObject(text: String, kind: JsonKind): JSONObject {
        // Reject unknown fields/types and excessive shallow allocations BEFORE JSONObject builds a DOM.
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            validateJsonValue(reader, kind, JsonBudget(), 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "备份包含多余数据。" }
        }
        return JSONObject(text)
    }

    private fun validateJsonValue(reader: JsonReader, kind: JsonKind, budget: JsonBudget, depth: Int) {
        require(depth <= 12 && ++budget.nodes <= MAX_JSON_NODES) { "备份 JSON 结构过大。" }
        val fields = when (kind) {
            JsonKind.ENVELOPE -> envelopeFields
            JsonKind.PAYLOAD -> payloadFields
            JsonKind.PREFERENCES -> readablePreferenceFields
            JsonKind.GROUP -> groupFields
            JsonKind.BOOK -> bookFields
            else -> null
        }
        if (fields != null) {
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "备份 JSON 对象无效。" }
            require(++budget.containers <= MAX_JSON_CONTAINERS) { "备份 JSON 容器过多。" }
            reader.beginObject()
            val keys = mutableSetOf<String>()
            while (reader.hasNext()) {
                require(keys.size < fields.size) { "备份字段过多。" }
                val key = reader.nextName()
                require(key.length <= 32 && key in fields) { "备份包含未知字段。" }
                require(keys.add(key)) { "备份字段重复。" }
                validateJsonValue(reader, fields.getValue(key), budget, depth + 1)
            }
            reader.endObject()
            // Legacy preference objects may include the removed reduce-motion key, and snapshots
            // written before the ebook shelf have no kind; everything else is still required.
            val required = when (kind) {
                JsonKind.PREFERENCES -> preferenceFields.keys
                else -> fields.keys - optionalFields[kind].orEmpty()
            }
            require(keys.containsAll(required)) { "备份字段缺失。" }
            return
        }
        when (kind) {
            JsonKind.GROUPS, JsonKind.BOOKS, JsonKind.BOOKMARKS -> {
                require(reader.peek() == JsonToken.BEGIN_ARRAY) { "备份 JSON 数组无效。" }
                require(++budget.containers <= MAX_JSON_CONTAINERS) { "备份 JSON 容器过多。" }
                val (limit, itemKind) = when (kind) {
                    JsonKind.GROUPS -> MetadataRules.MAX_GROUPS to JsonKind.GROUP
                    JsonKind.BOOKS -> MetadataRules.MAX_BOOKS to JsonKind.BOOK
                    else -> MetadataRules.MAX_BOOKMARKS to JsonKind.NUMBER
                }
                reader.beginArray()
                var count = 0
                while (reader.hasNext()) {
                    require(++count <= limit) { "备份数组过长。" }
                    if (kind == JsonKind.BOOKMARKS) {
                        require(++budget.bookmarks <= MetadataRules.MAX_BOOKMARKS) { "书签过多。" }
                    }
                    validateJsonValue(reader, itemKind, budget, depth + 1)
                }
                reader.endArray()
            }
            JsonKind.TEXT, JsonKind.PAYLOAD_TEXT, JsonKind.NULLABLE_TEXT -> {
                if (kind == JsonKind.NULLABLE_TEXT && reader.peek() == JsonToken.NULL) reader.nextNull()
                else {
                    require(reader.peek() == JsonToken.STRING) { "备份字符串无效。" }
                    val limit = if (kind == JsonKind.PAYLOAD_TEXT) MAX_BYTES else 255
                    require(reader.nextString().length <= limit) { "备份字符串过长。" }
                }
            }
            JsonKind.NUMBER -> {
                require(reader.peek() == JsonToken.NUMBER) { "备份数值无效。" }
                require(reader.nextString().length <= 20) { "备份数值过长。" }
            }
            JsonKind.BOOLEAN -> {
                require(reader.peek() == JsonToken.BOOLEAN) { "备份布尔值无效。" }
                reader.nextBoolean()
            }
            else -> throw IllegalArgumentException("备份 JSON 无效。")
        }
    }

    private fun JSONObject.string(key: String): String = get(key).also { require(it is String) } as String
    private fun JSONObject.nullableString(key: String): String? = if (get(key) === JSONObject.NULL) null else string(key)
    private fun JSONObject.bool(key: String): Boolean = get(key).also { require(it is Boolean) } as Boolean
    private fun JSONObject.number(key: String): Long = exactLong(get(key))
    private fun JSONObject.integer(key: String): Int = exactInt(get(key))
    private fun JSONObject.optInt(key: String, default: Int): Int =
        if (!has(key) || get(key) === JSONObject.NULL) default else integer(key)
    private fun exactLong(value: Any): Long {
        require(value is Int || value is Long) { "备份数值必须为整数。" }
        return (value as Number).toLong()
    }
    private fun exactInt(value: Any): Int {
        val number = exactLong(value)
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return number.toInt()
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
