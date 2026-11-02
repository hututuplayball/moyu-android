package com.moyu.reader

import com.moyu.reader.book.TextLayoutCache
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TextLayoutCacheTest {
    private lateinit var directory: File
    private lateinit var cache: TextLayoutCache

    /** Book ids are content hashes, so only 64 lowercase hex characters may become file names. */
    private val id = "a1b2c3d4".repeat(8)

    @Before fun setUp() {
        directory = File(System.getProperty("java.io.tmpdir"), "text-layout-${UUID.randomUUID()}")
        directory.mkdirs()
        cache = TextLayoutCache(directory)
    }

    @Test fun aStoredPageMapSurvivesARoundTrip() {
        val written = snapshot()
        cache.write(key(), written)
        val read = cache.read(key())
        assertNotNull(read)
        assertEquals(2, read!!.pages.size)
        assertEquals(0, read.pages[0].startChar)
        assertEquals(20, read.pages[1].startChar)
        assertEquals(2, read.pages[0].segments.size)
        assertEquals(4, read.pages[1].segments[0][2])
        assertArrayEquals(written.paragraphOffsets, read.paragraphOffsets)
    }

    @Test fun anotherTypesettingNeverReusesTheMap() {
        cache.write(key("v1|1080|2280|40"), snapshot())
        assertNull(cache.read(key("v1|1080|2280|44")))
    }

    @Test fun aDamagedMapIsIgnored() {
        cache.write(key(), snapshot())
        val file = directory.listFiles()!!.single()
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertNull(cache.read(key()))
    }

    @Test fun idsThatAreNotContentHashesAreRefused() {
        listOf("../escape", "12345", id.uppercase()).forEach { bad ->
            cache.write(TextLayoutCache.Key(bad, 3, 60, "v1"), snapshot())
            assertNull(cache.read(TextLayoutCache.Key(bad, 3, 60, "v1")))
        }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun offsetsMustNeverMoveBackwards() {
        val broken = TextLayoutCache.Snapshot(snapshot().pages, intArrayOf(0, 30, 10, 60))
        cache.write(key(), broken)
        assertNull(cache.read(key()))
    }

    @Test fun pageStartsMustNeverMoveBackwards() {
        val pages = listOf(
            TextLayoutCache.StoredPage(20, listOf(intArrayOf(0, 0, 1))),
            TextLayoutCache.StoredPage(0, listOf(intArrayOf(0, 0, 1))),
        )
        cache.write(key(), TextLayoutCache.Snapshot(pages, intArrayOf(0, 10, 30, 60)))
        assertNull(cache.read(key()))
    }

    @Test fun changingTypesettingKeepsTheLastFewMaps() {
        writeAt("oldest", 1_000L)
        writeAt("older", 2_000L)
        writeAt("newer", 3_000L)
        writeAt("newest", 4_000L)
        cache.retainRecent(key("newest"))
        val files = directory.listFiles()!!
        assertEquals(3, files.size)
        assertNotNull(cache.read(key("newest")))
        assertNotNull(cache.read(key("newer")))
        assertNotNull(cache.read(key("older")))
        assertNull(cache.read(key("oldest")))
    }

    @Test fun deletingABookDropsOnlyItsOwnMap() {
        val other = "b1c2d3e4".repeat(8)
        cache.write(key(), snapshot())
        cache.write(TextLayoutCache.Key(other, 3, 60, "v1"), snapshot())
        cache.delete(id)
        assertNull(cache.read(key()))
        assertNotNull(cache.read(TextLayoutCache.Key(other, 3, 60, "v1")))
    }

    private fun key(signature: String = "v1|1080|2280|40|50|27|44|8|10|11|10|30|33") =
        TextLayoutCache.Key(id, 3, 60, signature)

    /** Writes one map and gives it a known timestamp, so retention order never depends on the clock. */
    private fun writeAt(signature: String, time: Long) {
        val before = directory.listFiles()!!.map { it.name }.toSet()
        cache.write(key(signature), snapshot())
        directory.listFiles()!!.first { it.name !in before }.setLastModified(time)
    }

    private fun snapshot() = TextLayoutCache.Snapshot(
        pages = listOf(
            TextLayoutCache.StoredPage(
                0,
                listOf(intArrayOf(0, 0, 3), intArrayOf(1, 0, 2)),
            ),
            TextLayoutCache.StoredPage(20, listOf(intArrayOf(2, 0, 4))),
        ),
        paragraphOffsets = intArrayOf(0, 10, 30, 60),
    )
}
