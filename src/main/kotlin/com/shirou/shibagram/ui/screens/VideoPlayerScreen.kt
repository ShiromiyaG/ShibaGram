package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.itemsIndexed
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.VideoPlaylist
import com.shirou.shibagram.player.MediaPlayerEngine.TrackInfo

@Composable
fun VideoPlayerScreen(
    currentVideo: MediaMessage?,
    playlist: VideoPlaylist?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    volume: Float,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onCloseClick: () -> Unit,
    onVideoSelect: (Int) -> Unit,
    isFullscreen: Boolean,
    audioTracks: List<TrackInfo> = emptyList(),
    currentAudioTrack: Int = -1,
    onAudioTrackSelect: (Int) -> Unit = {},
    subtitleTracks: List<TrackInfo> = emptyList(),
    currentSubtitleTrack: Int = -1,
    onSubtitleTrackSelect: (Int) -> Unit = {},
    currentPlaybackSpeed: Float = 1f,
    onPlaybackSpeedChange: (Float) -> Unit = {},
    renderControlsAsPopup: Boolean = false,
    modifier: Modifier = Modifier,
    // videoContent now accepts an optional overlay composable to embed
    videoContent: @Composable (overlay: @Composable () -> Unit) -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showAudioTrackMenu by remember { mutableStateOf(false) }
    var showSubtitleTrackMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    // Define the controls overlay
    val controlsOverlay = @Composable {
        Box(modifier = Modifier.fillMaxSize()) {
            PlayerControlsOverlay(
                currentVideo = currentVideo,
                playlist = playlist,
                isPlaying = isPlaying,
                currentPosition = currentPosition,
                duration = duration,
                volume = volume,
                isFullscreen = isFullscreen,
                showPlaylist = showPlaylist,
                audioTracks = audioTracks,
                currentAudioTrack = currentAudioTrack,
                subtitleTracks = subtitleTracks,
                currentSubtitleTrack = currentSubtitleTrack,
                showSpeedMenu = showSpeedMenu,
                showAudioTrackMenu = showAudioTrackMenu,
                showSubtitleTrackMenu = showSubtitleTrackMenu,
                currentPlaybackSpeed = currentPlaybackSpeed,
                onShowPlaylistChange = { showPlaylist = it },
                onShowSpeedMenuChange = { showSpeedMenu = it },
                onShowAudioTrackMenuChange = { showAudioTrackMenu = it },
                onShowSubtitleTrackMenuChange = { showSubtitleTrackMenu = it },
                onPlayPauseClick = onPlayPauseClick,
                onSeek = onSeek,
                onVolumeChange = onVolumeChange,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onFullscreenToggle = onFullscreenToggle,
                onCloseClick = onCloseClick,
                onAudioTrackSelect = onAudioTrackSelect,
                onSubtitleTrackSelect = onSubtitleTrackSelect,
                onPlaybackSpeedChange = onPlaybackSpeedChange
            )
            
            // Playlist Sidebar (inside overlay)
            if (showPlaylist && playlist != null) {
                PlaylistSidebar(
                    playlist = playlist,
                    onVideoSelect = onVideoSelect,
                    onClose = { showPlaylist = false },
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (renderControlsAsPopup) {
            // MPV Mode: Pass controls to videoContent to be embedded in Swing (JLayeredPane)
            // Visibility is handled inside the ComposePanel in Main.kt via showMpvControls state
            videoContent(controlsOverlay)
        } else {
            // VLC Mode: Standard overlay
            videoContent {} // No embedded overlay
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showControls = !showControls }
            ) {
                if (showControls) {
                    controlsOverlay()
                }
            }
        }
    }
}

@Composable
fun PlayerControlsOverlay(
    currentVideo: MediaMessage?,
    playlist: VideoPlaylist?,
    isPlaying: Boolean,
    currentPosition: Long,
    duration: Long,
    volume: Float,
    isFullscreen: Boolean,
    showPlaylist: Boolean,
    audioTracks: List<TrackInfo>,
    currentAudioTrack: Int,
    subtitleTracks: List<TrackInfo>,
    currentSubtitleTrack: Int,
    showSpeedMenu: Boolean,
    showAudioTrackMenu: Boolean,
    showSubtitleTrackMenu: Boolean,
    currentPlaybackSpeed: Float,
    onShowPlaylistChange: (Boolean) -> Unit,
    onShowSpeedMenuChange: (Boolean) -> Unit,
    onShowAudioTrackMenuChange: (Boolean) -> Unit,
    onShowSubtitleTrackMenuChange: (Boolean) -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onFullscreenToggle: () -> Unit,
    onCloseClick: () -> Unit,
    onAudioTrackSelect: (Int) -> Unit,
    onSubtitleTrackSelect: (Int) -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        TopBar(
            currentVideo = currentVideo,
            playlist = playlist,
            isFullscreen = isFullscreen,
            showPlaylist = showPlaylist,
            onShowPlaylistChange = onShowPlaylistChange,
            onFullscreenToggle = onFullscreenToggle,
            onCloseClick = onCloseClick,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        CenterPlaybackControls(
            isPlaying = isPlaying,
            playlist = playlist,
            onPlayPauseClick = onPlayPauseClick,
            onPreviousClick = onPreviousClick,
            onNextClick = onNextClick,
            modifier = Modifier.align(Alignment.Center)
        )

        Surface(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            color = Color.Black.copy(alpha = 0.6f)
        ) {
            BottomControlsContent(
                currentPosition = currentPosition,
                duration = duration,
                volume = volume,
                currentVideo = currentVideo,
                currentPlaybackSpeed = currentPlaybackSpeed,
                audioTracks = audioTracks,
                currentAudioTrack = currentAudioTrack,
                subtitleTracks = subtitleTracks,
                currentSubtitleTrack = currentSubtitleTrack,
                showSpeedMenu = showSpeedMenu,
                showAudioTrackMenu = showAudioTrackMenu,
                showSubtitleTrackMenu = showSubtitleTrackMenu,
                onShowSpeedMenuChange = onShowSpeedMenuChange,
                onShowAudioTrackMenuChange = onShowAudioTrackMenuChange,
                onShowSubtitleTrackMenuChange = onShowSubtitleTrackMenuChange,
                onSeek = onSeek,
                onVolumeChange = onVolumeChange,
                onPlaybackSpeedChange = onPlaybackSpeedChange,
                onAudioTrackSelect = onAudioTrackSelect,
                onSubtitleTrackSelect = onSubtitleTrackSelect
            )
        }
    }
}

@Composable
private fun TopBar(
    currentVideo: MediaMessage?,
    playlist: VideoPlaylist?,
    isFullscreen: Boolean,
    showPlaylist: Boolean,
    onShowPlaylistChange: (Boolean) -> Unit,
    onFullscreenToggle: () -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Black.copy(alpha = 0.6f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onCloseClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                currentVideo?.let { video ->
                    Text(
                        text = video.title.ifEmpty { video.filename ?: "Video" },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    playlist?.let {
                        Text("${it.currentIndex + 1} / ${it.videos.size}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            Row {
                if (playlist != null && playlist.videos.size > 1) {
                    IconButton(onClick = { onShowPlaylistChange(!showPlaylist) }) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Playlist", tint = Color.White)
                    }
                }
                IconButton(onClick = onFullscreenToggle) {
                    Icon(if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun CenterPlaybackControls(
    isPlaying: Boolean,
    playlist: VideoPlaylist?,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
        if (playlist?.hasPrevious == true) {
            IconButton(onClick = onPreviousClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                Icon(Icons.Default.SkipPrevious, "Previous", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
        IconButton(onClick = onPlayPauseClick, modifier = Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                tint = Color.White, modifier = Modifier.size(48.dp)
            )
        }
        if (playlist?.hasNext == true) {
            IconButton(onClick = onNextClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))) {
                Icon(Icons.Default.SkipNext, "Next", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun BottomControlsContent(
    currentPosition: Long,
    duration: Long,
    volume: Float,
    currentVideo: MediaMessage?,
    currentPlaybackSpeed: Float,
    audioTracks: List<TrackInfo>,
    currentAudioTrack: Int,
    subtitleTracks: List<TrackInfo>,
    currentSubtitleTrack: Int,
    showSpeedMenu: Boolean,
    showAudioTrackMenu: Boolean,
    showSubtitleTrackMenu: Boolean,
    onShowSpeedMenuChange: (Boolean) -> Unit,
    onShowAudioTrackMenuChange: (Boolean) -> Unit,
    onShowSubtitleTrackMenuChange: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPlaybackSpeedChange: (Float) -> Unit,
    onAudioTrackSelect: (Int) -> Unit,
    onSubtitleTrackSelect: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatDuration(currentPosition), style = MaterialTheme.typography.bodySmall, color = Color.White)
            // While dragging, show the drag position and seek once on release (not once per tick)
            var dragFraction by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragFraction ?: if (duration > 0) currentPosition.toFloat() / duration else 0f,
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { onSeek((it * duration).toLong()) }
                    dragFraction = null
                },
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                )
            )
            Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                when { volume == 0f -> Icons.AutoMirrored.Filled.VolumeOff; volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown; else -> Icons.AutoMirrored.Filled.VolumeUp },
                "Volume", tint = Color.White, modifier = Modifier.size(24.dp)
            )
            Slider(
                value = volume, onValueChange = onVolumeChange, modifier = Modifier.width(120.dp),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f))
            )
            Spacer(modifier = Modifier.weight(1f))
            SpeedMenu(currentPlaybackSpeed, showSpeedMenu, onShowSpeedMenuChange, onPlaybackSpeedChange)
            if (audioTracks.isNotEmpty()) TrackMenu(Icons.Default.Audiotrack, "Audio Track", audioTracks, currentAudioTrack, showAudioTrackMenu, onShowAudioTrackMenuChange, onAudioTrackSelect)
            TrackMenu(Icons.Default.Subtitles, "Subtitles", subtitleTracks, currentSubtitleTrack, showSubtitleTrackMenu, onShowSubtitleTrackMenuChange, onSubtitleTrackSelect, allowOff = true)
            QualityLabel(currentVideo)
        }
    }
}

@Composable
private fun SpeedMenu(currentSpeed: Float, expanded: Boolean, onExpandChange: (Boolean) -> Unit, onSpeedChange: (Float) -> Unit) {
    Box {
        IconButton(onClick = { onExpandChange(true) }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Default.Speed, "Playback Speed", tint = Color.White, modifier = Modifier.size(20.dp))
                Text("${currentSpeed}x", style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandChange(false) }) {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${speed}x", color = if (speed == currentSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = { onSpeedChange(speed); onExpandChange(false) },
                    leadingIcon = if (speed == currentSpeed) { { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) } } else null
                )
            }
        }
    }
}

