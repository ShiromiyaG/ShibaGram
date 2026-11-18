package com.shirou.shibagram.vlc

import com.shirou.shibagram.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.shirou.shibagram.player.MediaPlayerEngine
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
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer

/**
 * VLC media player wrapper for desktop video playback.
 * Uses callback rendering to expose frames as Compose ImageBitmap.
 */
class VlcMediaPlayer : MediaPlayerEngine {
    private var mediaPlayerFactory: MediaPlayerFactory? = null
    private var mediaPlayer: EmbeddedMediaPlayer? = null
    
    // Video frame as Compose ImageBitmap for direct rendering
    private val _currentFrame = MutableStateFlow<ImageBitmap?>(null)
    override val currentFrame: StateFlow<ImageBitmap?> = _currentFrame
    
    // Written on VLC's format callback thread, read on its render thread
    @Volatile private var frameBuffer: BufferedImage? = null
    
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

    private val _tracksChangeSignal = MutableStateFlow(0)
    override val tracksChangeSignal: StateFlow<Int> = _tracksChangeSignal
    
    private var vlcFound = false
    
    override var onMediaEnd: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    
    /**
     * Initialize VLC - returns true if successful
     */
    override fun initialize(): Boolean {
        return try {
            // Discover VLC installation
            vlcFound = NativeDiscovery().discover()
            Log.d("VLC discovered: $vlcFound")
            
            if (!vlcFound) {
                Log.d("VLC not found. Please install VLC media player.")
                onError?.invoke("VLC not found. Please install VLC media player.")
                return false
            }
            
            // Create media player factory with optimized options
            mediaPlayerFactory = MediaPlayerFactory(
                "--no-video-title-show",      // Don't show title
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
                    frameBuffer = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB)
                    Log.d("Video format: ${sourceWidth}x${sourceHeight}")
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
            Log.e("Unexpected error", e)
            onError?.invoke("Failed to initialize VLC: ${e.message}")
            false
        }
    }
    
    private fun renderFrame(buffer: ByteBuffer, width: Int, height: Int) {
        val img = frameBuffer ?: return
        try {
            // RV32 delivers 0x00RRGGBB ints in native byte order: the same layout as TYPE_INT_RGB,
            // so the frame is copied straight into the image (no per-pixel conversion loop).
            val dest = (img.raster.dataBuffer as DataBufferInt).data
            buffer.rewind()
            buffer.asIntBuffer().get(dest, 0, minOf(dest.size, width * height))
            _currentFrame.value = img.toComposeImageBitmap()
        } catch (e: Exception) {
            Log.e("VLC frame render error: ${e.message}")
        }
    }
    
    override fun play(mediaPath: String) {
        try {
            if (mediaPath.startsWith("http://") || mediaPath.startsWith("https://")) {
                // Local HTTP stream from VideoStreamingServer: larger network cache + auto reconnect
                mediaPlayer?.media()?.play(mediaPath, ":network-caching=5000", ":http-reconnect")
            } else {
                mediaPlayer?.media()?.play(mediaPath)
            }
        } catch (e: Exception) {
            onError?.invoke("Failed to play media: ${e.message}")
        }
    }

    override fun pause() {
        mediaPlayer?.controls()?.pause()
    }
    
    override fun resume() {
        mediaPlayer?.controls()?.play()
    }
    
    override fun togglePlayPause() {
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }
    
    override fun stop() {
        mediaPlayer?.controls()?.stop()
    }
    
    override fun seekTo(position: Long) {
        mediaPlayer?.controls()?.setTime(position)
    }
    
    override fun setVolume(vol: Float) {
        val v = vol.coerceIn(0f, 1f)
        mediaPlayer?.audio()?.setVolume((v * 100).toInt())
        _volume.value = v
    }
    
    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.controls()?.setRate(speed)
    }
    
    /**
     * Get list of available audio tracks
     */
    override fun getAudioTracks(): List<MediaPlayerEngine.TrackInfo> {
        return try {
            val trackDescriptions = mediaPlayer?.audio()?.trackDescriptions() ?: emptyList()
            trackDescriptions.map { desc ->
                MediaPlayerEngine.TrackInfo(
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
    override fun getCurrentAudioTrack(): Int {
        return try {
            mediaPlayer?.audio()?.track() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Set audio track by ID
     */
    override fun setAudioTrack(trackId: Int) {
        try {
            mediaPlayer?.audio()?.setTrack(trackId)
        } catch (e: Exception) {
            Log.e("Error setting audio track: ${e.message}")
        }
    }
    
    /**
     * Get list of available subtitle tracks
     */
    override fun getSubtitleTracks(): List<MediaPlayerEngine.TrackInfo> {
        return try {
            val trackDescriptions = mediaPlayer?.subpictures()?.trackDescriptions() ?: emptyList()
            trackDescriptions.map { desc ->
                MediaPlayerEngine.TrackInfo(
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
    override fun getCurrentSubtitleTrack(): Int {
        return try {
            mediaPlayer?.subpictures()?.track() ?: -1
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * Set subtitle track by ID (-1 to disable)
     */
    override fun setSubtitleTrack(trackId: Int) {
        try {
            mediaPlayer?.subpictures()?.setTrack(trackId)
        } catch (e: Exception) {
            Log.e("Error setting subtitle track: ${e.message}")
        }
    }
    
    /**
     * Data class for track information — delegates to MediaPlayerEngine.TrackInfo
     */
    // TrackInfo is now defined in MediaPlayerEngine interface
    
    override fun release() {
        mediaPlayer?.controls()?.stop()
        mediaPlayer?.release()
        mediaPlayerFactory?.release()
        mediaPlayer = null
        mediaPlayerFactory = null
        frameBuffer = null
        _currentFrame.value = null
    }
    
}
