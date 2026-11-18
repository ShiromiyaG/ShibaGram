package com.shirou.shibagram.player

import com.shirou.shibagram.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media player backend using **libmpv** directly via JNA.
 *
 * mpv renders directly into an embedded AWT Canvas via the `wid` option,
 * providing hardware-accelerated playback.
 */
class MpvMediaPlayer : MediaPlayerEngine {

    // ---- State flows -----------------------------------------------------

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

    @Volatile private var mpvHandle: Pointer? = null
    private var eventThread: Thread? = null
    private var canvas: java.awt.Canvas? = null
    @Volatile private var windowHandle: Long = 0
    private data class PendingLoad(val path: String, val startMs: Long, val title: String?)
    @Volatile private var pendingLoad: PendingLoad? = null

    // Preferred track languages (mpv "alang"/"slang", comma separated, e.g. "jpn,ja")
    @Volatile private var audioLanguages: String = ""
    @Volatile private var subtitleLanguages: String = ""
    
    // Track info
    @Volatile private var _audioTracks = listOf<MediaPlayerEngine.TrackInfo>()
    @Volatile private var _subtitleTracks = listOf<MediaPlayerEngine.TrackInfo>()
    @Volatile private var _currentAudioTrackId = -1
    @Volatile private var _currentSubtitleTrackId = -1

    private val _tracksChangeSignal = MutableStateFlow(0)
    override val tracksChangeSignal: StateFlow<Int> = _tracksChangeSignal

    private val released = AtomicBoolean(false)
    
