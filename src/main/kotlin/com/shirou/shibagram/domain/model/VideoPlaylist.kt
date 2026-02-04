package com.shirou.shibagram.domain.model

/**
 * Represents a video playlist.
 * Ported from Android ShibaGram app.
 */
data class VideoPlaylist(
    val id: Long,
    val channelId: Long,
    val channelName: String,
    val videos: List<MediaMessage>,
    val currentIndex: Int = 0
) {
    val currentVideo: MediaMessage?
        get() = videos.getOrNull(currentIndex)
    
    val hasNext: Boolean
        get() = currentIndex < videos.size - 1
    
    val hasPrevious: Boolean
        get() = currentIndex > 0
    
    fun next(): VideoPlaylist {
        return if (hasNext) copy(currentIndex = currentIndex + 1) else this
    }
    
    fun previous(): VideoPlaylist {
        return if (hasPrevious) copy(currentIndex = currentIndex - 1) else this
    }
    
    fun goTo(index: Int): VideoPlaylist {
        return if (index in videos.indices) copy(currentIndex = index) else this
    }
}
