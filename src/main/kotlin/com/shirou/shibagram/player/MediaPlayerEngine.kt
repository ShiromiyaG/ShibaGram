package com.shirou.shibagram.player

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for media player backends (VLC, mpv, etc.).
 * Both VlcMediaPlayer and MpvMediaPlayer implement this interface.
 */
interface MediaPlayerEngine {

    // ---- Observable state -----------------------------------------------

    /** Current video frame as an ImageBitmap (VLC software rendering). Null for native-window players. */
    val currentFrame: StateFlow<ImageBitmap?>
    val isPlaying: StateFlow<Boolean>
    /** Playback position in milliseconds. */
    val currentPosition: StateFlow<Long>
    /** Media duration in milliseconds. */
    val duration: StateFlow<Long>
    /** Volume 0.0 – 1.0. */
    val volume: StateFlow<Float>
    val isBuffering: StateFlow<Boolean>

    // ---- Callbacks ------------------------------------------------------

    var onMediaEnd: (() -> Unit)?
    var onError: ((String) -> Unit)?

    // ---- Lifecycle -------------------------------------------------------

    /** Initialise the engine. Returns true on success. */
    fun initialize(): Boolean

    /** Release all resources. Called when the player is no longer needed. */
    fun release()

    fun isInitialized(): Boolean

    // ---- Playback --------------------------------------------------------

    fun play(mediaPath: String)
    fun pause()
    fun resume()
    fun togglePlayPause()
    fun stop()
    /** Seek to absolute position in milliseconds. */
    fun seekTo(position: Long)
    fun setVolume(vol: Float)
    fun setPlaybackSpeed(speed: Float)

    // ---- Track management ------------------------------------------------

    fun getAudioTracks(): List<TrackInfo>
    fun getCurrentAudioTrack(): Int
    fun setAudioTrack(trackId: Int)

    fun getSubtitleTracks(): List<TrackInfo>
    fun getCurrentSubtitleTrack(): Int
    fun setSubtitleTrack(trackId: Int)

    // ---- Native-window rendering (mpv) -----------------------------------

    /** True when the engine renders into a native window (mpv). False when it pushes frames to [currentFrame] (VLC). */
    val rendersToWindow: Boolean get() = false

    /** Provide an AWT Canvas for native-window rendering. Only called when [rendersToWindow] is true. */
    fun attachCanvas(canvas: java.awt.Canvas) {}

    // ---- Common data class -----------------------------------------------

    data class TrackInfo(val id: Int, val name: String)
}
