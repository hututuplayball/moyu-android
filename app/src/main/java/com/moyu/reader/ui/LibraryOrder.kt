package com.moyu.reader.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal fun moveBookInOrder(ids: List<String>, bookId: String, targetId: String): List<String> {
    val from = ids.indexOf(bookId)
    val to = ids.indexOf(targetId)
    if (from < 0 || to < 0 || from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

internal fun moveIdToEnd(ids: List<String>, id: String): List<String> {
    val from = ids.indexOf(id)
    if (from < 0 || from == ids.lastIndex) return ids
    return ids.toMutableList().apply { add(removeAt(from)) }
}

internal fun reconcileBookOrder(preferred: List<String>, source: List<String>): List<String> {
    val available = source.toSet()
    val retained = preferred.filter { it in available }.distinct()
    val retainedSet = retained.toSet()
    return retained + source.filterNot { it in retainedSet }
}

internal fun validGroupName(name: String): Boolean = name.trim().let { it.isNotEmpty() && it.length <= 80 }

@Stable
internal class LibraryOrder {
    private var draft by mutableStateOf<List<String>?>(null)
    private var original: List<String> = emptyList()
    private var pending by mutableStateOf<List<String>?>(null)
    private var awaitingResult = false

    val busy: Boolean get() = pending != null

    fun visible(source: List<String>): List<String> = reconcileBookOrder(draft ?: pending ?: source, source)

    fun begin(source: List<String>): Boolean {
        if (busy || draft != null) return false
        original = source.toList()
        draft = original
        return true
    }

    fun move(bookId: String, targetId: String): Boolean {
        val before = draft ?: return false
        val after = moveBookInOrder(before, bookId, targetId)
        draft = after
        return after !== before
    }

    fun moveToEnd(id: String): Boolean {
        val before = draft ?: return false
        val after = moveIdToEnd(before, id)
        draft = after
        return after !== before
    }

    fun complete(source: List<String>): List<String>? {
        val result = draft
        draft = null
        // A concurrent source edit wins over an unfinished gesture.
        if (result == null || source != original || !stage(result, source)) return null
        return result
    }

    fun stage(result: List<String>, source: List<String>): Boolean {
        if (busy || result == source || result.size != source.size || result.toSet() != source.toSet()) return false
        pending = result.toList()
        awaitingResult = true
        return true
    }

    fun acknowledge(success: Boolean) {
        awaitingResult = false
        if (!success) pending = null
    }

    fun reconcile(source: List<String>) {
        val expected = pending ?: return
        if (awaitingResult) return
        // The source may contain other groups; only compare the submitted slots.
        val expectedIds = expected.toSet()
        val available = source.toSet()
        if (source.filter { it in expectedIds } == expected.filter { it in available }) pending = null
    }

    fun cancel() {
        draft = null
    }
}