@Composable
private fun TrackMenu(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    tracks: List<TrackInfo>,
    currentTrack: Int,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    allowOff: Boolean = false
) {
    val isOff = allowOff && currentTrack <= 0
    Box {
        IconButton(onClick = { onExpandChange(true) }) {
            val highlighted = allowOff && !isOff
            Icon(icon, title, tint = if (highlighted) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(24.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandChange(false) }) {
            Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary)
            HorizontalDivider()
            @Composable
            fun item(label: String, selected: Boolean, id: Int) = DropdownMenuItem(
                text = { Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                onClick = { onSelect(id); onExpandChange(false) },
                leadingIcon = if (selected) { { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) } } else null
            )
            if (allowOff) item("Off", isOff, -1)
            tracks.filter { !allowOff || it.id > 0 }.forEach { track -> item(track.name, track.id == currentTrack, track.id) }
        }
    }
}

@Composable
private fun QualityLabel(currentVideo: MediaMessage?) {
    currentVideo?.qualityLabel?.let { quality ->
        Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primary) {
            Text(quality, style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun PlaylistSidebar(playlist: VideoPlaylist, onVideoSelect: (Int) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.width(300.dp).fillMaxHeight(), color = Color.Black.copy(alpha = 0.9f), shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Playlist", style = MaterialTheme.typography.titleMedium, color = Color.White)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close playlist", tint = Color.White) }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            // Lazy + scrollable: a plain Column clipped everything past the window height
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = playlist.currentIndex.coerceAtLeast(0)
            )
            androidx.compose.foundation.lazy.LazyColumn(state = listState) {
                itemsIndexed(playlist.videos, key = { _, v -> "${v.chatId}_${v.id}" }) { index, video ->
                    PlaylistItem(video, index, index == playlist.currentIndex) { onVideoSelect(index) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistItem(video: MediaMessage, index: Int, isCurrentVideo: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (isCurrentVideo) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1}", style = MaterialTheme.typography.bodySmall, color = if (isCurrentVideo) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f))
            if (isCurrentVideo) Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(video.title.ifEmpty { video.filename ?: "Video" }, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(video.formattedDuration, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds) else String.format("%d:%02d", minutes, seconds)
}
