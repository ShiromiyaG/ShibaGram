package com.shirou.shibagram.data.dto

import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.MediaType

/**
 * Data class for media card presentation.
 * Ported from Android ShibaGram app.
 */
data class MediaCardData(
    val mediaMessage: MediaMessage,
    val channelId: Long,
    val playbackPosition: Long = 0L,
    val playbackDuration: Long = 0L,
    val lastUpdated: Long = 0L,
    val isWatched: Boolean = false,
    val thumbnailPath: String = "",
    val channelIconPath: String = "",
    val mediaType: MediaType = MediaType.VIDEO,
    val qualityLabel: String = "",
    val progressRatio: Float = 0f,
    val hasProgress: Boolean = false,
    val videoTitle: String = "",
    val formattedSize: String = "",
    val formattedDuration: String = "",
    val durationText: String = ""
) {
    companion object {
        fun fromMediaMessage(
            mediaMessage: MediaMessage,
            channelId: Long,
            playbackPosition: Long = 0L,
            playbackDuration: Long = 0L,
            lastUpdated: Long = 0L,
            channelIconPath: String = ""
        ): MediaCardData {
            val duration = playbackDuration.takeIf { it > 0 } ?: (mediaMessage.duration * 1000L)
            val progressRatio = if (duration > 0) {
                (playbackPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
            
            return MediaCardData(
                mediaMessage = mediaMessage,
                channelId = channelId,
                playbackPosition = playbackPosition,
                playbackDuration = duration,
                lastUpdated = lastUpdated,
                isWatched = progressRatio >= 0.9f,
                thumbnailPath = mediaMessage.thumbnail ?: "",
                channelIconPath = channelIconPath,
                mediaType = mediaMessage.mediaType,
                qualityLabel = mediaMessage.qualityLabel ?: "",
                progressRatio = progressRatio,
                hasProgress = playbackPosition > 0,
                videoTitle = mediaMessage.title.ifEmpty { 
                    mediaMessage.filename ?: "Video ${mediaMessage.id}"
                },
                formattedSize = mediaMessage.formattedSize,
                formattedDuration = mediaMessage.formattedDuration,
                durationText = mediaMessage.formattedDuration
            )
        }
    }
}
