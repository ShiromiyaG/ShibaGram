package com.shirou.shibagram.data.local

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory LRU cache for thumbnail ImageBitmaps.
 * Avoids re-reading and re-decoding the same image files from disk on every recomposition.
 */
object ThumbnailCache {

    private const val MAX_CACHE_SIZE = 200

    // LinkedHashMap with accessOrder=true for LRU behavior (guarded by [mutex])
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }
    private val mutex = Mutex()

    // One shared decode per path: concurrent callers await the same load instead of
    // giving up after a fixed delay (which left thumbnails permanently blank).
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Get a cached thumbnail, or load it from disk if not cached.
     * Returns null if the file doesn't exist (yet) or can't be decoded; nulls are not cached.
     */
    suspend fun getOrLoad(path: String): ImageBitmap? {
        mutex.withLock { cache[path] }?.let { return it }

        val load = inFlight.computeIfAbsent(path) { loaderScope.async { decode(path) } }
        val bitmap = load.await()
        inFlight.remove(path, load)
        if (bitmap != null) mutex.withLock { cache[path] = bitmap }
        return bitmap
    }

    private fun decode(path: String): ImageBitmap? = try {
        val file = File(path)
        if (file.exists() && file.length() > 0) Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() else null
    } catch (e: Exception) {
        null
    }
}
