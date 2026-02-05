package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.VideoPlaylist

/**
 * Track information for audio/subtitle selection
 */
data class TrackInfo(
    val id: Int,
    val name: String
)

/**
 * Video player screen with Material Design 3 controls.
 */
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
    // Audio track callbacks
    audioTracks: List<TrackInfo> = emptyList(),
    currentAudioTrack: Int = -1,
    onAudioTrackSelect: (Int) -> Unit = {},
    // Subtitle track callbacks
    subtitleTracks: List<TrackInfo> = emptyList(),
    currentSubtitleTrack: Int = -1,
    onSubtitleTrackSelect: (Int) -> Unit = {},
    // Playback speed
    currentPlaybackSpeed: Float = 1f,
    onPlaybackSpeedChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    videoContent: @Composable () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showAudioTrackMenu by remember { mutableStateOf(false) }
    var showSubtitleTrackMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
    ) {
        // Video content (VLC player will be rendered here)
        videoContent()
        
        // Controls overlay
        if (showControls) {
            // Top bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onCloseClick) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        currentVideo?.let { video ->
                            Text(
                                text = video.title.ifEmpty { video.filename ?: "Video" },
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            
                            playlist?.let {
                                Text(
                                    text = "${it.currentIndex + 1} / ${it.videos.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    
                    Row {
                        if (playlist != null && playlist.videos.size > 1) {
                            IconButton(onClick = { showPlaylist = !showPlaylist }) {
                                Icon(
                                    Icons.Default.PlaylistPlay,
                                    contentDescription = "Playlist",
                                    tint = Color.White
                                )
                            }
                        }
                        
                        IconButton(onClick = onFullscreenToggle) {
                            Icon(
                                imageVector = if (isFullscreen) {
                                    Icons.Default.FullscreenExit
                                } else {
                                    Icons.Default.Fullscreen
                                },
                                contentDescription = "Fullscreen",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
            
            // Center play button
            Box(
                modifier = Modifier.align(Alignment.Center)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous
                    if (playlist?.hasPrevious == true) {
                        IconButton(
                            onClick = onPreviousClick,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    
                    // Play/Pause
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    
                    // Next
                    if (playlist?.hasNext == true) {
                        IconButton(
                            onClick = onNextClick,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
            
            // Bottom controls
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Progress bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatDuration(currentPosition),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        
                        Slider(
                            value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                            onValueChange = { onSeek((it * duration).toLong()) },
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                    
                    // Volume control
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when {
                                volume == 0f -> Icons.Default.VolumeOff
                                volume < 0.5f -> Icons.Default.VolumeDown
                                else -> Icons.Default.VolumeUp
                            },
                            contentDescription = "Volume",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        
                        Slider(
                            value = volume,
                            onValueChange = onVolumeChange,
                            modifier = Modifier.width(120.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        // Playback speed button
                        Box {
                            IconButton(onClick = { showSpeedMenu = true }) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = "Playback Speed",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "${currentPlaybackSpeed}x",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White
                                    )
                                }
                            }
                            
                            DropdownMenu(
                                expanded = showSpeedMenu,
                                onDismissRequest = { showSpeedMenu = false }
                            ) {
                                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                text = "${speed}x",
                                                color = if (speed == currentPlaybackSpeed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            onPlaybackSpeedChange(speed)
                                            showSpeedMenu = false
                                        },
                                        leadingIcon = if (speed == currentPlaybackSpeed) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null
                                    )
                                }
                            }
                        }
                        
                        // Audio track button
                        if (audioTracks.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { showAudioTrackMenu = true }) {
                                    Icon(
                                        Icons.Default.Audiotrack,
                                        contentDescription = "Audio Track",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                
                                DropdownMenu(
                                    expanded = showAudioTrackMenu,
                                    onDismissRequest = { showAudioTrackMenu = false }
                                ) {
                                    Text(
                                        text = "Audio Track",
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    HorizontalDivider()
                                    audioTracks.forEach { track ->
                                        DropdownMenuItem(
                                            text = { 
                                                Text(
                                                    text = track.name,
                                                    color = if (track.id == currentAudioTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                onAudioTrackSelect(track.id)
                                                showAudioTrackMenu = false
                                            },
                                            leadingIcon = if (track.id == currentAudioTrack) {
                                                { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                            } else null
                                        )
                                    }
                                }
                            }
                        }
                        
                        // Subtitle track button
                        Box {
                            IconButton(onClick = { showSubtitleTrackMenu = true }) {
                                Icon(
                                    Icons.Default.Subtitles,
                                    contentDescription = "Subtitles",
                                    tint = if (currentSubtitleTrack > 0) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            DropdownMenu(
                                expanded = showSubtitleTrackMenu,
                                onDismissRequest = { showSubtitleTrackMenu = false }
                            ) {
                                Text(
                                    text = "Subtitles",
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                HorizontalDivider()
                                
                                // Off option
                                DropdownMenuItem(
                                    text = { 
                                        Text(
                                            text = "Off",
                                            color = if (currentSubtitleTrack <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    onClick = {
                                        onSubtitleTrackSelect(-1)
                                        showSubtitleTrackMenu = false
                                    },
                                    leadingIcon = if (currentSubtitleTrack <= 0) {
                                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null
                                )
                                
                                subtitleTracks.filter { it.id > 0 }.forEach { track ->
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                text = track.name,
                                                color = if (track.id == currentSubtitleTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        },
                                        onClick = {
                                            onSubtitleTrackSelect(track.id)
                                            showSubtitleTrackMenu = false
                                        },
                                        leadingIcon = if (track.id == currentSubtitleTrack) {
                                            { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                        } else null
                                    )
                                }
                            }
                        }
                        
                        // Quality label
                        currentVideo?.qualityLabel?.let { quality ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = quality,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Playlist sidebar
        if (showPlaylist && playlist != null) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd),
                color = Color.Black.copy(alpha = 0.9f),
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Playlist",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        
                        IconButton(onClick = { showPlaylist = false }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close playlist",
                                tint = Color.White
                            )
                        }
                    }
                    
                    HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
                    
                    playlist.videos.forEachIndexed { index, video ->
                        PlaylistItem(
                            video = video,
                            index = index,
                            isCurrentVideo = index == playlist.currentIndex,
                            onClick = { onVideoSelect(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistItem(
    video: MediaMessage,
    index: Int,
    isCurrentVideo: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (isCurrentVideo) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrentVideo) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f)
            )
            
            if (isCurrentVideo) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title.ifEmpty { video.filename ?: "Video" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = video.formattedDuration,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
