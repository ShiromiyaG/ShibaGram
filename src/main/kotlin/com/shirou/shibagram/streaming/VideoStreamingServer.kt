package com.shirou.shibagram.streaming

import com.shirou.shibagram.data.remote.TelegramClientService
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
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
    private var serverAddress = "127.0.0.1"
    private var listeningPort = 0
    
    data class StreamingFile(
        val fileId: Int,
        val size: Long,
        val mimeType: String,
        val dataProvider: VideoDataProvider,
        val lastAccessTime: Long = System.currentTimeMillis()
    )
    
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
    
    private fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            listeningPort = getListeningPort()
            println("Video streaming server started on port $listeningPort")
        } catch (e: Exception) {
            println("Failed to start streaming server: ${e.message}")
        }
    }
    
    /**
     * Register a video for streaming and return the URL to access it
     */
    fun registerVideo(fileId: Int, size: Long, mimeType: String, dataProvider: VideoDataProvider): String {
        val token = "video_${nextTokenId.getAndIncrement()}_${fileId}"
        streamingFiles[token] = StreamingFile(fileId, size, mimeType, dataProvider)
        return "http://$serverAddress:$listeningPort/video/$token"
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
                 println("Error sending response: ${e.message}")
            }
        }
    }
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        
        return when {
            uri.startsWith("/video/") -> handleVideoRequest(session)
            else -> createSilentResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }
    
    private fun handleVideoRequest(session: IHTTPSession): Response {
        val token = session.uri.removePrefix("/video/")
        val streamingFile = streamingFiles[token]
            ?: return createSilentResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Video not found")
        
        // Update last access time
        streamingFiles[token] = streamingFile.copy(lastAccessTime = System.currentTimeMillis())
        
        return try {
            when (session.method) {
                Method.HEAD -> handleHeadRequest(streamingFile)
                Method.GET -> handleGetRequest(session, streamingFile)
                else -> createSilentResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
            }
        } catch (e: Exception) {
            println("Error handling video request: ${e.message}")
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
    
    private fun handleRangeRequest(rangeHeader: String, streamingFile: StreamingFile): Response {
        // Parse range header: "bytes=start-end" or "bytes=start-"
        val rangeSpec = rangeHeader.removePrefix("bytes=")
        val parts = rangeSpec.split("-")
        
        val start = parts[0].toLongOrNull() ?: 0L
        val requestedEnd = if (parts.size > 1 && parts[1].isNotEmpty()) {
            parts[1].toLongOrNull() ?: (streamingFile.size - 1)
        } else {
            // For "bytes=start-" requests, limit to available data or max chunk
            val available = streamingFile.dataProvider.getAvailableDataSize(start)
            val maxChunk = 2 * 1024 * 1024L // 2MB max per request
            if (available > 0) {
                minOf(start + available - 1, start + maxChunk - 1, streamingFile.size - 1)
            } else {
                minOf(start + maxChunk - 1, streamingFile.size - 1)
            }
        }
        
        // Validate range
        if (start >= streamingFile.size) {
            val response = createSilentResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Range not satisfiable")
            response.addHeader("Content-Range", "bytes */${streamingFile.size}")
            return response
        }
        
        // Limit end to what we can reasonably serve
        val maxChunkSize = 4 * 1024 * 1024L // 4MB max
        val actualEnd = minOf(requestedEnd, start + maxChunkSize - 1, streamingFile.size - 1)
        val contentLength = actualEnd - start + 1
        
        println("Range request: $start-$actualEnd (${contentLength / 1024}KB) of ${streamingFile.size / 1024}KB")
        
        val inputStream = VideoInputStream(streamingFile.dataProvider, start, contentLength)
        val response = createSilentResponse(Response.Status.PARTIAL_CONTENT, streamingFile.mimeType, inputStream, contentLength)
        response.addHeader("Content-Range", "bytes $start-$actualEnd/${streamingFile.size}")
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
     * InputStream that reads video data from Telegram progressively
     */
    private inner class VideoInputStream(
        private val dataProvider: VideoDataProvider,
        private val startOffset: Long,
        private val totalLength: Long
    ) : InputStream() {
        
        private var currentOffset = startOffset
        private var bytesRemaining = totalLength
        private val scope = CoroutineScope(Dispatchers.IO)
        
        override fun read(): Int {
            if (bytesRemaining <= 0) return -1
            
            val buffer = ByteArray(1)
            val bytesRead = read(buffer, 0, 1)
            return if (bytesRead > 0) buffer[0].toInt() and 0xFF else -1
        }
        
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRemaining <= 0) return -1
            
            val toRead = minOf(len.toLong(), bytesRemaining).toInt()
            
            // Read data synchronously (blocking for HTTP server)
            // Keep trying until we get data or timeout
            var attempts = 0
            val maxAttempts = 300 // 30 seconds total (100ms * 300)
            
            while (attempts < maxAttempts) {
                // Check if scope is active before blocking runBlocking
                // However, VideoInputStream close() cancels scope, but we are inside read()
                // Just proceed.
                
                try {
                    val data = runBlocking {
                        dataProvider.readBytes(currentOffset, toRead)
                    }
                    
                    if (data != null && data.isNotEmpty()) {
                        System.arraycopy(data, 0, b, off, data.size)
                        currentOffset += data.size
                        bytesRemaining -= data.size
                        return data.size
                    }
                } catch (e: Exception) {
                    println("Error reading video data: ${e.message}")
                    return -1
                }
                
                // Wait and retry
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    return -1
                }
                attempts++
            }
            
            // Timeout - return -1 to signal end of stream
            println("VideoInputStream: Timeout waiting for data at offset $currentOffset")
            return -1
        }
        
        override fun available(): Int {
            return bytesRemaining.toInt().coerceAtMost(Int.MAX_VALUE)
        }
        
        override fun close() {
            scope.cancel()
        }
    }
}
