package com.shirou.shibagram.player

import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Plays videos in the user's own mpv (with their mpv.conf, shaders and scripts).
 *
 * mpv gets an M3U playlist: the clicked video and the rest of the list, as stream URLs that the
 * local server resolves only when mpv opens them. Next/previous then work with mpv's own keys.
 * A JSON IPC connection (Windows named pipe) is polled every second to save watch progress and to
 * resume each video where it was left.
 */
class ExternalMpvSession(
    private val mpvPath: String,
    private val playlist: List<MediaMessage>,
    private val startIndex: Int,
    private val urlFor: (MediaMessage) -> String,
    private val savedPositionMs: suspend (MediaMessage) -> Long?,
    private val onProgress: suspend (MediaMessage, positionMs: Long, durationMs: Long) -> Unit,
    private val onActiveVideoChanged: (MediaMessage?) -> Unit,
    private val onExit: () -> Unit,
    /** Extra mpv arguments (used by the headless streaming check). */
    private val extraArgs: List<String> = emptyList()
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pipeName = "\\\\.\\pipe\\shibagram-mpv-${UUID.randomUUID()}"
    private var process: Process? = null
    private var playlistFile: File? = null
    @Volatile private var stopped = false

    companion object {
        /** True when [path] points to an mpv executable. */
        fun isValidMpvPath(path: String): Boolean {
            val f = File(path.trim())
            return f.isFile && f.name.lowercase().let { it == "mpv.exe" || it == "mpv.com" || it == "mpv" }
        }
    }

    fun start() {
        val m3u = File.createTempFile("shibagram-", ".m3u8").apply { deleteOnExit() }
        m3u.writeText(buildString {
            appendLine("#EXTM3U")
            for (video in playlist) {
                appendLine("#EXTINF:${video.duration},${video.displayTitle.replace('\n', ' ')}")
                appendLine(urlFor(video))
            }
        }, Charsets.UTF_8)
        playlistFile = m3u

        val command = listOf(
            mpvPath,
            "--input-ipc-server=$pipeName",
            "--playlist=${m3u.absolutePath}",
            "--playlist-start=$startIndex",
            "--force-window=immediate",
            // A stream stalls while Telegram fetches data after a seek: give it time
            "--network-timeout=60",
            "--cache=yes",
            "--demuxer-mkv-subtitle-preroll=index"
        ) + extraArgs
        Log.i("Starting external mpv: $mpvPath (${playlist.size} items, start $startIndex)")
        process = ProcessBuilder(command)
            .directory(File(mpvPath).parentFile)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        scope.launch { monitor() }
    }

    /** Closes mpv (progress is saved by the monitor loop on exit). */
    fun stop() {
        stopped = true
        process?.destroy()
    }

    private suspend fun monitor() {
        var lastIndex = -1
        var lastPosition = 0L
        var lastDuration = 0L
        var resumedIndex = -1
        var lastSave = 0L
        try {
            val pipe = connect() ?: return
            pipe.use { ipc ->
                ipc.request(buildJsonArray { add("disable_event"); add("all") })
                while (process?.isAlive == true && !stopped) {
                    val index = ipc.getNumber("playlist-pos")?.toInt() ?: -1
                    val position = ipc.getNumber("time-pos")?.let { (it * 1000).toLong() }
                    val duration = ipc.getNumber("duration")?.let { (it * 1000).toLong() }

                    if (index != lastIndex) {
                        // Save the video we're leaving, then switch
                        playlist.getOrNull(lastIndex)?.let { if (lastPosition > 0 && lastDuration > 0) onProgress(it, lastPosition, lastDuration) }
                        lastIndex = index
                        lastPosition = 0
                        lastDuration = 0
                        onActiveVideoChanged(playlist.getOrNull(index))
                    }
                    val video = playlist.getOrNull(index)
                    if (video != null && position != null && duration != null && duration > 0) {
                        // Resume once per entry, as soon as mpv has opened it
                        if (resumedIndex != index) {
                            resumedIndex = index
                            val saved = savedPositionMs(video)
                            if (saved != null && saved > 5_000 && saved < duration * 0.95 && position < 5_000) {
                                ipc.request(buildJsonArray { add("seek"); add(saved / 1000.0); add("absolute") })
                            }
                        }
                        lastPosition = position
                        lastDuration = duration
                        val now = System.currentTimeMillis()
                        if (now - lastSave > 5_000) {
                            lastSave = now
                            onProgress(video, position, duration)
                        }
                    }
                    delay(1_000)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The pipe breaks when mpv quits; that's the normal way out
            Log.d("External mpv IPC ended: ${e.message}")
        } finally {
            withContext(NonCancellable) {
                playlist.getOrNull(lastIndex)?.let { if (lastPosition > 0 && lastDuration > 0) onProgress(it, lastPosition, lastDuration) }
            }
            process?.waitFor()
            playlistFile?.delete()
            onActiveVideoChanged(null)
            onExit()
            scope.cancel()
        }
    }

    /** Opens the IPC pipe once mpv has created it. */
    private suspend fun connect(): RandomAccessFile? {
        repeat(100) {
            if (process?.isAlive != true) return null
            try {
                return RandomAccessFile(pipeName, "rw")
            } catch (_: Exception) {
                delay(100)
            }
        }
        Log.w("Could not connect to external mpv IPC")
        return null
    }

    private var requestId = 0

    /** Sends a command and returns its "data" (one request at a time on this thread). */
    private fun RandomAccessFile.request(command: JsonArray): JsonElement? {
        val id = ++requestId
        val message = buildJsonObject {
            put("command", command)
            put("request_id", id)
        }.toString() + "\n"
        write(message.toByteArray(Charsets.UTF_8))
        while (true) {
            val line = readLine() ?: throw java.io.EOFException("mpv closed the pipe")
            val json = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if ((json["request_id"] as? JsonPrimitive)?.intOrNull != id) continue
            return if ((json["error"] as? JsonPrimitive)?.content == "success") json["data"] else null
        }
    }

    private fun RandomAccessFile.getNumber(property: String): Double? =
        (request(buildJsonArray { add("get_property"); add(property) }) as? JsonPrimitive)?.doubleOrNull
}
