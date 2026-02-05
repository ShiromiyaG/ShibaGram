package com.shirou.shibagram.data.local

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Intelligent video cache manager that automatically evicts old cached videos
 * when the total cache size exceeds a configurable limit.
 * 
 * Videos are evicted in LRU (Least Recently Used) order based on file last-modified time.
 * Thumbnails and small files (<1MB) are preserved since they're cheap to keep.
 */
class VideoCacheManager private constructor() {
    
    companion object {
        // Default max cache size: 2 GB
        const val DEFAULT_MAX_CACHE_SIZE_BYTES = 2L * 1024L * 1024L * 1024L
        
        // Minimum file size to consider for eviction (skip thumbnails and small files)
        private const val MIN_EVICTION_FILE_SIZE = 1L * 1024L * 1024L // 1 MB
        
        // Video file extensions to manage
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg",
            "ts", "m2ts", "vob", "ogv"
        )
        
        // Check interval: every 5 minutes
        private const val CHECK_INTERVAL_MS = 5L * 60L * 1000L
        
        @Volatile
        private var instance: VideoCacheManager? = null
        
        fun getInstance(): VideoCacheManager {
            return instance ?: synchronized(this) {
                instance ?: VideoCacheManager().also { instance = it }
            }
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    
    // Configurable max cache size in bytes
    var maxCacheSizeBytes: Long = DEFAULT_MAX_CACHE_SIZE_BYTES
        set(value) {
            field = value.coerceAtLeast(100L * 1024L * 1024L) // Minimum 100 MB
        }
    
    // Cache directories to manage
    private val cacheDirectories: List<File>
        get() = listOf(
            // TDLib downloads directory (main video cache)
            File(Paths.get(System.getProperty("user.home"), "Downloads", "ShibaGram").toString()),
            // Session downloads
            File(Paths.get(System.getProperty("user.home"), ".ShibaGram", "session", "downloads").toString()),
            // General cache
            File(Paths.get(System.getProperty("user.home"), ".ShibaGram", "cache").toString())
        )
    
    /**
     * Data about a cached file for eviction decisions.
     */
    private data class CachedFileInfo(
        val file: File,
        val size: Long,
        val lastModified: Long,
        val isVideo: Boolean
    )
    
    /**
     * Start the periodic cache monitoring job.
     */
    fun startMonitoring() {
        if (isRunning.getAndSet(true)) return
        
        scope.launch {
            while (isActive) {
                try {
                    enforceLimit()
                } catch (e: Exception) {
                    println("Cache cleanup error: ${e.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
        println("VideoCacheManager: Started monitoring (max ${maxCacheSizeBytes / 1024 / 1024} MB)")
    }
    
    /**
     * Stop the periodic cache monitoring.
     */
    fun stopMonitoring() {
        isRunning.set(false)
        scope.coroutineContext.cancelChildren()
    }
    
    /**
     * Trigger a cache check immediately (e.g., after a video finishes downloading).
     */
    fun triggerCleanup() {
        scope.launch {
            try {
                enforceLimit()
            } catch (e: Exception) {
                println("Cache cleanup error: ${e.message}")
            }
        }
    }
    
    /**
     * Get total cache size in bytes across all managed directories.
     */
    fun getTotalCacheSize(): Long {
        return cacheDirectories.sumOf { dir ->
            if (dir.exists()) calculateDirectorySize(dir) else 0L
        }
    }
    
    /**
     * Get human-readable cache size string.
     */
    fun getFormattedCacheSize(): String {
        return formatSize(getTotalCacheSize())
    }
    
    /**
     * Get human-readable max cache size string.
     */
    fun getFormattedMaxCacheSize(): String {
        return formatSize(maxCacheSizeBytes)
    }
    
    /**
     * Enforce the cache size limit by evicting oldest video files.
     * Returns the number of files evicted.
     */
    suspend fun enforceLimit(): Int = withContext(Dispatchers.IO) {
        var totalSize = getTotalCacheSize()
        
        if (totalSize <= maxCacheSizeBytes) {
            return@withContext 0
        }
        
        println("VideoCacheManager: Cache size ${formatSize(totalSize)} exceeds limit ${formatSize(maxCacheSizeBytes)}, cleaning up...")
        
        // Collect all video files from all cache directories
        val allFiles = mutableListOf<CachedFileInfo>()
        for (dir in cacheDirectories) {
            if (dir.exists()) {
                collectFiles(dir, allFiles)
            }
        }
        
        // Sort by last modified time (oldest first = evict first)
        val evictionCandidates = allFiles
            .filter { it.isVideo && it.size >= MIN_EVICTION_FILE_SIZE }
            .sortedBy { it.lastModified }
        
        var evictedCount = 0
        val targetSize = (maxCacheSizeBytes * 0.85).toLong() // Clean to 85% of limit for headroom
        
        for (candidate in evictionCandidates) {
            if (totalSize <= targetSize) break
            
            try {
                if (candidate.file.exists() && candidate.file.delete()) {
                    totalSize -= candidate.size
                    evictedCount++
                    println("VideoCacheManager: Evicted ${candidate.file.name} (${formatSize(candidate.size)})")
                    
                    // Also try to delete empty parent directories
                    val parent = candidate.file.parentFile
                    if (parent != null && parent.isDirectory && parent.listFiles()?.isEmpty() == true) {
                        parent.delete()
                    }
                }
            } catch (e: Exception) {
                println("VideoCacheManager: Failed to evict ${candidate.file.name}: ${e.message}")
            }
        }
        
        if (evictedCount > 0) {
            println("VideoCacheManager: Evicted $evictedCount files, new cache size: ${formatSize(totalSize)}")
        }
        
        evictedCount
    }
    
    /**
     * Clear all cached videos (manual cache clear).
     */
    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        for (dir in cacheDirectories) {
            try {
                if (dir.exists()) {
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
            } catch (e: Exception) {
                println("VideoCacheManager: Error clearing ${dir.absolutePath}: ${e.message}")
            }
        }
        println("VideoCacheManager: All cache cleared")
    }
    
    private fun collectFiles(directory: File, result: MutableList<CachedFileInfo>) {
        val files = directory.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                collectFiles(file, result)
            } else {
                val extension = file.extension.lowercase()
                val isVideo = extension in VIDEO_EXTENSIONS
                result.add(CachedFileInfo(
                    file = file,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isVideo = isVideo
                ))
            }
        }
    }
    
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        val files = directory.listFiles() ?: return 0L
        for (file in files) {
            size += if (file.isDirectory) {
                calculateDirectorySize(file)
            } else {
                file.length()
            }
        }
        return size
    }
    
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val log10 = kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)
        val index = log10.toInt().coerceAtMost(4)
        val value = bytes / Math.pow(1024.0, index.toDouble())
        return String.format("%.1f %s", value, units[index])
    }
}
