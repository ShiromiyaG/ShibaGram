package com.shirou.shibagram.data.local

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory LRU cache for thumbnail ImageBitmaps.
 * Avoids re-reading and re-decoding the same image files from disk on every recomposition.
 * 
 * Thread-safe and coroutine-friendly.
 */
object ThumbnailCache {
    
    private const val MAX_CACHE_SIZE = 200
    
    // Using a LinkedHashMap with accessOrder=true for LRU behavior
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    
    private val mutex = Mutex()
    
    // Track paths currently being loaded to avoid duplicate work
    private val loadingPaths = ConcurrentHashMap<String, Boolean>()
    
    /**
     * Get a cached thumbnail, or load it from disk if not cached.
     * Returns null if the file doesn't exist or can't be decoded.
     */
    suspend fun getOrLoad(path: String): ImageBitmap? {
        // Fast path: check cache without locking
        mutex.withLock {
            cache[path]?.let { return it }
        }
        
        // Avoid duplicate concurrent loads of the same path
        if (loadingPaths.putIfAbsent(path, true) != null) {
            // Another coroutine is loading this; wait briefly and check cache again
            kotlinx.coroutines.delay(50)
            mutex.withLock {
                return cache[path]
            }
        }
        
        try {
            // Load from disk on IO dispatcher
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    if (file.exists() && file.length() > 0) {
                        val bytes = file.readBytes()
                        Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            
            // Store in cache
            if (bitmap != null) {
                mutex.withLock {
                    cache[path] = bitmap
                }
            }
            
            return bitmap
        } finally {
            loadingPaths.remove(path)
        }
    }
    
    /**
     * Invalidate a specific cached entry.
     */
    suspend fun invalidate(path: String) {
        mutex.withLock {
            cache.remove(path)
        }
    }
    
    /**
     * Clear entire cache (e.g., on low memory).
     */
    suspend fun clearAll() {
        mutex.withLock {
            cache.clear()
        }
    }
}
