package com.shirou.shibagram.player

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
    @Volatile private var pendingMediaPath: String? = null
    
    // Track info
    @Volatile private var _audioTracks = listOf<MediaPlayerEngine.TrackInfo>()
    @Volatile private var _subtitleTracks = listOf<MediaPlayerEngine.TrackInfo>()
    @Volatile private var _currentAudioTrackId = -1
    @Volatile private var _currentSubtitleTrackId = -1

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
            // Ensure JNA uses UTF-8 for native strings (libmpv expects UTF-8)
            System.setProperty("jna.encoding", "UTF8")
            
            LibMpv.INSTANCE
            println("libmpv loaded successfully")
            return true
        } catch (e: Throwable) {
            e.printStackTrace()
            onError?.invoke("Failed to load libmpv: ${e.message}")
            return false
        }
    }

    // ...

    override fun play(mediaPath: String) {
        println("MPV play() called with: $mediaPath")
        
        // Check if file exists to debug "No such file" errors
        val file = java.io.File(mediaPath)
        if (file.exists()) {
             println("MPV play(): File exists. Size: ${file.length()}")
        } else {
             println("MPV play(): WARNING - File does not exist at path: $mediaPath")
        }

        mpvExecutor.submit {
            println("MPV play() task started for: $mediaPath")
            if (mpvHandle == null && windowHandle != 0L) {
                createMpvInternal(windowHandle)
            }
            
            val ctx = mpvHandle
            if (ctx == null) {
                println("MPV play deferred - no context yet")
                pendingMediaPath = mediaPath
                return@submit
            }

            _isBuffering.value = true
            
            // formatting: use File.toURI() to handle spaces and special chars ([], etc)
            // formatting: use File.toURI() to handle spaces and special chars ([], etc)
            val validPath: String = try {
                if (mediaPath.startsWith("http") || mediaPath.startsWith("rtsp") || mediaPath.startsWith("udp")) {
                    mediaPath
                } else {
                    val f = java.io.File(mediaPath)
                    var uri = f.toURI().toString()
                    // Fix for Windows: MPV might need file:/// not file:/
                    if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
                         uri = uri.replaceFirst("file:/", "file:///")
                    }
                    uri
                }
            } catch (e: Exception) {
                mediaPath.replace('\\', '/')
            }
            
            println("MPV loadfile: $validPath")
            command(ctx, "loadfile", validPath, "replace")
        }
    }

    override fun isInitialized(): Boolean = true

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
        mpvExecutor.submit {
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
                println("MPV canvas HWND: $windowHandle")

                mpvExecutor.submit {
                    if (mpvHandle != null) {
                        println("Recreating MPV context due to window change")
                        destroyMpv()
                    }
                    createMpvInternal(hwnd)
                    
                     val pending = pendingMediaPath
                     if (pending != null) {
                         pendingMediaPath = null
                         val ctx = mpvHandle
                         if (ctx != null) {
                              _isBuffering.value = true
                              
                              // formatting: use File.toURI() to handle spaces and special chars ([], etc)
                              val validPath: String = try {
                                  if (pending.startsWith("http") || pending.startsWith("rtsp") || pending.startsWith("udp")) {
                                      pending
                                  } else {
                                      val f = java.io.File(pending)
                                      var uri = f.toURI().toString()
                                      // Fix for Windows: MPV might need file:/// not file:/
                                      if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
                                           uri = uri.replaceFirst("file:/", "file:///")
                                      }
                                      uri
                                  }
                              } catch (e: Exception) {
                                  pending.replace('\\', '/')
                              }
                              
                              println("MPV loadfile pending: $validPath")
                              command(ctx, "loadfile", validPath, "replace")
                         }
                     }
                 }
            }
        } catch (e: Exception) {
            println("Failed to get HWND: ${e.message}")
            onError?.invoke("Failed to get native window handle: ${e.message}")
        }
    }

    private fun destroyMpv() {
        val ctx = mpvHandle ?: return
        println("MPV destroyMpv: destroying context...")
        LibMpv.INSTANCE.mpv_terminate_destroy(ctx)
        mpvHandle = null
        try { 
            println("MPV destroyMpv: waiting for event thread...")
            eventThread?.join(2000) // Wait max 2 seconds
            println("MPV destroyMpv: event thread finished or timed out")
        } catch (e: Exception) {
            println("MPV destroyMpv: Exception waiting for event thread: $e")
        }
        eventThread = null
    }

    private fun createMpvInternal(hwnd: Long) {
        if (mpvHandle != null) return

        println("MPV createMpvInternal: start with HWND=$hwnd")
        val ctx = LibMpv.INSTANCE.mpv_create()
        if (ctx == null) {
            println("MPV createMpvInternal: mpv_create failed")
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
            
            // Enable logging
            println("MPV createMpvInternal: enabling logging")
            LibMpv.INSTANCE.mpv_request_log_messages(ctx, "warn")
            
            println("MPV createMpvInternal: initializing...")
            val res = LibMpv.INSTANCE.mpv_initialize(ctx)
            if (res < 0) {
                println("MPV createMpvInternal: initialize failed: $res")
                onError?.invoke("Failed to initialize mpv: " + LibMpv.INSTANCE.mpv_error_string(res))
                LibMpv.INSTANCE.mpv_destroy(ctx)
                return
            }
            println("MPV createMpvInternal: initialized successfully")
            
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
            println("MPV createMpvInternal: starting event loop")
            eventThread = Thread { eventLoop(ctx) }.apply { 
                isDaemon = true
                name = "mpv-event-loop"
                start() 
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            if (mpvHandle != null) {
                 LibMpv.INSTANCE.mpv_destroy(ctx)
                 mpvHandle = null
            }
        }
    }
    
    private fun observeProperty(ctx: Pointer, name: String, format: Int) {
        LibMpv.INSTANCE.mpv_observe_property(ctx, 0, name, format)
    }

    private fun eventLoop(ctx: Pointer) {
        println("MPV event loop started")
        while (true) {
            val eventPtr = LibMpv.INSTANCE.mpv_wait_event(ctx, -1.0)
            val event = MpvEvent(eventPtr)
            
            if (event.event_id == LibMpv.MPV_EVENT_SHUTDOWN) {
                println("MPV shutdown event received")
                break
            } else if (event.event_id == LibMpv.MPV_EVENT_LOG_MESSAGE) {
                val msg = MpvEventLogMessage(event.data)
                println("[MPV] [${msg.level}] ${msg.prefix}: ${msg.text}")
            }
            
            if (event.event_id == LibMpv.MPV_EVENT_PROPERTY_CHANGE) {
                val prop = MpvEventProperty(event.data)
                handlePropertyChange(prop)
            } else if (event.event_id == LibMpv.MPV_EVENT_END_FILE) {
                val endFile = MpvEventEndFile(event.data)
                if (endFile.reason == 0) { // MPV_END_FILE_REASON_EOF
                     _isPlaying.value = false
                     onMediaEnd?.invoke()
                } else if (endFile.error != 0) {
                     val errStr = LibMpv.INSTANCE.mpv_error_string(endFile.error)
                     println("[MPV] End file error: $errStr")
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
        println("MPV event loop ended")
    }

    private fun handlePropertyChange(prop: MpvEventProperty) {
        val name = prop.name ?: return
        val data = prop.data
        
        when (name) {
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
            "track-list" -> {
                if (prop.format == LibMpv.MPV_FORMAT_NODE && data != null) {
                    val node = MpvNode(data)
                    val list = node.getList()
                    if (list != null) {
                        parseTrackList(list)
                    }
                }
            }
        }
    }
    
    private fun parseTrackList(list: MpvNodeList) {
        val audio = mutableListOf<MediaPlayerEngine.TrackInfo>()
        val subs = mutableListOf<MediaPlayerEngine.TrackInfo>()

        for (i in 0 until list.num) {
            val node = list.getNodeAt(i) ?: continue
            if (node.format != LibMpv.MPV_FORMAT_NODE_MAP) continue
            val trackMap = node.getList() ?: continue
            
            var type: String? = null
            var id: Int? = null
            var title: String? = null
            var lang: String? = null
            var codec: String? = null
            var selected = false
            
            for (j in 0 until trackMap.num) {
                val key = trackMap.getKeyAt(j) ?: continue
                val valNode = trackMap.getNodeAt(j) ?: continue
                
                when (key) {
                    "type" -> type = valNode.getString()
                    "id" -> id = valNode.getLong().toInt()
                    "title" -> title = valNode.getString()
                    "lang" -> lang = valNode.getString()
                    "codec" -> codec = valNode.getString()
                    "selected" -> selected = valNode.getBoolean()
                }
            }
            
            if (type == null || id == null) continue
            
            val displayName = buildString {
                append(title ?: "Track $id")
                if (lang != null) append(" [$lang]")
                if (codec != null && codec.isNotEmpty()) append(" ($codec)")
            }

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
        val ctx = mpvHandle ?: return
        mpvExecutor.submit {
            command(ctx, "cycle", "pause")
        }
    }

    override fun stop() {
        mpvExecutor.submit {
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
        mpvExecutor.submit {
             val ctx = mpvHandle ?: return@submit
             val flag = Memory(4)
             flag.setInt(0, if (value) 1 else 0)
             LibMpv.INSTANCE.mpv_set_property_async(ctx, 0, name, LibMpv.MPV_FORMAT_FLAG, flag)
        }
    }
    
    private fun setPropertyDouble(name: String, value: Double) {
        mpvExecutor.submit {
             val ctx = mpvHandle ?: return@submit
             val d = Memory(8)
             d.setDouble(0, value)
             LibMpv.INSTANCE.mpv_set_property_async(ctx, 0, name, LibMpv.MPV_FORMAT_DOUBLE, d)
        }
    }

    private fun setPropertyString(name: String, value: String) {
        mpvExecutor.submit {
            val ctx = mpvHandle ?: return@submit
            LibMpv.INSTANCE.mpv_set_property_string(ctx, name, value)
        }
    }
}
