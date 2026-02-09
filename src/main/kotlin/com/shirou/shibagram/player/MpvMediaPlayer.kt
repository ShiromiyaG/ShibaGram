package com.shirou.shibagram.player

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media player backend using **mpv** via subprocess + JSON IPC (named pipe on Windows).
 *
 * mpv renders directly into an embedded AWT Canvas via `--wid=<HWND>`,
 * providing hardware-accelerated playback with zero frame-copy overhead.
 *
 * IPC protocol: JSON over Windows named pipe (`\\.\pipe\mpv-shibagram-<nonce>`).
 */
class MpvMediaPlayer(
    private val customMpvPath: String? = null
) : MediaPlayerEngine {

    // ---- State flows (same API surface as VlcMediaPlayer) ----------------

    private val _currentFrame = MutableStateFlow<ImageBitmap?>(null)
    override val currentFrame: StateFlow<ImageBitmap?> = _currentFrame // always null – mpv renders natively

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume

    private val _isBuffering = MutableStateFlow(false)
    override val isBuffering: StateFlow<Boolean> = _isBuffering

    override var onMediaEnd: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override val rendersToWindow: Boolean = true

    // ---- Internal state --------------------------------------------------

    private var mpvPath: String? = null
    private var mpvProcess: Process? = null
    private var ipcPipe: RandomAccessFile? = null
    private var readerThread: Thread? = null
    private var canvas: java.awt.Canvas? = null
    private var windowHandle: Long = 0
    private var pendingMediaPath: String? = null
    @Volatile private var lastMediaPath: String? = null
    @Volatile private var handleLocked = false
    @Volatile private var shutdownHookInstalled = false
    @Volatile private var pipeName = generatePipeName()
    private val released = AtomicBoolean(false)
    @Volatile private var ipcReady = false // true after pipe connected & verified

    /** Single-threaded executor for all IPC writes – keeps pipe writes off the UI thread. */
    private var ipcExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-ipc-writer").apply { isDaemon = true }
    }

    // Track info parsed from mpv's track-list property
    @Volatile private var _audioTracks = listOf<MediaPlayerEngine.TrackInfo>()
    @Volatile private var _subtitleTracks = listOf<MediaPlayerEngine.TrackInfo>()
    @Volatile private var _currentAudioTrackId = -1
    @Volatile private var _currentSubtitleTrackId = -1

    private val json = Json { ignoreUnknownKeys = true }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    override fun initialize(): Boolean {
        mpvPath = customMpvPath?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: findMpvExecutable()

        if (mpvPath == null) {
            onError?.invoke("mpv not found. Install mpv (https://mpv.io) and ensure mpv.exe is in your PATH.")
            return false
        }
        println("mpv found at: $mpvPath")
        return true
    }

    override fun isInitialized(): Boolean = mpvPath != null

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        try { sendCommandDirect("""{"command":["quit"]}""") } catch (_: Exception) {}
        Thread.sleep(150)
        try { ipcExecutor.shutdownNow() } catch (_: Exception) {}
        try { ipcPipe?.close() } catch (_: Exception) {}
        try { mpvProcess?.destroyForcibly() } catch (_: Exception) {}
        ipcPipe = null
        ipcReady = false
        mpvProcess = null
        readerThread = null
        canvas = null
        handleLocked = false
        _isPlaying.value = false
        _currentPosition.value = 0
        _duration.value = 0
        _currentFrame.value = null
    }

    // ======================================================================
    // Canvas / window handle
    // ======================================================================

    override fun attachCanvas(canvas: java.awt.Canvas) {
        if (this.canvas === canvas) return
        this.canvas = canvas
        canvas.background = java.awt.Color.BLACK
        // CRITICAL: prevent AWT from painting over mpv's native rendering
        canvas.ignoreRepaint = true

        if (canvas.isDisplayable) {
            captureWindowHandle()
        } else {
            canvas.addHierarchyListener { event ->
                if ((event.changeFlags.toLong() and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong()) != 0L
                    && canvas.isDisplayable
                ) {
                    captureWindowHandle()
                }
            }
        }
    }

    private fun captureWindowHandle() {
        try {
            val hwnd = com.sun.jna.Native.getComponentID(canvas!!)
            if (hwnd != windowHandle) {
                if (handleLocked) {
                    // During playback, ignore handle changes to avoid restart loops
                    println("MPV canvas HWND changed during playback (ignored): $hwnd")
                    return
                }
                windowHandle = hwnd
            }
            println("MPV canvas HWND: $windowHandle")

            pendingMediaPath?.let { path ->
                pendingMediaPath = null
                play(path)
            }
        } catch (e: Exception) {
            println("Failed to get HWND: ${e.message}")
            onError?.invoke("Failed to get native window handle: ${e.message}")
        }
    }

    // ======================================================================
    // Process management
    // ======================================================================

    private fun ensureProcessRunning(): Boolean {
        if (mpvProcess?.isAlive == true && ipcReady) return true
        if (windowHandle == 0L) return false

        killProcess() // clean up stale state

        // Generate a fresh pipe name for each launch to avoid stale pipe handles
        pipeName = generatePipeName()

        val args = listOf(
            mpvPath!!,
            "--wid=$windowHandle",
            "--input-ipc-server=$pipeName",
            "--idle=yes",
            "--keep-open=yes",
            "--no-osc",
            "--no-input-default-bindings",
            "--no-terminal",
            "--hr-seek=yes",
            "--hwdec=auto-safe",
            "--keepaspect=yes",
            "--cursor-autohide=no",
            "--input-cursor=no",
            "--no-osd-bar",
            "--volume=100"
        )

        return try {
            println("MPV starting with args: ${args.drop(1).joinToString(" ")}")
            mpvProcess = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()

            if (!shutdownHookInstalled) {
                shutdownHookInstalled = true
                Runtime.getRuntime().addShutdownHook(Thread {
                    try { killProcess() } catch (_: Exception) {}
                })
            }

            // Drain stdout/stderr – log it for diagnostics
            val proc = mpvProcess
            Thread {
                proc?.inputStream?.bufferedReader()?.forEachLine { line ->
                    println("MPV stdout: $line")
                }
            }.apply { isDaemon = true; start() }

            // Wait for mpv to be alive
            Thread.sleep(200)
            if (mpvProcess?.isAlive != true) {
                println("MPV process exited immediately – check your mpv installation")
                onError?.invoke("mpv process exited immediately")
                return false
            }

            // Connect IPC synchronously (blocks until ready or timeout)
            connectIpcSync()
        } catch (e: Exception) {
            println("Failed to start mpv: ${e.message}")
            onError?.invoke("Failed to start mpv: ${e.message}")
            false
        }
    }

    private fun killProcess() {
        try { ipcExecutor.shutdownNow() } catch (_: Exception) {}
        try { ipcPipe?.close() } catch (_: Exception) {}
        try { mpvProcess?.destroyForcibly() } catch (_: Exception) {}
        ipcPipe = null
        ipcReady = false
        mpvProcess = null
        readerThread = null
        // Create a fresh executor for next launch
        ipcExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "mpv-ipc-writer").apply { isDaemon = true }
        }
    }

    // ======================================================================
    // IPC (JSON over Windows named pipe)
    // ======================================================================

    /**
     * Connects to the mpv IPC pipe **synchronously**, verifies it is alive,
     * subscribes to properties, and launches the reader thread.
     * Returns true if everything succeeded.
     */
    private fun connectIpcSync(): Boolean {
        var connected = false
        for (attempt in 0 until 50) { // retry for up to 5 s
            if (mpvProcess?.isAlive != true) {
                println("MPV process died while waiting for IPC pipe (attempt $attempt)")
                return false
            }
            try {
                ipcPipe = RandomAccessFile(pipeName, "rw")
                connected = true
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        if (!connected) {
            println("Failed to connect to mpv IPC pipe after retries")
            onError?.invoke("Failed to connect to mpv IPC pipe")
            return false
        }
        println("Connected to mpv IPC pipe")

        // Verify pipe is working with a simple echo command
        try {
            ipcPipe!!.writeBytes("""{"command":["client_name"]}""" + "\n")
        } catch (e: Exception) {
            println("MPV IPC verification failed: ${e.message}")
            onError?.invoke("mpv IPC pipe not responding")
            try { ipcPipe?.close() } catch (_: Exception) {}
            ipcPipe = null
            return false
        }

        // Small delay to let mpv fully settle
        Thread.sleep(100)

        // Observe properties (direct writes during init – already on background thread)
        observePropertyDirect(1, "time-pos")
        observePropertyDirect(2, "duration")
        observePropertyDirect(3, "pause")
        observePropertyDirect(4, "volume")
        observePropertyDirect(5, "eof-reached")
        observePropertyDirect(6, "track-list")
        observePropertyDirect(7, "paused-for-cache")

        ipcReady = true

        // Start reader thread
        readerThread = Thread {
            try {
                while (mpvProcess?.isAlive == true) {
                    val line = readLineUtf8(ipcPipe ?: break) ?: break
                    if (line.isNotEmpty()) handleIpcMessage(line)
                }
            } catch (_: Exception) { /* pipe closed */ }
            println("MPV IPC reader thread ended")
        }.apply { isDaemon = true; start() }

        return true
    }

    /** Read a UTF-8 line from the pipe (byte-by-byte, blocking). */
    private fun readLineUtf8(raf: RandomAccessFile): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = raf.read()
            if (b == -1) return if (buf.size() > 0) buf.toString(Charsets.UTF_8.name()) else null
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name())
            if (b != '\r'.code) buf.write(b)
        }
    }

    private fun observePropertyDirect(id: Int, name: String) {
        sendCommandDirect("""{"command":["observe_property",$id,"$name"]}""")
    }

    /**
     * Send an IPC command **asynchronously** via the executor.
     * Safe to call from any thread (including the UI/Compose thread).
     */
    private fun sendCommand(jsonCmd: String) {
        try {
            ipcExecutor.submit {
                sendCommandDirect(jsonCmd)
            }
        } catch (_: Exception) {
            // executor shut down – ignore
        }
    }

    /** Send synchronously on the calling thread (used during init & shutdown). */
    private fun sendCommandDirect(jsonCmd: String) {
        try {
            val pipe = ipcPipe ?: return
            if (mpvProcess?.isAlive != true) {
                println("MPV IPC send skipped – process not alive")
                return
            }
            synchronized(pipe) {
                pipe.writeBytes(jsonCmd + "\n")
            }
        } catch (e: Exception) {
            println("MPV IPC send error: ${e.message}")
        }
    }

    // ======================================================================
    // IPC message handling
    // ======================================================================

    private fun handleIpcMessage(line: String) {
        try {
            val obj = json.parseToJsonElement(line).jsonObject
            when (obj["event"]?.jsonPrimitive?.contentOrNull) {
                "property-change" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return
                    handlePropertyChange(name, obj["data"])
                }
                "end-file" -> {
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull
                    if (reason == "eof") {
                        _isPlaying.value = false
                        onMediaEnd?.invoke()
                    }
                }
                "file-loaded" -> _isBuffering.value = false
                "seek" -> _isBuffering.value = true
                "playback-restart" -> _isBuffering.value = false
                else -> { /* ignore other events */ }
            }
        } catch (_: Exception) { /* malformed JSON – ignore */ }
    }

    private fun handlePropertyChange(name: String, data: JsonElement?) {
        if (data == null || data is JsonNull) return
        when (name) {
            "time-pos" -> {
                data.jsonPrimitive.doubleOrNull?.let { _currentPosition.value = (it * 1000).toLong() }
            }
            "duration" -> {
                data.jsonPrimitive.doubleOrNull?.let { _duration.value = (it * 1000).toLong() }
            }
            "pause" -> {
                data.jsonPrimitive.booleanOrNull?.let { _isPlaying.value = !it }
            }
            "volume" -> {
                data.jsonPrimitive.doubleOrNull?.let { _volume.value = (it / 100.0).toFloat().coerceIn(0f, 1f) }
            }
            "eof-reached" -> {
                if (data.jsonPrimitive.booleanOrNull == true) {
                    _isPlaying.value = false
                    onMediaEnd?.invoke()
                }
            }
            "track-list" -> {
                if (data is JsonArray) parseTrackList(data)
            }
            "paused-for-cache" -> {
                data.jsonPrimitive.booleanOrNull?.let { _isBuffering.value = it }
            }
        }
    }

    private fun parseTrackList(tracks: JsonArray) {
        val audio = mutableListOf<MediaPlayerEngine.TrackInfo>()
        val subs = mutableListOf<MediaPlayerEngine.TrackInfo>()

        for (trackEl in tracks) {
            val t = trackEl.jsonObject
            val type = t["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val id = t["id"]?.jsonPrimitive?.intOrNull ?: continue
            val title = t["title"]?.jsonPrimitive?.contentOrNull
            val lang = t["lang"]?.jsonPrimitive?.contentOrNull
            val codec = t["codec"]?.jsonPrimitive?.contentOrNull ?: ""
            val selected = t["selected"]?.jsonPrimitive?.booleanOrNull ?: false

            val displayName = buildString {
                append(title ?: "Track $id")
                if (lang != null) append(" [$lang]")
                if (codec.isNotEmpty()) append(" ($codec)")
            }

            when (type) {
                "audio" -> {
                    audio += MediaPlayerEngine.TrackInfo(id, displayName)
                    if (selected) _currentAudioTrackId = id
                }
                "sub" -> {
                    subs += MediaPlayerEngine.TrackInfo(id, displayName)
                    if (selected) _currentSubtitleTrackId = id
                }
            }
        }
        _audioTracks = audio
        _subtitleTracks = subs
    }

    // ======================================================================
    // Playback controls
    // ======================================================================

    override fun play(mediaPath: String) {
        lastMediaPath = mediaPath
        if (windowHandle == 0L) {
            println("MPV play deferred – no HWND yet")
            pendingMediaPath = mediaPath
            return
        }

        handleLocked = true

        // ensureProcessRunning() is now synchronous: starts process + connects IPC
        Thread {
            if (!ensureProcessRunning()) {
                println("MPV play failed – could not start process")
                pendingMediaPath = mediaPath
                return@Thread
            }
            _isBuffering.value = true
            // mpv prefers forward slashes; replace each \ with /
            val escaped = mediaPath.replace('\\', '/')
            pendingMediaPath = null
            sendCommand("""{"command":["loadfile","$escaped","replace"]}""")
            println("MPV loadfile sent: $escaped")
        }.apply { isDaemon = true; start() }
    }

    override fun pause() = sendCommand("""{"command":["set_property","pause",true]}""")

    override fun resume() = sendCommand("""{"command":["set_property","pause",false]}""")

    override fun togglePlayPause() = sendCommand("""{"command":["cycle","pause"]}""")

    override fun stop() {
        sendCommand("""{"command":["stop"]}""")
        _isPlaying.value = false
        _currentPosition.value = 0
        _duration.value = 0
        // Kill process so next play() will restart with (possibly new) HWND
        killProcess()
        handleLocked = false
    }

    override fun seekTo(position: Long) {
        val seconds = position / 1000.0
        sendCommand("""{"command":["set_property","time-pos",$seconds]}""")
    }

    override fun setVolume(vol: Float) {
        val mpvVol = (vol * 100).coerceIn(0f, 100f)
        sendCommand("""{"command":["set_property","volume",$mpvVol]}""")
        _volume.value = vol
    }

    override fun setPlaybackSpeed(speed: Float) =
        sendCommand("""{"command":["set_property","speed",$speed]}""")

    // ======================================================================
    // Track management
    // ======================================================================

    override fun getAudioTracks(): List<MediaPlayerEngine.TrackInfo> = _audioTracks
    override fun getCurrentAudioTrack(): Int = _currentAudioTrackId
    override fun setAudioTrack(trackId: Int) {
        if (trackId <= 0) sendCommand("""{"command":["set_property","aid","no"]}""")
        else sendCommand("""{"command":["set_property","aid",$trackId]}""")
        _currentAudioTrackId = trackId
    }

    override fun getSubtitleTracks(): List<MediaPlayerEngine.TrackInfo> = _subtitleTracks
    override fun getCurrentSubtitleTrack(): Int = _currentSubtitleTrackId
    override fun setSubtitleTrack(trackId: Int) {
        if (trackId <= 0) sendCommand("""{"command":["set_property","sid","no"]}""")
        else sendCommand("""{"command":["set_property","sid",$trackId]}""")
        _currentSubtitleTrackId = trackId
    }

    // ======================================================================
    // mpv discovery
    // ======================================================================

    private fun findMpvExecutable(): String? {
        val home = System.getProperty("user.home")
        val appDir = System.getProperty("user.dir")

        // 1. Check well-known filesystem locations (fast – no process spawn)
        val candidates = listOf(
            "$appDir\\mpv.exe",
            "$appDir\\libs\\mpv\\mpv.exe",
            "$home\\scoop\\apps\\mpv\\current\\mpv.exe",
            "$home\\scoop\\shims\\mpv.exe",
            "C:\\ProgramData\\chocolatey\\bin\\mpv.exe",
            "C:\\Program Files\\mpv\\mpv.exe",
            "C:\\Program Files (x86)\\mpv\\mpv.exe",
            "$home\\AppData\\Local\\Programs\\mpv\\mpv.exe"
        )
        for (c in candidates) {
            if (File(c).exists()) return c
        }

        // 2. Fallback: check PATH via `where` command
        return try {
            val p = ProcessBuilder("where", "mpv")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (p.exitValue() == 0 && out.isNotEmpty()) {
                // Prefer .exe over .com (the .com console wrapper can behave
                // differently when launched as a subprocess)
                val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                lines.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
                    ?: lines.firstOrNull()
            } else null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private fun generatePipeName() =
            "\\\\.\\pipe\\mpv-shibagram-${System.nanoTime()}"
    }
}
