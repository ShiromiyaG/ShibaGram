package com.shirou.shibagram.streaming

import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves byte ranges of a Telegram file while TDLib downloads it.
 *
 * TDLib downloads one range per file at a time (from an offset onwards), so the main job here is
 * deciding when to move that range:
 * - a read that is already covered (or just ahead of what is being downloaded) only waits;
 * - a read far away (a seek, or Matroska's index at the end of the file) moves the download there,
 *   and the next read back near the playback position moves it back.
 * (Prefetching the MKV tail before starting was tried and measured: it made the start slower.)
 *
 * File state comes from TDLib's UpdateFile events; reads use one shared file handle.
 */
class TelegramVideoDataProvider(
    private val telegramService: TelegramClientService,
    private val fileId: Int,
    val fileSize: Long
) : VideoStreamingServer.VideoDataProvider {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isClosed = AtomicBoolean(false)
    private val fileState = MutableStateFlow<TdApi.File?>(null)

    // Current TDLib download request (offset onwards; limit 0 = to the end)
    @Volatile private var requestedOffset = -1L
    @Volatile private var requestedLimit = 0L

    // Last range known to be on disk (avoids asking TDLib on every small read)
    @Volatile private var knownStart = 0L
    @Volatile private var knownEnd = 0L

    private val rafLock = Any()
    private var raf: RandomAccessFile? = null
    private var rafPath: String? = null

    companion object {
        private const val READ_TIMEOUT_MS = 45_000L
        private const val RECHECK_INTERVAL_MS = 1_000L
        private const val PRIORITY_PLAYBACK = 32
        /** A read this far past the download frontier still just waits for it. */
        private const val LOOKAHEAD_BYTES = 24L * 1024 * 1024
    }

    init {
        scope.launch {
            telegramService.fileUpdates
                .filter { it.id == fileId }
                .collect { fileState.value = it }
        }
        scope.launch {
            telegramService.getFileInfo(fileId)?.let { if (fileState.value == null) fileState.value = it }
            requestDownload(0, 0)
        }
    }

    private fun isCompleted() = fileState.value?.local?.isDownloadingCompleted == true

    private suspend fun requestDownload(offset: Long, limit: Long) {
        requestedOffset = offset
        requestedLimit = limit
        telegramService.startDownload(fileId, offset, limit, PRIORITY_PLAYBACK)
    }

    /** Whether a read at [offset] needs the TDLib download moved, or will be reached soon anyway. */
    private fun needsDownloadAt(offset: Long): Boolean {
        if (isCompleted()) return false
        val start = requestedOffset
        if (start < 0 || offset < start) return true
        if (requestedLimit > 0 && offset >= start + requestedLimit) return true
        val local = fileState.value?.local
        val frontier = if (local != null && local.downloadOffset == start) start + local.downloadedPrefixSize else start
        return offset > frontier + LOOKAHEAD_BYTES
    }

    private val localPath: String?
        get() = fileState.value?.local?.path?.takeIf { it.isNotEmpty() }

    /** Bytes available at [offset]. */
    private suspend fun availableAt(offset: Long): Long {
        if (localPath == null) return 0
        if (isCompleted()) return (fileSize - offset).coerceAtLeast(0)
        if (offset >= knownStart && offset < knownEnd) return knownEnd - offset
        val local = fileState.value?.local ?: return 0
        val prefixEnd = local.downloadOffset + local.downloadedPrefixSize
        if (offset >= local.downloadOffset && offset < prefixEnd) {
            knownStart = local.downloadOffset
            knownEnd = prefixEnd
            return prefixEnd - offset
        }
        // TDLib tracks every downloaded range, not just the current one
        val size = telegramService.getDownloadedPrefixSize(fileId, offset)
        if (size > 0) {
            knownStart = offset
            knownEnd = offset + size
        }
        return size
    }

    override suspend fun readBytes(offset: Long, length: Int): ByteArray? {
        if (isClosed.get() || offset >= fileSize) return null
        val result = withTimeoutOrNull(READ_TIMEOUT_MS) {
            while (!isClosed.get()) {
                val available = availableAt(offset)
                if (available > 0) return@withTimeoutOrNull readFromFile(offset, minOf(length.toLong(), available).toInt())
                if (needsDownloadAt(offset)) requestDownload(offset, 0)
                val current = fileState.value
                withTimeoutOrNull(RECHECK_INTERVAL_MS) { fileState.first { it !== current } }
            }
            null
        }
        if (result == null && !isClosed.get()) Log.w("Timeout reading file $fileId at offset $offset")
        return result
    }

    private fun readFromFile(offset: Long, length: Int): ByteArray? {
        val path = localPath ?: return null
        val actualLength = minOf(length.toLong(), fileSize - offset).toInt()
        if (actualLength <= 0) return null
        return synchronized(rafLock) {
            try {
                // TDLib moves the file when the download completes: reopen when the path changes
                if (raf == null || rafPath != path) {
                    raf?.close()
                    raf = RandomAccessFile(File(path), "r")
                    rafPath = path
                }
                val file = raf!!
                file.seek(offset)
                val buffer = ByteArray(actualLength)
                val bytesRead = file.read(buffer)
                when {
                    bytesRead <= 0 -> null
                    bytesRead < actualLength -> buffer.copyOf(bytesRead)
                    else -> buffer
                }
            } catch (e: Exception) {
                Log.e("Error reading file $fileId at offset $offset: ${e.message}")
                raf?.close()
                raf = null
                null
            }
        }
    }

    override fun getAvailableDataSize(offset: Long): Long {
        if (isCompleted()) return (fileSize - offset).coerceAtLeast(0)
        return if (offset >= knownStart && offset < knownEnd) knownEnd - offset else 0
    }

    override fun close() {
        if (isClosed.getAndSet(true)) return
        scope.cancel()
        synchronized(rafLock) {
            raf?.close()
            raf = null
        }
        telegramService.cancelDownload(fileId)
    }
}
