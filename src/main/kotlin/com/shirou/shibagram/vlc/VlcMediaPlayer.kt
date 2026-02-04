package com.shirou.shibagram.vlc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.Component
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.IntBuffer

/**
 * VLC media player wrapper for desktop video playback.
 * Uses callback rendering to expose frames as Compose ImageBitmap.
 */
class VlcMediaPlayer {
    private var mediaPlayerFactory: MediaPlayerFactory? = null
    private var mediaPlayer: EmbeddedMediaPlayer? = null
    
    // Video frame as Compose ImageBitmap for direct rendering
    private val _currentFrame = MutableStateFlow<ImageBitmap?>(null)
    val currentFrame: StateFlow<ImageBitmap?> = _currentFrame
    
    private var frameBuffer: BufferedImage? = null
    private var pixelBuffer: IntArray? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var frameCounter = 0
    private val FRAME_SKIP = 1 // Render every N frames (1 = all frames)
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition
    
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration
    
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume
    
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering
    
    private var vlcFound = false
    
    var onMediaEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    /**
     * Initialize VLC - returns true if successful
     */
    fun initialize(): Boolean {
        return try {
            // Discover VLC installation
            vlcFound = NativeDiscovery().discover()
            println("VLC discovered: $vlcFound")
            
            if (!vlcFound) {
                println("VLC not found. Please install VLC media player.")
                onError?.invoke("VLC not found. Please install VLC media player.")
                return false
            }
            
            // Create media player factory with optimized options
            mediaPlayerFactory = MediaPlayerFactory(
                "--no-video-title-show",      // Don't show title
                "--vout=vmem",                // Use memory video output
                "--no-snapshot-preview",       // Disable snapshot previews
                "--quiet",                     // Reduce log spam
                "--file-caching=1000",         // 1 second file cache
                "--live-caching=1000",         // 1 second live cache
                "--network-caching=1000"       // 1 second network cache
            )
            mediaPlayer = mediaPlayerFactory?.mediaPlayers()?.newEmbeddedMediaPlayer()
            
            // Set up callback video surface for software rendering
            val bufferFormatCallback = object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    videoWidth = sourceWidth
                    videoHeight = sourceHeight
                    frameBuffer = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_ARGB)
                    println("Video format: ${sourceWidth}x${sourceHeight}")
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
                
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {
                    // Buffers allocated
                }
            }
            
            val renderCallback = RenderCallback { _, nativeBuffers, bufferFormat ->
                renderFrame(nativeBuffers[0], bufferFormat.width, bufferFormat.height)
            }
            
            val videoSurface = mediaPlayerFactory?.videoSurfaces()?.newVideoSurface(bufferFormatCallback, renderCallback, true)
            mediaPlayer?.videoSurface()?.set(videoSurface)
            
