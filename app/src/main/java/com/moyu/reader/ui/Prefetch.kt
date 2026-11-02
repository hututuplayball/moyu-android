package com.moyu.reader.ui

/**
 * Pages to warm around [currentPage], nearest first. A swipe cancels the rest of the pass, so the
 * one or two turns a gesture can reach must be requested before the pages three turns away.
 * Concentric inside each distance: the previous page is the one a backward turn needs.
 */
internal fun prefetchPages(currentPage: Int, pageCount: Int, radius: Int): List<Int> {
    val pages = ArrayList<Int>(radius * 2)
    if (pageCount <= 0 || radius <= 0) return pages
    for (distance in 1..radius) {
        for (offset in intArrayOf(-distance, distance)) {
            val page = currentPage + offset
            if (page in 0 until pageCount) pages.add(page)
        }
    }
    return pages
}

/**
 * [prefetchPages] farthest first, so a cache touch pass ends on the neighbours. The render cache
 * evicts its least recently used entry, and this order is what keeps it from evicting the page a
 * backward turn needs while it makes room for a page three turns ahead.
 */
internal fun touchPages(currentPage: Int, pageCount: Int, radius: Int): List<Int> =
    prefetchPages(currentPage, pageCount, radius).asReversed()
