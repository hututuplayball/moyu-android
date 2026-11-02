package com.moyu.reader

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.moyu.reader.data.BackupRepository
import com.moyu.reader.data.BookEntity
import com.moyu.reader.data.LibraryRepository
import com.moyu.reader.data.PreferencesRepository
import com.moyu.reader.data.ReaderDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StartupTest {
    private lateinit var app: Application
    private lateinit var settings: PreferencesRepository
    private lateinit var library: LibraryRepository
    private val stores = mutableListOf<ViewModelStore>()
    private val id = "a".repeat(64)

    @Before fun prepare() {
        app = ApplicationProvider.getApplicationContext()
        settings = PreferencesRepository(app)
        library = LibraryRepository(app)
        runBlocking {
            withContext(Dispatchers.IO) { ReaderDatabase.getInstance(app).clearAllTables() }
            settings.setLastReadingBook(null)
            ReaderDatabase.getInstance(app).libraryDao().insertBook(
                BookEntity(id, "连续阅读测试", "test.pdf", 402, currentPage = 118, pageOffset = 346, mode = "vertical"),
            )
        }
        val source = File(File(app.filesDir, "books"), "$id.pdf")
        source.parentFile!!.mkdirs()
        source.writeText("startup tests do not render PDF")
    }

    @After fun dispose() {
        stores.forEach { it.clear() }
        shadowOf(Looper.getMainLooper()).idle()
        val writerField = ReaderViewModel::class.java.getDeclaredField("progressWriter").apply { isAccessible = true }
        val writer = writerField.get(null) as CoroutineScope
        val backups = BackupRepository.getInstance(app)
        val scopeField = BackupRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val backupScope = scopeField.get(backups) as CoroutineScope
        runBlocking {
            withTimeout(10_000) {
                writer.coroutineContext[Job]!!.children.toList().joinAll()
                backupScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
        // Robolectric resets native SQLite between tests, unlike a ViewModel restart within a test.
        ReaderDatabase.getInstance(app).close()
        ReaderDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        BackupRepository::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        File(File(app.filesDir, "books"), "$id.pdf").delete()
    }

    private fun model(state: SavedStateHandle = SavedStateHandle()): ReaderViewModel {
        val store = ViewModelStore().also { stores += it }
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(ReaderViewModel(app, state))!!
        })[ReaderViewModel::class.java]
        await { !vm.initializing.value }
        assertFalse(vm.initializing.value)
        return vm
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        org.junit.Assert.assertTrue("State change did not finish", condition())
    }

    @Test fun coldStartRestoresReadingBookAndOffset() {
        runBlocking { settings.setLastReadingBook(id) }
        val vm = model()
        assertEquals("reader", vm.screen.value)
        assertEquals(id, vm.reading.value?.id)
        assertEquals(118, vm.reading.value?.currentPage)
        assertEquals(346, vm.reading.value?.pageOffset)
    }

    @Test fun returningToLibraryPersistsLibraryDestination() {
        runBlocking { settings.setLastReadingBook(id) }
        model().backToLibrary()
        val restarted = model()
        assertEquals("library", restarted.screen.value)
        assertNull(restarted.reading.value)
    }

    @Test fun openingBookPersistsDestinationWithoutWaitingForScroll() {
        val vm = model()
        vm.open(runBlocking { library.getBook(id)!! })
        await { vm.screen.value == "reader" }
        assertEquals("reader", model().screen.value)
    }

    @Test fun missingPrivatePdfReturnsToLibrary() {
        runBlocking { settings.setLastReadingBook(id) }
        File(File(app.filesDir, "books"), "$id.pdf").delete()
        val vm = model()
        assertEquals("library", vm.screen.value)
        assertNull(vm.reading.value)
        // Startup reads this value cold, so assert the durable record rather than a hot-flow emission.
        val deadline = System.nanoTime() + 10_000_000_000L
        while (runBlocking { settings.preferences.first().lastReadingBookId } != null &&
            System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertNull(runBlocking { settings.preferences.first().lastReadingBookId })
        assertEquals("library", model().screen.value)
    }

    @Test fun savedSettingsScreenIsNotReplacedByLastBook() {
        runBlocking { settings.setLastReadingBook(id) }
        val vm = model(SavedStateHandle(mapOf("screen" to "settings")))
        assertEquals("settings", vm.screen.value)
        assertNull(vm.reading.value)
    }

    @Test fun committedProgressSurvivesViewModelDisposal() {
        val vm = model()
        vm.open(runBlocking { library.getBook(id)!! })
        await { vm.screen.value == "reader" }
        vm.updatePosition(201, 512, "vertical")
        vm.flushProgress()
        stores.last().clear()
        val restarted = model()
        assertEquals(201, restarted.reading.value?.currentPage)
        assertEquals(512, restarted.reading.value?.pageOffset)
    }

    @Test fun quicklyReopeningStaleShelfEntryKeepsNewerProgress() {
        val stale = runBlocking { library.getBook(id)!! }
        val vm = model()
        vm.open(stale)
        await { vm.screen.value == "reader" }
        vm.updatePosition(211, 612, "vertical")
        vm.backToLibrary()
        vm.open(stale)
        await { vm.screen.value == "reader" }
        assertEquals(211, vm.reading.value?.currentPage)
        assertEquals(612, vm.reading.value?.pageOffset)
    }
}
