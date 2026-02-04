package com.shirou.shibagram.streaming

import com.shirou.shibagram.data.remote.TelegramClientService
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Data provider that streams video data from Telegram progressively.
 * Reads directly from the file being downloaded by TDLib.
 */
class TelegramVideoDataProvider(
    private val telegramService: TelegramClientService,
    private val fileId: Int,
    val fileSize: Long
) : VideoStreamingServer.VideoDataProvider {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadedSize = AtomicLong(0)
    private val isDownloading = AtomicBoolean(false)
    private val isClosed = AtomicBoolean(false)
    
    private var localFilePath: String = ""
    private var downloadJob: Job? = null
    
    companion object {
        const val MIN_BUFFER_SIZE = 2 * 1024 * 1024L // 2MB minimum buffer before playback (needed for AV1/MKV headers)
        const val READ_TIMEOUT_MS = 120000L // 120 seconds timeout
        const val POLL_INTERVAL_MS = 50L
    }
    
    init {
        startDownload()
    }
    
    private fun startDownload() {
        if (isDownloading.getAndSet(true)) return
        
        downloadJob = scope.launch {
            try {
                // Start download and poll for progress
                telegramService.downloadFileProgressively(fileId) { _, _, filePath ->
                    if (isClosed.get()) return@downloadFileProgressively
                    localFilePath = filePath
                }
            } catch (e: Exception) {
                if (!isClosed.get()) {
                    println("Download error: ${e.message}")
                }
            }
        }
        
        // Also start a polling job to track download progress
        scope.launch {
            while (!isClosed.get()) {
                try {
                    val fileInfo = telegramService.getFileInfo(fileId)
                    if (fileInfo != null) {
                        val local = fileInfo.local
                        val downloadedBytes = local?.downloadedSize ?: 0
                        downloadedSize.set(downloadedBytes)
                        if (localFilePath.isEmpty()) {
                            localFilePath = local?.path ?: ""
                        }
                        if (local?.isDownloadingCompleted == true) {
                            downloadedSize.set(fileSize)
                            break
                        }
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    override suspend fun readBytes(offset: Long, length: Int): ByteArray? {
        if (isClosed.get()) return null
        if (localFilePath.isEmpty()) {
            // Wait for file path to become available
            val startTime = System.currentTimeMillis()
            while (localFilePath.isEmpty() && !isClosed.get()) {
                if (System.currentTimeMillis() - startTime > READ_TIMEOUT_MS) {
                    println("Timeout waiting for file path")
                    return null
                }
                delay(POLL_INTERVAL_MS)
            }
        }
        
        // Wait for data to be available with timeout
        val startTime = System.currentTimeMillis()
        val neededOffset = offset + minOf(length.toLong(), 4096L) // Need at least some data
        
        while (downloadedSize.get() < neededOffset && downloadedSize.get() < fileSize) {
            if (isClosed.get()) return null
            if (System.currentTimeMillis() - startTime > READ_TIMEOUT_MS) {
                println("Timeout waiting for data at offset $offset (downloaded: ${downloadedSize.get()})")
                return null
            }
            delay(POLL_INTERVAL_MS)
        }
        
        // Read data from file
        return try {
            val file = File(localFilePath)
            if (!file.exists()) return null
            
            val actualLength = minOf(length.toLong(), fileSize - offset, downloadedSize.get() - offset).toInt()
            if (actualLength <= 0) return null
            
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(actualLength)
                val bytesRead = raf.read(buffer)
                if (bytesRead > 0) {
                    if (bytesRead < actualLength) buffer.copyOf(bytesRead) else buffer
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            println("Error reading from file: ${e.message}")
            null
        }
    }
    
    override fun getAvailableDataSize(offset: Long): Long {
        val downloaded = downloadedSize.get()
        return if (downloaded > offset) downloaded - offset else 0
    }
    
    fun isBufferReady(): Boolean {
        return downloadedSize.get() >= MIN_BUFFER_SIZE || downloadedSize.get() >= fileSize
    }
    
    suspend fun waitForBuffer(): Boolean {
        val startTime = System.currentTimeMillis()
        val neededKB = MIN_BUFFER_SIZE / 1024
        var lastPrint = 0L
        while (!isBufferReady()) {
            if (isClosed.get()) return false
            if (System.currentTimeMillis() - startTime > READ_TIMEOUT_MS) {
                println("Buffer timeout - downloaded: ${downloadedSize.get() / 1024}KB, needed: ${neededKB}KB")
                return false
            }
            delay(100)
            // Only print every 500ms to reduce spam
            if (System.currentTimeMillis() - lastPrint > 500) {
                val downloadedKB = downloadedSize.get() / 1024
                println("Buffering: ${downloadedKB}KB / ${neededKB}KB")
                lastPrint = System.currentTimeMillis()
            }
        }
        return true
    }
    
    override fun close() {
        if (isClosed.getAndSet(true)) return
        downloadJob?.cancel()
        scope.cancel()
    }
}
