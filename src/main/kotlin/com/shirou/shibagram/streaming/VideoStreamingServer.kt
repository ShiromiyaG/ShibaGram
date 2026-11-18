package com.shirou.shibagram.streaming

import fi.iki.elonen.NanoHTTPD
import com.shirou.shibagram.util.Log
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local HTTP server for streaming Telegram videos to VLC.
 * Ported from Android VlcHttpServer.
 */
class VideoStreamingServer private constructor() : NanoHTTPD(0) {
    
    private val streamingFiles = ConcurrentHashMap<String, StreamingFile>()
    private val nextTokenId = AtomicInteger(1)
    private val serverAddress = "127.0.0.1"
    @Volatile private var listeningPort = 0
    
    data class StreamingFile(
        val fileId: Int,
        val size: Long,
        val mimeType: String,
        val dataProvider: VideoDataProvider
    )
    
    /** Creates a stream on demand for a Telegram message (used by /tg/<chatId>/<messageId> URLs). */
    fun interface VideoResolver {
        suspend fun resolve(chatId: Long, messageId: Long): StreamingFile?
    }

    @Volatile
    var resolver: VideoResolver? = null

    // Streams created on demand, keyed by "chatId/messageId"
    private val lazyFiles = ConcurrentHashMap<String, StreamingFile>()

    /**
     * URL of a stream that is resolved only when a player requests it, so a whole playlist can be
     * handed to an external player without opening every file up front. [fileName] only helps the
     * player guess the format and show a name.
     */
    fun urlFor(chatId: Long, messageId: Long, fileName: String? = null): String {
        val name = java.net.URLEncoder.encode(fileName?.takeIf { it.isNotBlank() } ?: "video", "UTF-8").replace("+", "%20")
        return "http://$serverAddress:$listeningPort/tg/$chatId/$messageId/$name"
    }

    /** Close on-demand streams, except the ones whose key "chatId/messageId" is in [keep]. */
    fun releaseLazyStreams(keep: Set<String> = emptySet()) {
        for (key in lazyFiles.keys.toList()) {
            if (key !in keep) lazyFiles.remove(key)?.dataProvider?.close()
        }
    }

    private fun lazyStream(path: String): StreamingFile? {
        val parts = path.removePrefix("/tg/").split('/')
        val chatId = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val messageId = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val key = "$chatId/$messageId"
        lazyFiles[key]?.let { return it }
        synchronized(lazyFiles) {
            lazyFiles[key]?.let { return it }
            val file = runBlocking { resolver?.resolve(chatId, messageId) } ?: return null
            lazyFiles[key] = file
            return file
        }
    }

    interface VideoDataProvider {
        suspend fun readBytes(offset: Long, length: Int): ByteArray?
        fun getAvailableDataSize(offset: Long): Long
        fun close()
    }
    
    companion object {
        private const val SOCKET_READ_TIMEOUT = 30000
        
        @Volatile
        private var instance: VideoStreamingServer? = null
        
        fun getInstance(): VideoStreamingServer {
            return instance ?: synchronized(this) {
                instance ?: VideoStreamingServer().also { 
                    instance = it
                }
            }
        }
        
        /**
         * Get instance and ensure server is started. Call this only when streaming is needed.
         */
        @Synchronized
        fun getRunningInstance(): VideoStreamingServer {
            val inst = getInstance()
            if (inst.listeningPort == 0) {
                inst.startServer()
            }
            return inst
        }
        
        fun shutdown() {
            instance?.stop()
            instance = null
        }
    }
    