            mediaPlayer?.events()?.addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = true
                    _isBuffering.value = false
                }
                
                override fun paused(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                }
                
                override fun stopped(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    _currentPosition.value = 0
                }
                
                override fun finished(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    onMediaEnd?.invoke()
                }
                
                override fun error(mediaPlayer: MediaPlayer) {
                    _isPlaying.value = false
                    _isBuffering.value = false
                    onError?.invoke("Playback error occurred")
                }
                
                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                    _isBuffering.value = newCache < 100f
                }
                
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    _currentPosition.value = newTime
                }
                
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    _duration.value = newLength
                }

                override fun volumeChanged(mediaPlayer: MediaPlayer, vol: Float) {
                    _volume.value = vol
                }
            })
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            onError?.invoke("Failed to initialize VLC: ${e.message}")
            false
        }
    }
    
    private fun renderFrame(buffer: ByteBuffer, width: Int, height: Int) {
        // Skip frames for performance if needed
        frameCounter++
        if (frameCounter % FRAME_SKIP != 0) return
        
        frameBuffer?.let { img ->
            try {
                buffer.rewind()
                
                // Get direct access to the BufferedImage pixel array for faster writes
                val raster = img.raster
                val dataBuffer = raster.dataBuffer as? DataBufferInt
                val destPixels = dataBuffer?.data ?: run {
                    // Fallback: use pre-allocated pixel buffer
                    if (pixelBuffer == null || pixelBuffer!!.size != width * height) {
                        pixelBuffer = IntArray(width * height)
                    }
                    pixelBuffer!!
                }
                
                // Bulk read BGRA pixels and convert to ARGB
                val pixelCount = width * height
                val intBuffer = buffer.asIntBuffer()
                
                // Bulk copy to temp array then convert
                if (pixelBuffer == null || pixelBuffer!!.size != pixelCount) {
                    pixelBuffer = IntArray(pixelCount)
                }
                intBuffer.get(pixelBuffer!!)
                
                // Convert BGRA to ARGB in place
                val pixels = pixelBuffer!!
                for (i in 0 until pixelCount) {
                    val bgra = pixels[i]
                    // BGRA -> ARGB: swap B and R (VLC sends BGRA, we need ARGB)
                    val b = (bgra shr 0) and 0xFF
                    val g = (bgra shr 8) and 0xFF
                    val r = (bgra shr 16) and 0xFF
                    destPixels[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                }
                
                // If we used fallback buffer, copy to image
                if (dataBuffer == null) {
                    img.setRGB(0, 0, width, height, destPixels, 0, width)
                }
                
                // Convert to Compose ImageBitmap
                _currentFrame.value = img.toComposeImageBitmap()
            } catch (e: Exception) {
                // Ignore frame rendering errors
            }
        }
    }
    
    /**
     * Get the video surface component for embedding in UI (legacy - not used with Compose)
     */
    fun getVideoSurface(): Component? = null

    fun play(mediaPath: String) {
        try {
            // Check if this is an HTTP stream and add appropriate options
            if (mediaPath.startsWith("http://") || mediaPath.startsWith("https://")) {
                // Add caching options for HTTP streaming
                mediaPlayer?.media()?.play(mediaPath,
                    ":network-caching=5000",       // 5 second network cache
                    ":file-caching=5000",          // 5 second file cache  
                    ":live-caching=5000",          // 5 second live cache
                    ":http-reconnect",             // Auto reconnect on connection loss
                    ":http-continuous",            // Continuous HTTP streaming
                    ":no-hw-dec",                  // Disable hardware decoding for better compatibility
                    ":codec=avcodec",              // Use FFmpeg/libav codecs
                    ":avcodec-hw=none"             // No hardware acceleration
                )
            } else {
                mediaPlayer?.media()?.play(mediaPath)
            }
        } catch (e: Exception) {
            onError?.invoke("Failed to play media: ${e.message}")
        }
    }
    
    fun playUrl(url: String) {
        try {
            mediaPlayer?.media()?.play(url)
        } catch (e: Exception) {
            onError?.invoke("Failed to play URL: ${e.message}")
        }
    }
    
    fun pause() {
        mediaPlayer?.controls()?.pause()
    }
    
    fun resume() {
        mediaPlayer?.controls()?.play()
    }
    
    fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }
    
    fun stop() {
        mediaPlayer?.controls()?.stop()
    }
    
    fun seekTo(position: Long) {
        mediaPlayer?.controls()?.setTime(position)
    }
    
    fun seekToPercentage(percentage: Float) {
        mediaPlayer?.controls()?.setPosition(percentage)
    }
    
    fun setVolume(vol: Float) {
        val volumeInt = (vol * 100).toInt().coerceIn(0, 100)
        mediaPlayer?.audio()?.setVolume(volumeInt)
        _volume.value = vol
    }
    
    fun mute() {
        mediaPlayer?.audio()?.mute()
    }
    
    fun unmute() {
        mediaPlayer?.audio()?.setMute(false)
    }
    
    fun toggleMute() {
        mediaPlayer?.audio()?.let { audio ->
            audio.setMute(!audio.isMute)
        }
    }
    
    fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.controls()?.setRate(speed)
    }
    
    /**
     * Get list of available audio tracks
     */
    fun getAudioTracks(): List<TrackInfo> {
        return try {
            val trackDescriptions = mediaPlayer?.audio()?.trackDescriptions() ?: emptyList()
            trackDescriptions.map { desc ->
                TrackInfo(
                    id = desc.id(),
                    name = desc.description() ?: "Track ${desc.id()}"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get current audio track ID
     */
    fun getCurrentAudioTrack(): Int {
        return try {
            mediaPlayer?.audio()?.track() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Set audio track by ID
     */
    fun setAudioTrack(trackId: Int) {
        try {
            mediaPlayer?.audio()?.setTrack(trackId)
        } catch (e: Exception) {
            println("Error setting audio track: ${e.message}")
        }
    }
    
    /**
     * Get list of available subtitle tracks
     */
    fun getSubtitleTracks(): List<TrackInfo> {
        return try {
            val trackDescriptions = mediaPlayer?.subpictures()?.trackDescriptions() ?: emptyList()
            trackDescriptions.map { desc ->
                TrackInfo(
                    id = desc.id(),
                    name = desc.description() ?: "Subtitle ${desc.id()}"
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get current subtitle track ID (-1 means disabled)
     */
    fun getCurrentSubtitleTrack(): Int {
        return try {
            mediaPlayer?.subpictures()?.track() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Set subtitle track by ID (-1 to disable)
     */
    fun setSubtitleTrack(trackId: Int) {
        try {
            mediaPlayer?.subpictures()?.setTrack(trackId)
        } catch (e: Exception) {
            println("Error setting subtitle track: ${e.message}")
        }
    }
    
    /**
     * Data class for track information
     */
    data class TrackInfo(
        val id: Int,
        val name: String
    )
    
    fun getSnapshot(): java.awt.image.BufferedImage? {
        return mediaPlayer?.snapshots()?.get()
    }
    
    fun release() {
        mediaPlayer?.controls()?.stop()
        mediaPlayer?.release()
        mediaPlayerFactory?.release()
        mediaPlayer = null
        mediaPlayerFactory = null
        frameBuffer = null
        _currentFrame.value = null
    }
    
    fun isInitialized(): Boolean = mediaPlayer != null && vlcFound
}
