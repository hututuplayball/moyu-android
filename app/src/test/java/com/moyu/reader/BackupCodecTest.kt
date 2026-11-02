package com.moyu.reader.data

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupCodecTest {
    private val hash = "a".repeat(64)
    private val valid = MetadataSnapshot(
        createdAt = 200,
        groups = listOf(GroupEntity("series", "阅读系列", 0, 100)),
        books = listOf(BookMetadata(hash, "A title", 20, 5, 77, "horizontal", 10, 100, "series", 2, listOf(2, 7))),
        preferences = ReaderPreferences("dark", "vertical", true, false, hash),
        preferencesUpdatedAt = 100,
    )

    @Test fun roundTripContainsOnlyPortableMetadata() {
        val encoded = BackupCodec.encode(valid)
        assertEquals(valid, BackupCodec.decode(encoded))
        assertEquals(valid, BackupCodec.read(ByteArrayInputStream(encoded)))
        val text = encoded.toString(Charsets.UTF_8)
        listOf("fileName", "filePath", "content://", "backup_tree", "grant", "pdfBytes").forEach {
            assertFalse("Unexpected nonportable field: $it", text.contains(it))
        }
    }

    @Test fun readsPreEbookSnapshotsAsComicsAndRoundTripsBothShelves() {
        val legacyPayload = JSONObject(JSONObject(BackupCodec.encode(valid).toString(Charsets.UTF_8)).getString("payload"))
            .apply {
                getJSONArray("groups").getJSONObject(0).remove("kind")
                getJSONArray("books").getJSONObject(0).remove("kind")
            }
        assertEquals(valid, BackupCodec.decode(envelope(legacyPayload.toString())))

        val ebook = valid.copy(
            groups = listOf(GroupEntity("ebooks", "电子书", 0, 100, KIND_EBOOK)),
            books = listOf(valid.books.single().copy(groupId = "ebooks", kind = KIND_EBOOK)),
        )
        assertEquals(ebook, BackupCodec.decode(BackupCodec.encode(ebook)))

        val mixed = valid.copy(groups = listOf(valid.groups.single().copy(kind = KIND_EBOOK)))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(mixed) }
    }

    @Test fun readsSnapshotsWrittenBeforeTheReduceMotionToggleWasRemoved() {
        val legacyPayload = JSONObject(JSONObject(BackupCodec.encode(valid).toString(Charsets.UTF_8)).getString("payload"))
            .apply { getJSONObject("preferences").put("reduceMotion", true) }
        assertEquals(valid, BackupCodec.decode(envelope(legacyPayload.toString())))
    }

    @Test fun rejectsTruncationChecksumMismatchUnsupportedVersionAndTrailingData() {
        val bytes = BackupCodec.encode(valid)
        assertThrows(Exception::class.java) { BackupCodec.decode(bytes.copyOf(bytes.size - 10)) }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        envelope.put("sha256", "0".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(envelope.toString().toByteArray()) }
        val wrongVersion = JSONObject(bytes.toString(Charsets.UTF_8)).put("version", 99)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(wrongVersion.toString().toByteArray()) }
        assertThrows(Exception::class.java) { BackupCodec.decode(bytes + "garbage".toByteArray()) }
        val incomplete = JSONObject(bytes.toString(Charsets.UTF_8)).put("complete", false)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(incomplete.toString().toByteArray()) }
    }

    @Test fun validatesExternalHashesPagesOffsetsEnumsNamesAndReferencesBeforeMerge() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("books").getJSONObject(0).put("id", "../not-a-hash") },
            { it.getJSONArray("books").getJSONObject(0).put("pageCount", 0) },
            { it.getJSONArray("books").getJSONObject(0).put("currentPage", 20) },
            { it.getJSONArray("books").getJSONObject(0).put("currentPage", 1.5) },
            { it.getJSONArray("books").getJSONObject(0).put("pageOffset", -1) },
            { it.getJSONArray("books").getJSONObject(0).put("mode", "unknown") },
            { it.getJSONArray("books").getJSONObject(0).put("title", "x".repeat(256)) },
            { it.getJSONArray("books").getJSONObject(0).put("groupId", "absent") },
            { it.getJSONArray("books").getJSONObject(0).put("updatedAt", -1) },
            { it.getJSONArray("books").getJSONObject(0).put("sortOrder", -1) },
            { it.getJSONArray("books").getJSONObject(0).put("bookmarks", JSONArray(listOf(1, 1))) },
            { it.getJSONArray("books").getJSONObject(0).put("bookmarks", JSONArray(listOf(20))) },
            { it.getJSONArray("books").getJSONObject(0).put("kind", 2) },
            { it.getJSONArray("groups").getJSONObject(0).put("name", " ") },
            { it.getJSONArray("groups").getJSONObject(0).put("name", "bad\nname") },
            { it.getJSONArray("groups").getJSONObject(0).put("id", "nested/group") },
            { it.getJSONObject("preferences").put("theme", "unknown") },
            { it.getJSONObject("preferences").put("defaultMode", "") },
            { it.getJSONObject("preferences").put("lastReadingBookId", "invalid") },
            { it.getJSONObject("preferences").put("rightToLeft", "true") },
            { it.getJSONArray("books").put(it.getJSONArray("books").getJSONObject(0)) },
        )
        mutations.forEach { mutation ->
            assertThrows(Exception::class.java) { BackupCodec.decode(mutated(mutation)) }
        }
    }

    @Test fun enforcesByteArrayStreamingArrayAndNestingLimits() {
        val huge = ByteArray(BackupCodec.MAX_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(huge) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.read(ByteArrayInputStream(huge)) }
        val tooMany = mutated { root ->
            root.put("books", JSONArray().apply { repeat(MetadataRules.MAX_BOOKS + 1) { put(JSONObject.NULL) } })
        }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(tooMany) }
        val deep = ("{\"deep\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}").toByteArray()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(deep) }
    }

    @Test fun rejectsHugeShallowContainersBeforeBuildingDom() {
        // Six MiB of text, not two million JSONObject allocations in the fixture.
        val shallow = "{\"unexpected\":[" + "{},".repeat(2_000_000) + "{}]}"
        val bytes = shallow.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.size < BackupCodec.MAX_BYTES)
        val failure = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(bytes) }
        assertEquals("备份包含未知字段。", failure.message)
        val payloadFailure = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(envelope(shallow.replace("unexpected", "books")))
        }
        assertEquals("备份字段缺失。", payloadFailure.message) // Stops at the very first empty book.
    }

    @Test fun streamingPrepassEnforcesSchemaKeysStringsAndAggregateBookmarkBudget() {
        val unknown = mutated { it.getJSONArray("books").getJSONObject(0).put("extra", JSONArray()) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(unknown) }
        val longText = mutated { it.getJSONArray("groups").getJSONObject(0).put("name", "x".repeat(256)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(longText) }
        // Each array is individually within its bound; their combined element count is not.
        val book = JSONObject(JSONObject(BackupCodec.encode(valid).toString(Charsets.UTF_8)).getString("payload"))
            .getJSONArray("books").getJSONObject(0)
        book.put("bookmarks", JSONArray())
        val text = book.toString().replace("\"bookmarks\":[]",
            "\"bookmarks\":[" + "0,".repeat(50_000) + "0]")
        val payload = "{\"books\":[$text,$text]}"
        val failure = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(envelope(payload)) }
        assertEquals("书签过多。", failure.message)
    }

    @Test fun streamingBudgetsAcceptAllSchemaCountLimitsTogether() {
        val groups = List(MetadataRules.MAX_GROUPS) { GroupEntity("g$it", "G", it.toLong(), 100) }
        val books = List(MetadataRules.MAX_BOOKS) { index ->
            valid.books.single().copy(id = index.toString(16).padStart(64, '0'), title = "B",
                groupId = groups[index % groups.size].id, sortOrder = index.toLong(), bookmarks = (0..9).toList())
        }
        val snapshot = valid.copy(groups = groups, books = books)
        assertEquals(snapshot, BackupCodec.decode(BackupCodec.encode(snapshot)))
    }

    @Test fun externalFutureTimestampsAreRejectedButModestClockSkewAndBoundedRevisionsAreValid() {
        val skewed = System.currentTimeMillis() + MetadataRules.MAX_CLOCK_SKEW_MS / 2
        val accepted = valid.copy(createdAt = skewed, preferencesUpdatedAt = skewed,
            groups = valid.groups.map { it.copy(updatedAt = skewed) },
            books = valid.books.map { it.copy(importedAt = skewed, updatedAt = skewed) })
        assertEquals(accepted, BackupCodec.decode(BackupCodec.encode(accepted)))
        val futures = listOf(
            valid.copy(createdAt = MetadataRules.MAX_TIME),
            valid.copy(preferencesUpdatedAt = MetadataRules.MAX_TIME),
            valid.copy(groups = valid.groups.map { it.copy(updatedAt = MetadataRules.MAX_TIME) }),
            valid.copy(books = valid.books.map { it.copy(updatedAt = MetadataRules.MAX_TIME) }),
            valid.copy(books = valid.books.map { it.copy(importedAt = MetadataRules.MAX_TIME) }),
        )
        futures.forEach { snapshot ->
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(snapshot)) }
        }
        assertEquals(skewed + 1, MetadataRules.nextTimestamp(skewed))
        assertEquals(MetadataRules.MAX_TIME, MetadataRules.nextTimestamp(MetadataRules.MAX_TIME))
    }

    private fun mutated(change: (JSONObject) -> Unit): ByteArray {
        val payload = JSONObject(JSONObject(BackupCodec.encode(valid).toString(Charsets.UTF_8)).getString("payload"))
        change(payload)
        return envelope(payload.toString())
    }

    private fun envelope(text: String): ByteArray {
        val envelope = JSONObject(BackupCodec.encode(valid).toString(Charsets.UTF_8))
        val checksum = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return envelope.put("payload", text).put("sha256", checksum).toString().toByteArray(Charsets.UTF_8)
    }
}
