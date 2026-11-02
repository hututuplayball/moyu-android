package com.moyu.reader.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class BackupStatus(
    val location: String = BackupRepository.LOCATION,
    val enabled: Boolean = true,
    val lastBackupAt: Long = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val pendingRestore: Boolean = false,
)

/** Metadata only, written to the app's private files directory. */
class BackupRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dao = ReaderDatabase.getInstance(appContext).libraryDao()
    private val preferences = PreferencesRepository(appContext)
    private val mutableStatus = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = mutableStatus.asStateFlow()
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
        recordFailure(error)
    })
    private var configuration = BackupConfiguration()
    private var lastAttemptElapsed = -AUTO_INTERVAL_MS
    private var lastWritten: MetadataSnapshot? = null
    private val storage: Storage = PrivateStorage()

    init {
        scope.launch {
            val loaded = preferences.backupConfiguration.retryWhen { error, _ ->
                if (error is CancellationException) throw error
                recordFailure(error)
                if (error is IOException) { delay(AUTO_INTERVAL_MS); true } else false
            }.first()
            mutex.withLock {
                // Backups are not optional: a stored "off" from an older build is upgraded silently.
                configuration = if (loaded.enabled) loaded else loaded.copy(enabled = true)
                if (!loaded.enabled) runCatching { preferences.setBackupConfiguration(configuration) }
                val elapsed = (System.currentTimeMillis() - configuration.lastBackupAt).coerceIn(0, AUTO_INTERVAL_MS)
                lastAttemptElapsed = SystemClock.elapsedRealtime() - elapsed
                publishConfiguration()
                // A failed read is not an empty configuration, nor successful initialization.
                ready.complete(Unit)
            }
            // A surviving install can recover a damaged database from its private snapshots.
            autoRestoreOnLaunch()
        }
        scope.launch {
            ready.await()
            // metadata excludes backup configuration, so recording a successful backup cannot
            // trigger itself. Conflation/interval limiting live in the worker, not a sliding debounce.
            combine(dao.observeBackupChanges(), preferences.metadata) { _, _ -> Unit }
                .retryWhen { error, _ ->
                    if (error is CancellationException) throw error
                    recordFailure(error)
                    delay(AUTO_INTERVAL_MS)
                    true
                }
                .collect { requests.trySend(Unit) }
        }
        scope.launch {
            ready.await()
            for (request in requests) {
                if (!status.value.enabled || status.value.pendingRestore) continue
                // Coalesce frequent page/offset updates; never cancel a write already in progress.
                val wait = mutex.withLock {
                    AUTO_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastAttemptElapsed)
                }
                if (wait > 0) delay(wait)
                requests.tryReceive() // Consume the one conflated invalidation, without a draining loop.
                try {
                    operation {
                        if (!configuration.enabled || configuration.pendingRestore) return@operation
                        // A manual write may have happened while this worker was waiting.
                        if (SystemClock.elapsedRealtime() - lastAttemptElapsed < AUTO_INTERVAL_MS) requests.trySend(Unit)
                        else backupLocked(false)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // operation already records the error. A later metadata change can retry.
                }
            }
        }
    }

    suspend fun backupNow() = operation { backupLocked(true) }

    /** Returns the number of book metadata entries in the chosen valid snapshot, including unmatched ones. */
    suspend fun restoreBackup(): Int = operation {
        val snapshot = newestValid()
            ?: throw LibraryException("未找到完整有效的墨屿记录备份，已有文件未被修改。")
        BackupCodec.validateForRestore(snapshot)
        // Durable pause survives process death between Room merge and DataStore merge.
        saveConfiguration(configuration.copy(pendingRestore = true))
        val count = dao.mergeSnapshot(snapshot)
        preferences.mergeRestored(snapshot.preferences, snapshot.preferencesUpdatedAt)
        saveConfiguration(configuration.copy(pendingRestore = false,
            lastBackupAt = maxOf(configuration.lastBackupAt, snapshot.createdAt)))
        preferences.markShelfInitialized()
        lastWritten = null
        mutableStatus.update { it.copy(error = null) }
        requests.trySend(Unit)
        count
    }

    private suspend fun autoRestoreOnLaunch() {
        try {
            operation {
                // Only a fresh install may adopt the backup. An emptied shelf on an install that
                // already ran is a deliberate deletion, and restoring it would make books reappear.
                if (preferences.shelfInitialized()) return@operation
                autoRestoreLocked()
                preferences.markShelfInitialized()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A permission or storage failure must not overwrite the backup it could not read.
            if (dao.metadataCount() == 0) {
                try { mutex.withLock { saveConfiguration(configuration.copy(pendingRestore = true)) } }
                catch (_: Exception) { }
            }
        }
    }

    /** Merge a snapshot only into an empty library, so an in-use library is never overwritten. */
    private suspend fun autoRestoreLocked() {
        if (!configuration.enabled || dao.metadataCount() != 0) return
        val snapshot = newestValid() ?: return
        saveConfiguration(configuration.copy(pendingRestore = true))
        dao.mergeSnapshot(snapshot)
        preferences.mergeRestored(snapshot.preferences, snapshot.preferencesUpdatedAt)
        saveConfiguration(configuration.copy(pendingRestore = false,
            lastBackupAt = maxOf(configuration.lastBackupAt, snapshot.createdAt)))
        lastWritten = null
        mutableStatus.update { it.copy(error = null) }
        requests.trySend(Unit)
    }

    private suspend fun backupLocked(force: Boolean) {
        if (!configuration.enabled) throw LibraryException("自动备份已关闭。")
        if (configuration.pendingRestore) throw LibraryException(RESTORE_FIRST)
        lastAttemptElapsed = SystemClock.elapsedRealtime()
        val prefs = preferences.metadata.first()
        val snapshot = dao.snapshot(System.currentTimeMillis(), prefs.value, prefs.updatedAt)
        // Never replace a real backup with the empty shelf of a fresh, unrestored install.
        if (!force && snapshot.books.isEmpty() && snapshot.groups.isEmpty()) return
        if (!force && lastWritten == snapshot.copy(createdAt = 0)) return
        val bytes = BackupCodec.encode(snapshot)
        // Refuse further growth if a write repeatedly succeeds but cleanup does not.
        var older = storage.list()
        if (older.size >= MAX_OWNED_SNAPSHOTS) {
            val valid = older.sortedByDescending { it.timestamp }.firstOrNull { readValid(it) != null }
            if (valid != null) prune(older.filter { it != valid && it.timestamp <= valid.timestamp })
            older = storage.list()
            require(older.size < MAX_OWNED_SNAPSHOTS) { "旧备份无法清理，已暂停写入以避免占用过多空间。" }
        }
        val name = "moyu-metadata-v1-${snapshot.createdAt}-${UUID.randomUUID().toString().replace("-", "")}.json"
        val document = storage.write(name, bytes, snapshot.createdAt)
        var verified = false
        try {
            val reread = readValid(document) ?: throw IOException("无法校验刚写入的备份。")
            require(reread == snapshot) { "备份写入后的校验失败。" }
            verified = true
            saveConfiguration(configuration.copy(lastBackupAt = snapshot.createdAt))
            lastWritten = snapshot.copy(createdAt = 0)
            // Only after a complete read-back do we remove our older snapshots. Other files are untouched.
            prune(older.filter { it.timestamp <= snapshot.createdAt })
        } finally {
            if (!verified) {
                try { storage.delete(document) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Previous good snapshots remain. */ }
            }
        }
    }

    private fun prune(older: List<SnapshotDocument>) {
        var retained = 1 // the verified new snapshot
        var failed = false
        older.sortedByDescending { it.timestamp }.forEach { candidate ->
            if (retained < RETAIN_SNAPSHOTS && readValid(candidate) != null) {
                retained++
            } else {
                try {
                    if (!storage.delete(candidate)) failed = true
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { failed = true }
            }
        }
        if (failed) mutableStatus.update { it.copy(error = "备份已保存，但部分旧备份无法清理。") }
    }

    private fun newestValid(): MetadataSnapshot? = storage.list()
        .sortedByDescending { it.timestamp }
        .firstNotNullOfOrNull { readValid(it) }

    private fun readValid(document: SnapshotDocument): MetadataSnapshot? = try {
        val size = document.size
        if (size != null && size > BackupCodec.MAX_BYTES) null
        else storage.open(document)?.use { BackupCodec.read(it) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null // Try the next older snapshot on truncation, invalid metadata, or an unreadable entry.
    }

    // Called only from operations holding mutex; failed persistence leaves the last known config intact.
    private suspend fun saveConfiguration(value: BackupConfiguration) {
        preferences.setBackupConfiguration(value)
        configuration = value
        publishConfiguration()
    }

    private fun publishConfiguration() {
        mutableStatus.update { it.copy(location = LOCATION, enabled = configuration.enabled,
            lastBackupAt = configuration.lastBackupAt, pendingRestore = configuration.pendingRestore,
            error = if (configuration.pendingRestore) RESTORE_FIRST else it.error) }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock {
            mutableStatus.update { it.copy(busy = true, error = if (configuration.pendingRestore) RESTORE_FIRST else null) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordFailure(error)
                throw LibraryException(mutableStatus.value.error ?: "记录备份失败。")
            } finally {
                mutableStatus.update { it.copy(busy = false) }
            }
        }
    }

    private fun recordFailure(error: Throwable) {
        val message = when (error) {
            is SecurityException -> "记录备份失败，请检查应用存储空间。"
            is LibraryException, is IllegalArgumentException -> error.message ?: "备份数据无效。"
            else -> "记录备份失败，请检查应用私有存储空间。"
        }
        mutableStatus.update { it.copy(error = message) }
    }

    private data class SnapshotDocument(val file: File, val timestamp: Long, val size: Long?)

    private interface Storage {
        fun list(): List<SnapshotDocument>
        fun write(name: String, bytes: ByteArray, timestamp: Long): SnapshotDocument
        fun open(document: SnapshotDocument): InputStream?
        fun delete(document: SnapshotDocument): Boolean
    }

    /** App-private snapshots: readable by this app only, so book titles and history stay on device. */
    private inner class PrivateStorage : Storage {
        private val directory = File(appContext.filesDir, "backups")

        override fun list(): List<SnapshotDocument> {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("无法创建应用私有备份目录。")
            }
            val files = directory.listFiles() ?: throw IOException("无法读取应用私有备份目录。")
            require(files.size <= MAX_DIRECTORY_ENTRIES) { "备份文件过多，已暂停扫描。" }
            return files.mapNotNull { file ->
                val match = SNAPSHOT_NAME.matchEntire(file.name) ?: return@mapNotNull null
                val timestamp = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                SnapshotDocument(file, timestamp, file.length())
            }
        }

        override fun write(name: String, bytes: ByteArray, timestamp: Long): SnapshotDocument {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("无法创建应用私有备份目录。")
            }
            val file = File(directory, name)
            file.outputStream().use { output ->
                output.write(bytes)
                output.flush()
            }
            return SnapshotDocument(file, timestamp, bytes.size.toLong())
        }

        override fun open(document: SnapshotDocument): InputStream? = document.file.inputStream()

        override fun delete(document: SnapshotDocument): Boolean = document.file.delete()
    }

    companion object {
        const val LOCATION = "应用私有目录"
        private const val FILE_PREFIX = "moyu-metadata-v1-"
        private const val RESTORE_FIRST = "发现已有记录备份，请先恢复"
        private const val AUTO_INTERVAL_MS = 30_000L
        private const val RETAIN_SNAPSHOTS = 3
        private const val MAX_OWNED_SNAPSHOTS = 6
        private const val MAX_DIRECTORY_ENTRIES = 20_000
        private val SNAPSHOT_NAME = Regex("moyu-metadata-v1-([0-9]{1,15})-([0-9a-f]{32})\\.json")
        @Volatile private var instance: BackupRepository? = null

        fun getInstance(context: Context): BackupRepository = instance ?: synchronized(this) {
            instance ?: BackupRepository(context.applicationContext).also { instance = it }
        }
    }
}
