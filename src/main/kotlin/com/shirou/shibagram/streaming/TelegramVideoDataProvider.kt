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
 * File state comes from TDLib's UpdateFile events (no polling). When the player asks for a range
 * that isn't local yet, a download starting at that offset is requested once and the read waits
 * for the next file update, with a single overall deadline.
 */
class TelegramVideoDataProvider(
    private val telegramService: TelegramClientService,
    private val fileId: Int,
    val fileSize: Long
) : VideoStreamingServer.VideoDataProvider {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isClosed = AtomicBoolean(false)

    private val fileState = MutableStateFlow<TdApi.File?>(null)

    // Offset of the last download we asked TDLib for, to avoid re-requesting on every read
    @Volatile private var requestedOffset = -1L

    companion object {
        private const val READ_TIMEOUT_MS = 30_000L
        private const val RECHECK_INTERVAL_MS = 1_000L
        private const val PRIORITY_PLAYBACK = 32
    }

    init {
        scope.launch {
            telegramService.fileUpdates
                .filter { it.id == fileId }
                .collect { fileState.value = it }
        }
        scope.launch {
            telegramService.getFileInfo(fileId)?.let { if (fileState.value == null) fileState.value = it }
            requestDownload(0)
        }
    }

    private suspend fun requestDownload(offset: Long) {
        requestedOffset = offset
        // limit = 0: download from offset to the end of the file
        telegramService.startDownload(fileId, offset, 0, PRIORITY_PLAYBACK)
    }

    private val localPath: String?
        get() = fileState.value?.local?.path?.takeIf { it.isNotEmpty() && File(it).exists() }

    /** Bytes available at [offset], asking TDLib for exact ranges (downloads may not start at 0). */
    private suspend fun availableAt(offset: Long): Long {
        val local = fileState.value?.local ?: return 0
        if (localPath == null) return 0
        if (local.isDownloadingCompleted) return (fileSize - offset).coerceAtLeast(0)
        val prefixEnd = local.downloadOffset + local.downloadedPrefixSize
        if (offset >= local.downloadOffset && offset < prefixEnd) return prefixEnd - offset
        return telegramService.getDownloadedPrefixSize(fileId, offset)
    }

    override suspend fun readBytes(offset: Long, length: Int): ByteArray? {
        if (isClosed.get() || offset >= fileSize) return null
        val result = withTimeoutOrNull(READ_TIMEOUT_MS) {
            while (!isClosed.get()) {
                val available = availableAt(offset)
                if (available > 0) return@withTimeoutOrNull readFromFile(offset, minOf(length.toLong(), available).toInt())
                if (requestedOffset != offset) requestDownload(offset)
                // Wait for the next file update (or re-check periodically)
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
        return try {
            RandomAccessFile(path, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(actualLength)
                val bytesRead = raf.read(buffer)
                when {
                    bytesRead <= 0 -> null
                    bytesRead < actualLength -> buffer.copyOf(bytesRead)
                    else -> buffer
                }
            }
        } catch (e: Exception) {
            Log.e("Error reading file $fileId at offset $offset: ${e.message}")
            null
        }
    }

    override fun getAvailableDataSize(offset: Long): Long {
        val local = fileState.value?.local ?: return 0
        if (localPath == null) return 0
        if (local.isDownloadingCompleted) return (fileSize - offset).coerceAtLeast(0)
        val prefixEnd = local.downloadOffset + local.downloadedPrefixSize
        return if (offset >= local.downloadOffset && offset < prefixEnd) prefixEnd - offset else 0
    }

    override fun close() {
        if (isClosed.getAndSet(true)) return
        scope.cancel()
        telegramService.cancelDownload(fileId)
    }
}
