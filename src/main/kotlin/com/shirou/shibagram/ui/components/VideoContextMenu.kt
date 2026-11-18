package com.shirou.shibagram.ui.components

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.runtime.Composable
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.util.Log
import java.io.File

/**
 * Right-click menu for a video card. Each action is shown only when its callback is given.
 */
@Composable
fun VideoContextMenu(
    video: MediaMessage,
    savedVideos: List<MediaMessage>,
    onSaveVideo: ((MediaMessage) -> Unit)?,
    onRemoveFromContinue: ((MediaMessage) -> Unit)? = null,
    isWatched: Boolean? = null,
    onSetWatched: ((MediaMessage, Boolean) -> Unit)? = null,
    onPlay: ((MediaMessage) -> Unit)? = null,
    onPlayExternal: ((MediaMessage) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val isSaved = savedVideos.any { it.id == video.id && it.chatId == video.chatId }
    ContextMenuArea(items = {
        buildList {
            if (onPlay != null) add(ContextMenuItem("Play") { onPlay(video) })
            if (onPlayExternal != null) add(ContextMenuItem("Open in external mpv") { onPlayExternal(video) })
            if (onSaveVideo != null) {
                add(ContextMenuItem(if (isSaved) "Remove from saved" else "Save video") { onSaveVideo(video) })
            }
            if (onSetWatched != null && isWatched != null) {
                add(ContextMenuItem(if (isWatched) "Mark as unwatched" else "Mark as watched") { onSetWatched(video, !isWatched) })
            }
            if (onRemoveFromContinue != null) {
                add(ContextMenuItem("Remove from Continue Watching") { onRemoveFromContinue(video) })
            }
            val localPath = video.videoFile.localPath
            if (video.videoFile.isDownloaded && localPath != null) {
                add(ContextMenuItem("Show in folder") { showInFolder(localPath) })
            }
        }
    }) {
        content()
    }
}

private fun showInFolder(path: String) {
    try {
        val file = File(path)
        if (System.getProperty("os.name").lowercase().startsWith("windows")) {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        } else {
            java.awt.Desktop.getDesktop().open(file.parentFile)
        }
    } catch (e: Exception) {
        Log.e("Could not open folder for $path: ${e.message}")
    }
}
