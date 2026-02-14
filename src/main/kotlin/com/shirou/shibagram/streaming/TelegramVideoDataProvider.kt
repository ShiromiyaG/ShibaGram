package com.shirou.shibagram.streaming

import com.shirou.shibagram.data.remote.TelegramClientService
import org.drinkless.tdlib.TdApi
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

class TelegramVideoDataProvider(
    private val telegramService: TelegramClientService,
    private val fileId: Int,
    val fileSize: Long
) : VideoStreamingServer.VideoDataProvider {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isClosed = AtomicBoolean(false)
    
    @Volatile private var currentFile: TdApi.File? = null
    private val fileStateLock = ReentrantReadWriteLock()
    private val readLock = fileStateLock.readLock()
    private val writeLock = fileStateLock.writeLock()
    
    private val pendingDownloads = ConcurrentHashMap<String, CompletableDeferred<TdApi.File?>>()
    
    companion object {
        private const val MIN_DOWNLOAD_SIZE = 1048576L // 1MB
        private const val OPTIMAL_DOWNLOAD_SIZE = 52428800L // 50MB
        private const val SEEK_DOWNLOAD_SIZE = 2097152L // 2MB for seeks
        private const val READ_TIMEOUT_MS = 30000L
    }
    
    init {
        startBackgroundDownload()
    }
    
    private fun startBackgroundDownload() {
        scope.launch {
            try {
                // Start download from beginning with high priority
                telegramService.startDownload(fileId, 0, OPTIMAL_DOWNLOAD_SIZE, 1)
            } catch (e: Exception) {
                println("Error starting background download: ${e.message}")
            }
        }
        
        // Poll for file updates
        scope.launch {
            while (!isClosed.get()) {
                try {
                    val fileInfo = telegramService.getFileInfo(fileId)
                    if (fileInfo != null) {
                        writeLock.lock()
                        try {
                            currentFile = fileInfo
                        } finally {
                            writeLock.unlock()
                        }
                        
                        // Complete any pending downloads that now have data
                        checkPendingDownloads(fileInfo)
                        
                        if (fileInfo.local.isDownloadingCompleted) {
                            break
                        }
                    }
                } catch (e: Exception) {
                }
                delay(50)
            }
        }
    }
    
    private fun checkPendingDownloads(file: TdApi.File) {
        val local = file.local
        val downloadOffset = local.downloadOffset
        val downloadedPrefixSize = local.downloadedPrefixSize
        val availableEnd = downloadOffset + downloadedPrefixSize
        
        pendingDownloads.entries.toList().forEach { (key, deferred) ->
            val parts = key.split(":")
            if (parts.size == 2) {
                val offset = parts[0].toLong()
                val length = parts[1].toLong()
                
                if (offset < availableEnd && !deferred.isCompleted) {
                    deferred.complete(file)
                }
            }
        }
    }
    
    private fun isRangeAvailable(offset: Long, length: Int): Boolean {
        readLock.lock()
        try {
            val file = currentFile ?: return false
            val local = file.local
            val path = local.path
            
            if (path.isEmpty() || !File(path).exists()) {
                return false
            }
            
            if (local.isDownloadingCompleted) {
                return true
            }
            
            val downloadOffset = local.downloadOffset
            val downloadedPrefixSize = local.downloadedPrefixSize
            
            return offset >= downloadOffset && (offset + length) <= (downloadOffset + downloadedPrefixSize)
        } finally {
            readLock.unlock()
        }
    }
    
    private suspend fun ensureDataAvailable(offset: Long, length: Int): Boolean {
        if (isRangeAvailable(offset, length)) {
            return true
        }
        
        val key = "$offset:$length"
        val deferred = CompletableDeferred<TdApi.File?>()
        pendingDownloads[key] = deferred
        
        try {
            scope.launch {
                try {
                    telegramService.startDownload(fileId, offset, SEEK_DOWNLOAD_SIZE, 32)
                } catch (e: Exception) {
                    deferred.complete(null)
                }
            }
            
            return withTimeout(READ_TIMEOUT_MS) {
                val file = deferred.await()
                file != null && isRangeAvailable(offset, length)
            }
        } catch (e: TimeoutCancellationException) {
            println("Timeout waiting for data at offset $offset")
            return false
        } finally {
            pendingDownloads.remove(key)
        }
    }
    
    override suspend fun readBytes(offset: Long, length: Int): ByteArray? {
        if (isClosed.get()) return null
        
        // Fast path: data already available
        if (isRangeAvailable(offset, length)) {
            return readFromFile(offset, length)
        }
        
        // Wait for data to be downloaded
        val startTime = System.currentTimeMillis()
        while (!isClosed.get()) {
            if (isRangeAvailable(offset, length)) {
                return readFromFile(offset, length)
            }
            
            // Request download synchronously
            val success = telegramService.downloadFileSync(fileId, offset, SEEK_DOWNLOAD_SIZE, 32)
            if (!success) {
                if (System.currentTimeMillis() - startTime > READ_TIMEOUT_MS) {
                    println("Timeout reading at offset $offset")
                    return null
                }
            }
            
            delay(20)
        }
        
        return null
    }
    
    private fun readFromFile(offset: Long, length: Int): ByteArray? {
        readLock.lock()
        try {
            val file = currentFile ?: return null
            val path = file.local.path
            
            if (path.isEmpty()) return null
            
            val localFile = File(path)
            if (!localFile.exists()) return null
            
            val actualLength = minOf(length.toLong(), fileSize - offset).toInt()
            if (actualLength <= 0) return null
            
            return try {
                RandomAccessFile(localFile, "r").use { raf ->
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
                println("Error reading from file at offset $offset: ${e.message}")
                null
            }
        } finally {
            readLock.unlock()
        }
    }
    
    override fun getAvailableDataSize(offset: Long): Long {
        readLock.lock()
        try {
            val file = currentFile ?: return 0
            val local = file.local
            val path = local.path
            
            if (path.isEmpty() || !File(path).exists()) {
                return 0
            }
            
            if (local.isDownloadingCompleted) {
                return maxOf(0L, fileSize - offset)
            }
            
            val downloadOffset = local.downloadOffset
            val downloadedPrefixSize = local.downloadedPrefixSize
            val availableEnd = downloadOffset + downloadedPrefixSize
            
            return if (offset >= downloadOffset && offset < availableEnd) {
                availableEnd - offset
            } else {
                0
            }
        } finally {
            readLock.unlock()
        }
    }
    
    override fun close() {
        if (isClosed.getAndSet(true)) return
        scope.cancel()
        pendingDownloads.values.forEach { it.cancel() }
        pendingDownloads.clear()
        
        try {
            telegramService.cancelDownload(fileId)
        } catch (e: Exception) {
        }
    }
}
