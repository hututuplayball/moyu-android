package com.moyu.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.GroupEntity
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.ui.PaperAction
import com.moyu.reader.ui.PaperConfirmation
import com.moyu.reader.ui.PaperModal
import com.moyu.reader.ui.PaperSegmented
import com.moyu.reader.ui.PaperTextField
import com.moyu.reader.ui.validGroupName

/**
 * The shelf's modal layer: which book list is about to be removed, which group is being renamed,
 * dissolved or moved into. Kept apart from the shelf itself because none of it is part of the
 * shelf's layout — it only ever sits on top of it.
 *
 * The group-name draft is the one thing that has to survive the process being killed mid-edit, so
 * it is the only part [Saver] carries; a confirmation dialog is cheap to reopen, a half-typed
 * name is not.
 */
internal class ShelfDialogs(
    nameEditorId: String? = null,
    nameDraft: String = "",
) {
    var deleteIds by mutableStateOf<List<String>?>(null)
    var moving by mutableStateOf(false)
    var groupActionsId by mutableStateOf<String?>(null)
    var deleteGroupId by mutableStateOf<String?>(null)
    var nameEditorId by mutableStateOf(nameEditorId)
    var nameDraft by mutableStateOf(nameDraft)

    /** True while any dialog owns the back gesture, so the shelf must not consume it. */
    val open: Boolean
        get() = deleteIds != null || moving || groupActionsId != null ||
            deleteGroupId != null || nameEditorId != null

    fun createGroup() {
        nameDraft = ""
        nameEditorId = ""
    }

    fun renameGroup(id: String, name: String) {
        nameDraft = name
        nameEditorId = id
    }

    companion object {
        val Saver: Saver<ShelfDialogs, Any> = listSaver(
            save = { listOf(it.nameEditorId ?: "", it.nameDraft) },
            restore = { ShelfDialogs((it[0] as String).ifEmpty { null }, it[1] as String) },
        )
    }
}

/**
 * Asks which shelf a file opened from outside the app belongs on.
 *
 * The container suggests an answer — readable text is a book, everything else a comic — but a PDF
 * novel and a zipped set of scans are both ordinary, and a book cannot be moved to the other shelf
 * later. Choosing is therefore separate from importing: the shelf is picked first, and nothing is
 * read from the file until 「导入」 is pressed.
 */
@Composable
internal fun OpenShelfDialog(
    fileName: String,
    suggested: Int,
    onImport: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember(fileName) { mutableStateOf(suggested) }
    PaperModal(
        title = "加入哪个书架？",
        onDismiss = onDismiss,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperAction("取消", onDismiss, Modifier.weight(1f))
                PaperAction("导入", { onImport(picked) }, Modifier.weight(1f), primary = true)
            }
        },
    ) {
        Text(
            fileName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        PaperSegmented(
            options = listOf(
                KIND_COMIC.toString() to "漫画书架",
                KIND_EBOOK.toString() to "电子书书架",
            ),
            selected = picked.toString(),
            onSelect = { picked = it.toInt() },
        )
    }
}

@Composable
internal fun rememberShelfDialogs(): ShelfDialogs =
    rememberSaveable(saver = ShelfDialogs.Saver) { ShelfDialogs() }

/** Renders whichever dialog is open. One host keeps [ShelfDialogs] and its UI in step. */
@Composable
internal fun ShelfDialogHost(
    dialogs: ShelfDialogs,
    shelfIds: List<String>,
    selection: Set<String>,
    sortedGroups: List<GroupEntity>,
    scopedGroups: List<GroupEntity>,
    scopedBooks: List<BookEntity>,
    scopeId: String?,
    kind: Int = KIND_COMIC,
    onSelection: (Set<String>) -> Unit,
    onLeaveScope: () -> Unit,
    onDeleteBooks: (List<String>) -> Unit,
    onMoveBooks: (List<String>, String?) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onCreateGroup: (String, Int) -> Unit,
    onRenameGroup: (String, String) -> Unit,
) {
    dialogs.deleteIds?.let { ids ->
        PaperConfirmation(
            "从书架移除 ${ids.size} 本书？",
            "将移除这 ${ids.size} 本书的封面与阅读记录，不会删除原始文件。",
            "移出书架", { dialogs.deleteIds = null }, {
                dialogs.deleteIds = null
                onSelection(selection - ids.toSet())
                onDeleteBooks(ids)
            }, dismissLabel = "保留", destructive = true, confirmOnLeft = true,
        )
    }
    if (dialogs.moving) {
        val ids = shelfIds.filter { it in selection }
        PaperModal("移动 ${ids.size} 本书到", { dialogs.moving = false }, actions = {
            PaperAction("取消", { dialogs.moving = false }, Modifier.fillMaxWidth())
        }) {
            PaperAction("未分组", { dialogs.moving = false; onSelection(emptySet()); onMoveBooks(ids, null) }, Modifier.fillMaxWidth())
            sortedGroups.forEach { group ->
                PaperAction(group.name, { dialogs.moving = false; onSelection(emptySet()); onMoveBooks(ids, group.id) }, Modifier.fillMaxWidth())
            }
        }
    }
    scopedGroups.firstOrNull { it.id == dialogs.groupActionsId }?.let { group ->
        PaperModal(group.name, { dialogs.groupActionsId = null }, actions = {
            PaperAction("取消", { dialogs.groupActionsId = null }, Modifier.fillMaxWidth())
        }) {
            PaperAction("重命名分组", {
                dialogs.groupActionsId = null
                dialogs.renameGroup(group.id, group.name)
            }, Modifier.fillMaxWidth())
            PaperAction("解散分组", { dialogs.groupActionsId = null; dialogs.deleteGroupId = group.id }, Modifier.fillMaxWidth())
        }
    }
    scopedGroups.firstOrNull { it.id == dialogs.deleteGroupId }?.let { group ->
        PaperConfirmation(
            "解散「${group.name}」？", "分组内的 ${scopedBooks.count { it.groupId == group.id }} 本书将回到未分组，书籍与阅读记录会保留。",
            "解散分组", { dialogs.deleteGroupId = null }, {
                dialogs.deleteGroupId = null
                if (scopeId == group.id) onLeaveScope()
                onDeleteGroup(group.id)
            },
        )
    }
    dialogs.nameEditorId?.let { id ->
        PaperModal(if (id.isEmpty()) "新建分组" else "重命名分组", { dialogs.nameEditorId = null }, actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperAction("取消", { dialogs.nameEditorId = null }, Modifier.weight(1f))
                PaperAction("保存", {
                    if (validGroupName(dialogs.nameDraft)) {
                        val name = dialogs.nameDraft.trim()
                        dialogs.nameEditorId = null
                        if (id.isEmpty()) onCreateGroup(name, kind) else onRenameGroup(id, name)
                    }
                }, Modifier.weight(1f), enabled = validGroupName(dialogs.nameDraft), primary = true)
            }
        }) {
            PaperTextField(dialogs.nameDraft, { dialogs.nameDraft = it }, "分组名称")
            if (dialogs.nameDraft.trim().length > 80) Text("名称最多 80 个字符", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