    // Executor for mpv calls to avoid blocking UI thread
    private val mpvExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mpv-executor").apply { isDaemon = true }
    }

    // ======================================================================
    // Lifecycle
    // ======================================================================

    override fun initialize(): Boolean {
        // We defer actual mpv_create until we have a window handle
        try {
            LibMpv.INSTANCE
            Log.d("libmpv loaded successfully")
            return true
        } catch (e: Throwable) {
            Log.e("Unexpected error", e)
            onError?.invoke("Failed to load libmpv: ${e.message}")
            return false
        }
    }

    // ...

    override fun play(mediaPath: String, startMs: Long, title: String?) {
        Log.d("MPV play: $mediaPath (start=${startMs}ms)")
        post {
            if (mpvHandle == null && windowHandle != 0L) {
                createMpvInternal(windowHandle)
            }
            val ctx = mpvHandle
            if (ctx == null) {
                Log.d("MPV play deferred - no context yet")
                pendingLoad = PendingLoad(mediaPath, startMs, title)
                return@post
            }
            loadFile(ctx, mediaPath, startMs, title)
        }
    }

    override fun setPreferredLanguages(audio: String, subtitles: String) {
        audioLanguages = audio.trim()
        subtitleLanguages = subtitles.trim()
        setPropertyString("alang", audioLanguages)
        setPropertyString("slang", subtitleLanguages)
    }

    /**
     * loadfile with per-file options: the start position is applied by mpv itself once the file
     * is open (no "wait 500 ms then seek", which failed on slow streams).
     */
    private fun loadFile(ctx: Pointer, mediaPath: String, startMs: Long, title: String?) {
        _isBuffering.value = true
        // Local files as file:/// URIs (handles spaces, [], non-ASCII); streams as-is
        val target = if (mediaPath.contains("://")) mediaPath else try {
            java.io.File(mediaPath).toURI().toString().replaceFirst(Regex("^file:/(?!//)"), "file:///")
        } catch (e: Exception) {
            mediaPath.replace('\\', '/')
        }
        val options = buildList {
            if (startMs > 0) add("start=" + String.format(java.util.Locale.ROOT, "%.3f", startMs / 1000.0))
            // %N% quoting lets the title contain commas and '='
            if (!title.isNullOrBlank()) add("force-media-title=%${title.toByteArray(Charsets.UTF_8).size}%$title")
        }.joinToString(",")
        Log.d("MPV loadfile: $target [$options]")
        if (options.isEmpty()) command(ctx, "loadfile", target, "replace")
        else command(ctx, "loadfile", target, "replace", "0", options)
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        
        mpvExecutor.submit {
            stopInternal()
            destroyMpv()
        }
        mpvExecutor.shutdown()
    }

    // ======================================================================
    // Canvas / window handle
    // ======================================================================

    override fun attachCanvas(canvas: java.awt.Canvas) {
        if (this.canvas === canvas) return
        this.canvas = canvas
        canvas.background = java.awt.Color.BLACK
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

    override fun detachWindow() {
        // Run on executor to avoid blocking UI thread if called from UI
        post {
            destroyMpv()
            windowHandle = 0
            canvas = null
        }
    }

    private fun captureWindowHandle() {
        try {
            val hwnd = Native.getComponentID(canvas!!)
            if (hwnd != windowHandle) {
                windowHandle = hwnd
                Log.d("MPV canvas HWND: $windowHandle")

                post {
                    if (mpvHandle != null) {
                        Log.d("Recreating MPV context due to window change")
                        destroyMpv()
                    }
                    createMpvInternal(hwnd)
                    
                    pendingLoad?.let { pending ->
                        pendingLoad = null
                        mpvHandle?.let { ctx -> loadFile(ctx, pending.path, pending.startMs, pending.title) }
                    }
                 }
            }
        } catch (e: Exception) {
            Log.e("Failed to get HWND: ${e.message}")
            onError?.invoke("Failed to get native window handle: ${e.message}")
        }
    }

    private fun destroyMpv() {
        val ctx = mpvHandle ?: return
        mpvHandle = null
        // Ask the core to quit first so the event thread receives MPV_EVENT_SHUTDOWN and leaves
        // mpv_wait_event(). Destroying the handle while that thread still waits on it is a use-after-free.
        command(ctx, "quit")
        try {
            eventThread?.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        eventThread = null
        LibMpv.INSTANCE.mpv_terminate_destroy(ctx)
    }

    /** Runs [block] on the mpv thread; silently ignored once the player has been released. */
    private fun post(block: () -> Unit) {
        if (released.get()) return
        try {
            mpvExecutor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // executor already shut down
        }
    }

    private fun createMpvInternal(hwnd: Long) {
        if (mpvHandle != null) return

        Log.d("MPV createMpvInternal: start with HWND=$hwnd")
        val ctx = LibMpv.INSTANCE.mpv_create()
        if (ctx == null) {
            Log.e("MPV createMpvInternal: mpv_create failed")
            onError?.invoke("Failed to create mpv context")
            return
        }
        
        try {
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "wid", hwnd.toString())
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "input-default-bindings", "no")
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "input-vo-keyboard", "no")
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "osc", "no")
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "hwdec", "auto-safe")
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "keep-open", "yes")
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "idle", "yes")
            LibMpv.INSTANCE.mpv_set_option_string(ctx, "video-sync", "audio")

            // Streaming from the local HTTP server (data arrives as Telegram downloads it)
            val streamingOptions = listOf(
                "cache" to "yes",
                "cache-secs" to "60",
                "demuxer-readahead-secs" to "60",
                "demuxer-max-bytes" to "512MiB",
                "demuxer-max-back-bytes" to "128MiB",
                "stream-buffer-size" to "4MiB",
                "network-timeout" to "60",           // Telegram can stall for a while on a seek
                "cache-pause-initial" to "yes",      // buffer a little before starting: fewer stutters
                "force-seekable" to "yes",
                "hr-seek" to "yes",                  // exact seeks (resume lands where you left)
                "demuxer-mkv-subtitle-preroll" to "index" // MKV: show subtitles right after a seek
            )
            for ((name, value) in streamingOptions) setOption(ctx, name, value)
            if (audioLanguages.isNotEmpty()) setOption(ctx, "alang", audioLanguages)
            if (subtitleLanguages.isNotEmpty()) setOption(ctx, "slang", subtitleLanguages)
            
            // Enable logging
            Log.d("MPV createMpvInternal: enabling logging")
            LibMpv.INSTANCE.mpv_request_log_messages(ctx, "warn")
            
            Log.d("MPV createMpvInternal: initializing...")
            val res = LibMpv.INSTANCE.mpv_initialize(ctx)
            if (res < 0) {
                Log.e("MPV createMpvInternal: initialize failed: $res")
                onError?.invoke("Failed to initialize mpv: " + LibMpv.INSTANCE.mpv_error_string(res))
                LibMpv.INSTANCE.mpv_destroy(ctx)
                return
            }
            Log.d("MPV createMpvInternal: initialized successfully")
            
            mpvHandle = ctx
            
            // Observe properties
            observeProperty(ctx, "time-pos", LibMpv.MPV_FORMAT_DOUBLE)
            observeProperty(ctx, "duration", LibMpv.MPV_FORMAT_DOUBLE)
            observeProperty(ctx, "pause", LibMpv.MPV_FORMAT_FLAG)
            observeProperty(ctx, "volume", LibMpv.MPV_FORMAT_DOUBLE)
            observeProperty(ctx, "eof-reached", LibMpv.MPV_FORMAT_FLAG)
            observeProperty(ctx, "track-list", LibMpv.MPV_FORMAT_NODE)
            observeProperty(ctx, "paused-for-cache", LibMpv.MPV_FORMAT_FLAG)

            // Start event loop
            Log.d("MPV createMpvInternal: starting event loop")
            eventThread = Thread { eventLoop(ctx) }.apply { 
                isDaemon = true
                name = "mpv-event-loop"
                start() 
            }
            
        } catch (e: Exception) {
            Log.e("Unexpected error", e)
            if (mpvHandle != null) {
                 LibMpv.INSTANCE.mpv_destroy(ctx)
                 mpvHandle = null
            }
        }
    }
    
    private fun setOption(ctx: Pointer, name: String, value: String) {
        val res = LibMpv.INSTANCE.mpv_set_option_string(ctx, name, value)
        if (res < 0) Log.w("mpv option $name=$value rejected: ${LibMpv.INSTANCE.mpv_error_string(res)}")
    }

    private fun observeProperty(ctx: Pointer, name: String, format: Int) {
        LibMpv.INSTANCE.mpv_observe_property(ctx, 0, name, format)
    }

    private fun eventLoop(ctx: Pointer) {
        Log.d("MPV event loop started")
        while (true) {
            val eventPtr = LibMpv.INSTANCE.mpv_wait_event(ctx, -1.0)
            val event = MpvEvent(eventPtr)
            
            if (event.event_id == LibMpv.MPV_EVENT_SHUTDOWN) {
                Log.d("MPV shutdown event received")
                break
            } else if (event.event_id == LibMpv.MPV_EVENT_LOG_MESSAGE) {
                val msg = MpvEventLogMessage(event.data)
                Log.d("[MPV] [${msg.level}] ${msg.prefix}: ${msg.text}")
            }
            
            if (event.event_id == LibMpv.MPV_EVENT_PROPERTY_CHANGE) {
                val prop = MpvEventProperty(event.data)
                handlePropertyChange(prop)
            } else if (event.event_id == LibMpv.MPV_EVENT_END_FILE) {
                val endFile = MpvEventEndFile(event.data)
                if (endFile.reason == 0) { // MPV_END_FILE_REASON_EOF (end handled via "eof-reached")
                     _isPlaying.value = false
                } else if (endFile.error != 0) {
                     val errStr = LibMpv.INSTANCE.mpv_error_string(endFile.error)
                     Log.e("[MPV] End file error: $errStr")
                     onError?.invoke("Playback error: $errStr")
                }
            } else if (event.event_id == LibMpv.MPV_EVENT_FILE_LOADED) {
                _isBuffering.value = false
            } else if (event.event_id == LibMpv.MPV_EVENT_SEEK) {
                _isBuffering.value = true
            } else if (event.event_id == LibMpv.MPV_EVENT_PLAYBACK_RESTART) {
                _isBuffering.value = false
            }
        }
        Log.d("MPV event loop ended")
    }

    private fun handlePropertyChange(prop: MpvEventProperty) {
        val name = prop.name ?: return
        val data = prop.data
        
        when (name) {
            "track-list" -> {
                Log.d("MPV track-list event: format=${prop.format}, data=$data")
                if (prop.format == LibMpv.MPV_FORMAT_NODE && data != null) {
                    val node = MpvNode(data)
                    val list = node.getList()
                    Log.d("MPV track-list node: format=${node.format}, list=$list")
                    if (list != null) {
                        Log.d("MPV track-list list: num=${list.num}, values=${list.values}, keys=${list.keys}")
                        parseTrackList(list)
                    }
                }
            }
            "time-pos" -> {
                if (prop.format == LibMpv.MPV_FORMAT_DOUBLE && data != null) {
                    val pos = data.getDouble(0)
                    _currentPosition.value = (pos * 1000).toLong()
                }
            }
            "duration" -> {
                if (prop.format == LibMpv.MPV_FORMAT_DOUBLE && data != null) {
                    val dur = data.getDouble(0)
                    _duration.value = (dur * 1000).toLong()
                }
            }
            "pause" -> {
                if (prop.format == LibMpv.MPV_FORMAT_FLAG && data != null) {
                    val paused = data.getInt(0) != 0
                    _isPlaying.value = !paused
                }
            }
            "volume" -> {
                if (prop.format == LibMpv.MPV_FORMAT_DOUBLE && data != null) {
                    val vol = data.getDouble(0)
                    _volume.value = (vol / 100.0).toFloat().coerceIn(0f, 1f)
                }
            }
            "eof-reached" -> {
                 if (prop.format == LibMpv.MPV_FORMAT_FLAG && data != null) {
                    val reached = data.getInt(0) != 0
                    if (reached) {
                        _isPlaying.value = false
                        onMediaEnd?.invoke()
                    }
                }
            }
            "paused-for-cache" -> {
                 if (prop.format == LibMpv.MPV_FORMAT_FLAG && data != null) {
                    val buffering = data.getInt(0) != 0
                    _isBuffering.value = buffering
                }
            }
        }
    }
    
    private fun parseTrackList(list: MpvNodeList) {
        Log.d("parseTrackList: num=${list.num}")
        val audio = mutableListOf<MediaPlayerEngine.TrackInfo>()
        val subs = mutableListOf<MediaPlayerEngine.TrackInfo>()

        for (i in 0 until list.num) {
            val node = list.getNodeAt(i)
            Log.d("parseTrackList: track[$i] node=$node format=${node?.format}")
            if (node == null || node.format != LibMpv.MPV_FORMAT_NODE_MAP) continue
            val trackMap = node.getList()
            Log.d("parseTrackList: track[$i] map=$trackMap num=${trackMap?.num} values=${trackMap?.values} keys=${trackMap?.keys}")
            if (trackMap == null) continue
            
            var type: String? = null
            var id: Int? = null
            var title: String? = null
            var lang: String? = null
            var codec: String? = null
            var selected = false
            
            for (j in 0 until trackMap.num) {
                val key = trackMap.getKeyAt(j)
                val valNode = trackMap.getNodeAt(j)
                Log.d("parseTrackList: track[$i] field[$j] key=$key valNode=$valNode")
                if (key == null || valNode == null) continue
                
                when (key) {
                    "type" -> { type = valNode.getString(); Log.d("  -> type=$type") }
                    "id" -> { id = valNode.getLong().toInt(); Log.d("  -> id=$id") }
                    "title" -> { title = valNode.getString(); Log.d("  -> title=$title") }
                    "lang" -> { lang = valNode.getString(); Log.d("  -> lang=$lang") }
                    "codec" -> { codec = valNode.getString(); Log.d("  -> codec=$codec") }
                    "selected" -> { selected = valNode.getBoolean(); Log.d("  -> selected=$selected") }
                }
            }
            
            if (type == null || id == null) {
                Log.d("parseTrackList: track[$i] SKIPPED (type=$type, id=$id)")
                continue
            }
            
            val displayName = buildString {
                append(title ?: "Track $id")
                if (lang != null) append(" [$lang]")
                if (codec != null && codec.isNotEmpty()) append(" ($codec)")
            }
            Log.d("parseTrackList: track[$i] type=$type id=$id name=$displayName")

            if (type == "audio") {
                audio.add(MediaPlayerEngine.TrackInfo(id, displayName))
                if (selected) _currentAudioTrackId = id
            } else if (type == "sub") {
                subs.add(MediaPlayerEngine.TrackInfo(id, displayName))
                if (selected) _currentSubtitleTrackId = id
            }
        }
        _audioTracks = audio
        _subtitleTracks = subs
        _tracksChangeSignal.value = _tracksChangeSignal.value + 1
        Log.d("parseTrackList: result: ${audio.size} audio, ${subs.size} subtitle")
    }

    // ======================================================================
    // Playback controls
    // ======================================================================



    override fun pause() {
        setPropertyBoolean("pause", true)
    }

    override fun resume() {
        setPropertyBoolean("pause", false)
    }

    override fun togglePlayPause() {
        post {
            val ctx = mpvHandle ?: return@post
            command(ctx, "cycle", "pause")
        }
    }

    override fun stop() {
        post {
            stopInternal()
        }
    }
    
    private fun stopInternal() {
        val ctx = mpvHandle ?: return
        command(ctx, "stop")
        _isPlaying.value = false
        _currentPosition.value = 0
        _duration.value = 0
    }

    override fun seekTo(position: Long) {
        val seconds = position / 1000.0
        setPropertyDouble("time-pos", seconds)
    }

    override fun setVolume(vol: Float) {
        val mpvVol = (vol * 100).toDouble().coerceIn(0.0, 100.0)
        setPropertyDouble("volume", mpvVol)
        _volume.value = vol
    }

    override fun setPlaybackSpeed(speed: Float) {
        setPropertyDouble("speed", speed.toDouble())
    }

    // ======================================================================
    // Track management
    // ======================================================================

    override fun getAudioTracks(): List<MediaPlayerEngine.TrackInfo> = _audioTracks
    override fun getCurrentAudioTrack(): Int = _currentAudioTrackId
    override fun setAudioTrack(trackId: Int) {
        if (trackId <= 0) setPropertyString("aid", "no")
        else setPropertyString("aid", trackId.toString())
        _currentAudioTrackId = trackId
    }

    override fun getSubtitleTracks(): List<MediaPlayerEngine.TrackInfo> = _subtitleTracks
    override fun getCurrentSubtitleTrack(): Int = _currentSubtitleTrackId
    override fun setSubtitleTrack(trackId: Int) {
        if (trackId <= 0) setPropertyString("sid", "no")
        else setPropertyString("sid", trackId.toString())
        _currentSubtitleTrackId = trackId
    }

    // ======================================================================
    // Helpers
    // ======================================================================
    
    private fun command(ctx: Pointer, vararg args: String) {
        val cmdArgs = arrayOfNulls<String>(args.size + 1)
        for (i in args.indices) cmdArgs[i] = args[i]
        cmdArgs[args.size] = null
        
        LibMpv.INSTANCE.mpv_command_async(ctx, 0, cmdArgs)
    }
    
    private fun setPropertyBoolean(name: String, value: Boolean) {
        post {
             val ctx = mpvHandle ?: return@post
             val flag = Memory(4)
             flag.setInt(0, if (value) 1 else 0)
             LibMpv.INSTANCE.mpv_set_property_async(ctx, 0, name, LibMpv.MPV_FORMAT_FLAG, flag)
        }
    }
    
    private fun setPropertyDouble(name: String, value: Double) {
        post {
             val ctx = mpvHandle ?: return@post
             val d = Memory(8)
             d.setDouble(0, value)
             LibMpv.INSTANCE.mpv_set_property_async(ctx, 0, name, LibMpv.MPV_FORMAT_DOUBLE, d)
        }
    }

    private fun setPropertyString(name: String, value: String) {
        post {
            val ctx = mpvHandle ?: return@post
            LibMpv.INSTANCE.mpv_set_property_string(ctx, name, value)
        }
    }
}
