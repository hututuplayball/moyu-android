package com.moyu.reader

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moyu.reader.data.KIND_COMIC
import com.moyu.reader.data.KIND_EBOOK
import com.moyu.reader.ui.LocalMoyuReduceMotion
import com.moyu.reader.ui.MoyuTheme
import com.moyu.reader.ui.PaperSnackbar
import com.moyu.reader.ui.reader.ReaderScreen
import com.moyu.reader.ui.SettingsScreen
import com.moyu.reader.ui.SplashScreen
import com.moyu.reader.ui.library.LibraryScreen
import com.moyu.reader.ui.library.OpenShelfDialog
import com.moyu.reader.ui.library.clearCoverCache
import java.util.Locale

/**
 * Anchors the picker at the Downloads root. DocumentsUI otherwise reopens the last folder, and
 * backing out of a folder walks up one level first — that is why leaving it took two presses.
 */
private class OpenDownloads : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"),
        )
}

/** A file another app handed over, with the shelf it most likely belongs on. */
private class OpenRequest(val uris: List<Uri>, val name: String, val suggested: Int)

class MainActivity : ComponentActivity() {
    private var model: ReaderViewModel? = null

    /**
     * A file waiting to be imported. It is Compose state because the picker runs on the composition
     * thread while the intent can arrive from onCreate or, with the app already open, onNewIntent.
     */
    private var incoming by mutableStateOf<OpenRequest?>(null)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN (20) is above RUNNING_LOW (10) and is included here too.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            model?.trimMemory()
            clearCoverCache()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep Theme.Moyu.Starting for the whole activity so the window never swaps to a different
        // background between the system launch window and the first Compose frame.
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A rotation must not import the file a second time: only the launch that carried it does.
        if (savedInstanceState == null) incoming = openRequest(intent)
        setContent {
            val vm: ReaderViewModel = viewModel()
            model = vm
            val books by vm.books.collectAsStateWithLifecycle()
            val groups by vm.groups.collectAsStateWithLifecycle()
            val preferences by vm.preferences.collectAsStateWithLifecycle()
            val brightness by vm.brightness.collectAsStateWithLifecycle()
            val speech by vm.speech.collectAsStateWithLifecycle()
            val book by vm.reading.collectAsStateWithLifecycle()
            val screen by vm.screen.collectAsStateWithLifecycle()
            val libraryKind by vm.libraryKind.collectAsStateWithLifecycle()
            val importing by vm.importing.collectAsStateWithLifecycle()
            val initializing by vm.initializing.collectAsStateWithLifecycle()
            val unavailable by vm.unavailable.collectAsStateWithLifecycle()
            var showSplash by rememberSaveable { mutableStateOf(true) }
            // The shelf is large to compose; defer it one frame so the launch screen paints immediately.
            var shelfVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                shelfVisible = true
            }
            val snackbar = remember { SnackbarHostState() }
            val comicImporter = rememberLauncherForActivityResult(OpenDownloads()) {
                vm.importDocuments(it, KIND_COMIC)
            }
            val ebookImporter = rememberLauncherForActivityResult(OpenDownloads()) {
                vm.importDocuments(it, KIND_EBOOK)
            }
            LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
            MoyuTheme(preferences) {
                val view = LocalView.current
                val light = MaterialTheme.colorScheme.background.luminance() > .5f
                val motion = !LocalMoyuReduceMotion.current
                DisposableEffect(screen, light) {
                    if (screen != "reader") {
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.show(WindowInsetsCompat.Type.systemBars())
                        controller.isAppearanceLightStatusBars = light
                        controller.isAppearanceLightNavigationBars = light
                    }
                    onDispose { }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (shelfVisible) AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val duration = if (motion && initialState != "loading") 240 else 0
                            // Entering a screen slides in from the right; returning slides back left.
                            val direction = if (targetState == "library") -1 else 1
                            (slideInHorizontally(tween(duration)) { it / 4 * direction } + fadeIn(tween(duration)))
                                .togetherWith(slideOutHorizontally(tween(duration)) { -it / 4 * direction } + fadeOut(tween(duration)))
                        },
                        label = "navigation",
                    ) { destination ->
                        when (destination) {
                            "reader" -> book?.let { selected ->
                                ReaderScreen(selected, vm.library, preferences, brightness,
                                    vm::backToLibrary, vm::updatePosition, vm::toggleBookmark, vm::setBrightness,
                                    vm::setRightToLeft, onTextReading = vm::setTextReading,
                                    onTextPosition = vm::updateTextPosition, onView = vm::setReadView,
                                    onRelinked = { vm.clearUnavailable(selected.id) },
                                    speechPreferences = speech, onSpeech = vm::setSpeech,
                                    onSpeechBackground = vm::setSpeechBackground)
                            }
                            "settings" -> SettingsScreen(
                                preferences = preferences,
                                onBack = vm::backToLibrary,
                                onTheme = vm::setTheme,
                                onDefaultMode = vm::setDefaultMode,
                                onRightToLeft = vm::setRightToLeft,
                                onKeepScreenOn = vm::setKeepScreenOn,
                                version = BuildConfig.VERSION_NAME,
                                onPrefetchRadius = vm::setPrefetchRadius,
                            )
                            "library" -> LibraryScreen(
                                books = books,
                                importing = importing,
                                kind = libraryKind,
                                onKind = vm::setLibraryKind,
                                onImport = {
                                    comicImporter.launch(arrayOf(
                                        "application/pdf",
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/vnd.comicbook+zip",
                                        "application/x-cbz",
                                        // A comic can also arrive as a fixed-layout EPUB.
                                        "application/epub+zip",
                                        "application/octet-stream",
                                    ))
                                },
                                onImportEbook = {
                                    ebookImporter.launch(arrayOf(
                                        "application/pdf",
                                        "application/epub+zip",
                                        "application/zip",
                                        "text/plain",
                                        "application/octet-stream",
                                    ))
                                },
                                onOpen = vm::open,
                                onSettings = vm::showSettings,
                                groups = groups,
                                onCreateGroup = vm::createGroup,
                                onRenameGroup = vm::renameGroup,
                                onDeleteGroup = vm::deleteGroup,
                                onMoveBooks = vm::moveBooks,
                                onReorderBooks = vm::reorderBooks,
                                onReorderGroups = vm::reorderGroups,
                                onDeleteBooks = vm::deleteBooks,
                                unavailable = unavailable,
                            )
                            else -> Box(Modifier.fillMaxSize())
                        }
                    }
                    AnimatedVisibility(
                        visible = showSplash,
                        enter = EnterTransition.None,
                        exit = fadeOut(tween(if (motion) 280 else 0)),
                    ) {
                        SplashScreen(ready = !initializing, onFinished = { showSplash = false })
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(bottom = 20.dp)) { data ->
                        PaperSnackbar(data.visuals.message)
                    }
                    // A file opened from another app asks where it goes before anything is
                    // imported; the splash holds it back so the question never covers the launch.
                    val request = incoming
                    if (request != null && !showSplash) {
                        OpenShelfDialog(
                            fileName = request.name,
                            suggested = request.suggested,
                            onImport = { kind ->
                                incoming = null
                                vm.importDocuments(request.uris, kind)
                            },
                            onDismiss = { incoming = null },
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        // Compose lifecycle observers record the visible line synchronously in super.onStop().
        // Flushing first used to persist the previous line before the current one was reported.
        super.onStop()
        model?.onAppStopped()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: the running task takes the file instead of a second reader being launched.
        setIntent(intent)
        incoming = openRequest(intent)
    }

    /** The file an outside app asked us to open, if this launch carried one. */
    private fun openRequest(intent: Intent?): OpenRequest? {
        intent ?: return null
        @Suppress("DEPRECATION")
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return null
        return OpenRequest(listOf(uri), displayName(uri) ?: "所选文件", shelfFor(intent, uri))
    }

    /**
     * The shelf a file most likely belongs on, offered as the default in the chooser. A file
     * arrives without the tab the reader was last looking at, so the container decides: readable
     * text is a book, everything else is a comic.
     */
    private fun shelfFor(intent: Intent, uri: Uri): Int {
        val mime = intent.type ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        val name = displayName(uri)?.lowercase(Locale.ROOT).orEmpty()
        return when {
            mime == "text/plain" || name.endsWith(".txt") -> KIND_EBOOK
            mime == "application/epub+zip" || name.endsWith(".epub") -> KIND_EBOOK
            else -> KIND_COMIC
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