    init {
        // NanoHTTPD logs every client disconnect (players drop connections on seek/close) as SEVERE
        java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name).level = java.util.logging.Level.OFF
    }

    private fun startServer() {
        // Let failures propagate: a URL with port 0 would only fail later and less clearly
        start(SOCKET_READ_TIMEOUT, false)
        listeningPort = getListeningPort()
        Log.i("Video streaming server started on port $listeningPort")
    }
    
    /** A registered stream: [token] is what [unregisterVideo] expects, [url] is what the player opens. */
    data class Registration(val token: String, val url: String)

    /**
     * Register a video for streaming and return its token and URL.
     */
    fun registerVideo(fileId: Int, size: Long, mimeType: String, dataProvider: VideoDataProvider): Registration {
        val token = "video_${nextTokenId.getAndIncrement()}_${fileId}"
        streamingFiles[token] = StreamingFile(fileId, size, mimeType, dataProvider)
        return Registration(token, "http://$serverAddress:$listeningPort/video/$token")
    }
    
    /**
     * Unregister a video and clean up resources
     */
    fun unregisterVideo(token: String) {
        streamingFiles.remove(token)?.dataProvider?.close()
    }
    
    private fun createSilentResponse(status: Response.IStatus, mimeType: String, message: String): Response {
        val bytes = message.toByteArray()
        return SilentResponse(status, mimeType, bytes.inputStream(), bytes.size.toLong())
    }

    private fun createSilentResponse(status: Response.IStatus, mimeType: String, data: InputStream?, totalBytes: Long): Response {
        return SilentResponse(status, mimeType, data, totalBytes)
    }

    private class SilentResponse(
        status: Response.IStatus,
        mimeType: String?,
        data: InputStream?,
        totalBytes: Long
    ) : Response(status, mimeType, data, totalBytes) {
        
        override fun send(outputStream: java.io.OutputStream?) {
            try {
                super.send(outputStream)
            } catch (e: java.net.SocketException) {
                // Ignore connection reset by peer (client disconnected)
                // This is common with video players like MPV/VLC performing seek operations
            } catch (e: Exception) {
                // Re-throw other exceptions or let them be logged if needed
                // But generally for a response send, if it fails, it fails.
                 Log.e("Error sending response: ${e.message}")
            }
        }
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        
        return when {
            uri.startsWith("/video/") -> handleVideoRequest(session)
            uri.startsWith("/tg/") -> {
                val file = lazyStream(uri)
                    ?: return createSilentResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video not found")
                serveFile(session, file)
            }
            else -> createSilentResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }
    
    private fun handleVideoRequest(session: IHTTPSession): Response {
        val token = session.uri.removePrefix("/video/")
        val streamingFile = streamingFiles[token]
            ?: return createSilentResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video not found")
        
        return serveFile(session, streamingFile)
    }

    private fun serveFile(session: IHTTPSession, streamingFile: StreamingFile): Response {
        return try {
            when (session.method) {
                Method.HEAD -> handleHeadRequest(streamingFile)
                Method.GET -> handleGetRequest(session, streamingFile)
                else -> createSilentResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
            }
        } catch (e: Exception) {
            Log.e("Error handling video request: ${e.message}")
            createSilentResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Server error")
        }
    }
    
    private fun handleHeadRequest(streamingFile: StreamingFile): Response {
        val response = createSilentResponse(Response.Status.OK, streamingFile.mimeType, null as InputStream?, 0)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", streamingFile.size.toString())
        addCorsHeaders(response)
        return response
    }
    
    private fun handleGetRequest(session: IHTTPSession, streamingFile: StreamingFile): Response {
        val rangeHeader = session.headers["range"]
        return if (rangeHeader != null) {
            handleRangeRequest(rangeHeader, streamingFile)
        } else {
            handleFullRequest(streamingFile)
        }
    }
    
    private fun handleFullRequest(streamingFile: StreamingFile): Response {
        val inputStream = VideoInputStream(streamingFile.dataProvider, 0, streamingFile.size)
        val response = createSilentResponse(Response.Status.OK, streamingFile.mimeType, inputStream, streamingFile.size)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", streamingFile.size.toString())
        addCorsHeaders(response)
        return response
    }
    
    /**
     * Serves "bytes=start-end", "bytes=start-" and "bytes=-suffix" to the requested end.
     * The body streams as TDLib downloads it; a player that seeks just closes the connection.
     * (Capping responses at a few MB, as before, made players re-request constantly.)
     */
    private fun handleRangeRequest(rangeHeader: String, streamingFile: StreamingFile): Response {
        val size = streamingFile.size
        val spec = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()

        val start: Long
        val end: Long
        if (startText.isEmpty()) {
            // Suffix range: the last N bytes
            val suffix = endText.toLongOrNull() ?: 0L
            start = (size - suffix).coerceAtLeast(0)
            end = size - 1
        } else {
            start = startText.toLongOrNull() ?: 0L
            end = endText.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
        }

        if (start >= size || end < start) {
            val response = createSilentResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Range not satisfiable")
            response.addHeader("Content-Range", "bytes */$size")
            return response
        }

        val contentLength = end - start + 1
        Log.d("Range request: $start-$end (${contentLength / 1024}KB) of ${size / 1024}KB")

        val inputStream = VideoInputStream(streamingFile.dataProvider, start, contentLength)
        val response = createSilentResponse(Response.Status.PARTIAL_CONTENT, streamingFile.mimeType, inputStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$end/$size")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", contentLength.toString())
        addCorsHeaders(response)
        return response
    }
    
    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Range, Content-Type")
    }
    
    /**
     * InputStream that reads video data from Telegram progressively.
     * Blocking is fine here: each HTTP request has its own NanoHTTPD thread.
     * The provider already waits (with one deadline) for missing data, so no retry loop is needed.
     */
    private class VideoInputStream(
        private val dataProvider: VideoDataProvider,
        startOffset: Long,
        totalLength: Long
    ) : InputStream() {

        private var currentOffset = startOffset
        private var bytesRemaining = totalLength

        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) > 0) buffer[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRemaining <= 0) return -1
            val toRead = minOf(len.toLong(), bytesRemaining).toInt()
            val data = try {
                runBlocking { dataProvider.readBytes(currentOffset, toRead) }
            } catch (e: Exception) {
                Log.e("Error reading video data: ${e.message}")
                null
            } ?: return -1
            if (data.isEmpty()) return -1
            System.arraycopy(data, 0, b, off, data.size)
            currentOffset += data.size
            bytesRemaining -= data.size
            return data.size
        }

        override fun available(): Int = 0
    }
}
