package com.moyu.reader

import com.moyu.reader.ui.prefetchPages
import com.moyu.reader.ui.touchPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Warm-up order: nearest turn first, far pages last, and the cache touch pass in between. */
class PrefetchOrderTest {
    @Test
    fun nearestPagesComeFirstAndBothSidesAlternate() {
        assertEquals(listOf(4, 6, 3, 7, 2, 8), prefetchPages(currentPage = 5, pageCount = 40, radius = 3))
        assertEquals(listOf(4, 6), prefetchPages(currentPage = 5, pageCount = 40, radius = 1))
    }

    @Test
    fun bookEdgesWarmOnlyThePagesThatExist() {
        assertEquals(listOf(1, 2, 3), prefetchPages(currentPage = 0, pageCount = 10, radius = 3))
        assertEquals(listOf(8, 7, 6), prefetchPages(currentPage = 9, pageCount = 10, radius = 3))
        assertEquals(emptyList<Int>(), prefetchPages(currentPage = 0, pageCount = 1, radius = 3))
        assertEquals(emptyList<Int>(), prefetchPages(currentPage = 3, pageCount = 0, radius = 3))
    }

    @Test
    fun touchPassEndsOnThePagesASingleTurnNeeds() {
        val touched = touchPages(currentPage = 5, pageCount = 40, radius = 3)
        assertEquals(listOf(8, 2, 7, 3, 6, 4), touched)
        assertEquals(prefetchPages(5, 40, 3).toSet(), touched.toSet())
        // The very last lookups are the previous page and then the next one.
        assertEquals(4, touched.last())
        assertEquals(6, touched[touched.size - 2])
    }

    @Test
    fun touchPassKeepsThePreviousPageWhenTheNextPageNeedsRoom() {
        // A cache that holds six pages: exactly the prefetch radius and nothing to spare.
        val cache = Lru(6)
        prefetchPages(currentPage = 5, pageCount = 40, radius = 3).forEach(cache::put)
        touchPages(currentPage = 5, pageCount = 40, radius = 3).forEach { assertTrue(cache.get(it)) }
        cache.put(9) // The page a forward turn reveals: one far page has to go.
        assertTrue(cache.get(4))
        assertTrue(cache.get(6))
        assertFalse(cache.get(8))
    }

    @Test
    fun withoutTheTouchPassThePreviousPageIsTheFirstVictim() {
        val cache = Lru(6)
        prefetchPages(currentPage = 5, pageCount = 40, radius = 3).forEach(cache::put)
        cache.put(9)
        assertFalse(cache.get(4))
        assertTrue(cache.get(6))
    }

    private class Lru(private val capacity: Int) {
        private val entries = LinkedHashMap<Int, Unit>(0, 0.75f, true)

        fun get(page: Int): Boolean {
            if (entries[page] == null) return false
            entries[page] = Unit
            return true
        }

        fun put(page: Int) {
            entries[page] = Unit
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
    }
}
