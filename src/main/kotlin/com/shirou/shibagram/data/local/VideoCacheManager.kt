package com.shirou.shibagram.data.local

import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.util.Log
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps TDLib's media cache under a configurable size.
 *
 * All sizing and eviction goes through TDLib (GetStorageStatisticsFast / OptimizeStorage):
 * TDLib evicts least-recently-used media and keeps its file database consistent. Deleting files
 * by hand (as before) left TDLib pointing at missing files and also removed thumbnails, photos
 * and anything else in the downloads folder.
 */
class VideoCacheManager private constructor(
    private val telegram: TelegramClientService = TelegramClientService.getInstance()
) {

    companion object {
        const val DEFAULT_MAX_CACHE_SIZE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MIN_CACHE_SIZE_BYTES = 100L * 1024L * 1024L
        private const val CHECK_INTERVAL_MS = 5L * 60L * 1000L

        @Volatile
        private var instance: VideoCacheManager? = null

        fun getInstance(): VideoCacheManager {
            return instance ?: synchronized(this) {
                instance ?: VideoCacheManager().also { instance = it }
            }
        }

        fun formatSize(bytes: Long): String = com.shirou.shibagram.domain.model.formatBytes(bytes)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    @Volatile
    var maxCacheSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES
        set(value) {
            field = value.coerceAtLeast(MIN_CACHE_SIZE_BYTES)
        }

    /** Start periodic enforcement of the size limit. */
    fun startMonitoring() {
        if (isRunning.getAndSet(true)) return
        scope.launch {
            while (isActive) {
                enforceLimitSafely()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /** Enforce the limit now (e.g. after a video stops). */
    fun triggerCleanup() {
        scope.launch { enforceLimitSafely() }
    }

    private suspend fun enforceLimitSafely() {
        try {
            enforceLimit()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Cache cleanup error: ${e.message}")
        }
    }

    /** Ask TDLib to evict old media if the cache is over the limit. */
    suspend fun enforceLimit() {
        val size = telegram.getFilesSize() ?: return
        if (size <= maxCacheSizeBytes) return
        // Clean to 85% of the limit for headroom
        val target = (maxCacheSizeBytes * 0.85).toLong()
        Log.i("Cache ${formatSize(size)} exceeds ${formatSize(maxCacheSizeBytes)}, trimming to ${formatSize(target)}")
        telegram.optimizeStorage(target)
    }

    /** Size of TDLib's files on disk, formatted ("Unknown" while TDLib isn't ready). */
    suspend fun getFormattedCacheSize(): String =
        telegram.getFilesSize()?.let(::formatSize) ?: "Unknown"

    /** Delete all cached media (videos/documents); thumbnails and photos are kept. */
    suspend fun clearAllCache() {
        if (!telegram.optimizeStorage(0)) Log.w("Clearing cache failed")
    }
}
