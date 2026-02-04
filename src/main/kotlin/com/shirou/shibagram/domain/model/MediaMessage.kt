package com.shirou.shibagram.domain.model

import java.util.Locale

/**
 * Represents a media message from Telegram.
 * Ported from Android ShibaGram app.
 */
data class MediaMessage(
    val id: Long,
    val date: Int,
    val videoFile: VideoFile,
    val filename: String? = null,
    val thumbnailFileId: Int? = null,
    val thumbnail: String? = null,
    val duration: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val caption: String? = null,
    val mimeType: String = "",
    val chatId: Long = 0L,
    val audioTitle: String? = null,
    val audioPerformer: String? = null,
    val title: String = ""
) {
    val formattedSize: String
        get() {
            val size = videoFile.size
            if (size <= 0) return ""
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val log10 = kotlin.math.log10(size.toDouble()) / kotlin.math.log10(1024.0)
            val index = log10.toInt().coerceAtMost(4)
            val value = size / Math.pow(1024.0, index.toDouble())
            return String.format(Locale.getDefault(), "%.1f%s", value, units[index])
        }

    val qualityLabel: String?
        get() {
            if (!isVideo() || height == 0 || width == 0) return null
            val maxDimension = maxOf(width, height)
            return when {
                maxDimension >= 3840 -> "4K"
                maxDimension >= 2560 -> "2K"
                maxDimension >= 1920 -> "1080p"
                maxDimension >= 1280 -> "720p"
                maxDimension >= 640 -> "480p"
                else -> null
            }
        }

    val formattedDuration: String
        get() {
            if (duration <= 0) return ""
            val hours = duration / 3600
            val minutes = (duration % 3600) / 60
            val seconds = duration % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }

    val mediaType: MediaType
        get() = when {
            isVideo() -> MediaType.VIDEO
            isAudio() -> MediaType.AUDIO
            isImage() -> MediaType.IMAGE
            else -> MediaType.VIDEO
        }

    fun isVideo(): Boolean {
        return mimeType.startsWith("video/") || 
               filename?.let { 
                   it.endsWith(".mp4", ignoreCase = true) ||
                   it.endsWith(".mkv", ignoreCase = true) ||
                   it.endsWith(".avi", ignoreCase = true) ||
                   it.endsWith(".webm", ignoreCase = true)
               } == true
    }

    fun isAudio(): Boolean {
        return mimeType.startsWith("audio/") ||
               filename?.let {
                   it.endsWith(".mp3", ignoreCase = true) ||
                   it.endsWith(".flac", ignoreCase = true) ||
                   it.endsWith(".ogg", ignoreCase = true) ||
                   it.endsWith(".wav", ignoreCase = true)
               } == true
    }

    fun isImage(): Boolean {
        return mimeType.startsWith("image/") ||
               filename?.let {
                   it.endsWith(".jpg", ignoreCase = true) ||
                   it.endsWith(".jpeg", ignoreCase = true) ||
                   it.endsWith(".png", ignoreCase = true) ||
                   it.endsWith(".gif", ignoreCase = true)
               } == true
    }
}

/**
 * Media type enumeration.
 */
enum class MediaType {
    VIDEO,
    AUDIO,
    IMAGE
}
