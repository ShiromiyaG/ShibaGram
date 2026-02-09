package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.data.dto.MediaCardData
import com.shirou.shibagram.data.local.ThumbnailCache
import com.shirou.shibagram.domain.model.Channel
import com.shirou.shibagram.domain.model.ChatFolder
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.ViewingMode
import com.shirou.shibagram.ui.components.*
import java.io.File

/**
 * Channels list screen with video content.
 */
@Composable
fun ChannelsScreen(
    channels: List<Channel>,
    selectedChannel: Channel?,
    videos: List<MediaMessage>,
    onChannelSelect: (Channel) -> Unit,
    onVideoClick: (MediaMessage) -> Unit,
    isLoading: Boolean,
    viewingMode: ViewingMode,
    modifier: Modifier = Modifier,
    folders: List<ChatFolder> = emptyList(),
    selectedFolderId: Int? = null,
    onFolderSelect: (Int?) -> Unit = {}
) {
    Row(modifier = modifier.fillMaxSize()) {
        // Channels sidebar
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ) {
            Column {
                // Header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Text(
                        text = "Channels",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                
                // Folder tabs
                if (folders.isNotEmpty()) {
                    FolderTabs(
                        folders = folders,
                        selectedFolderId = selectedFolderId,
                        onFolderSelect = onFolderSelect
                    )
                }
                
                HorizontalDivider()
                
                // Channels list
                if (channels.isEmpty()) {
                    EmptyState(
                        icon = {
                            Icon(
                                Icons.Default.VideoLibrary,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        title = "No channels",
                        description = "Join channels in Telegram to see them here",
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(channels) { channel ->
                            // Load channel photo
                            var photoBitmap by remember(channel.photoPath) { mutableStateOf<ImageBitmap?>(null) }
                            LaunchedEffect(channel.photoPath) {
                                channel.photoPath?.let { path ->
                                    photoBitmap = loadImageBitmap(path)
                                }
                            }
                            
                            ChannelListItem(
                                channel = channel,
                                onClick = { onChannelSelect(channel) },
                                isSelected = selectedChannel?.id == channel.id,
                                photoPainter = photoBitmap?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it) }
                            )
                        }
                    }
                }
            }
        }
        
        // Divider
        VerticalDivider()
        
        // Videos content area
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            // Selected channel header
            selectedChannel?.let { channel ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.titleLarge
                            )
                            if (!channel.description.isNullOrEmpty()) {
                                Text(
                                    text = channel.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                
                HorizontalDivider()
            }
            
            // Videos content
            if (isLoading) {
                LoadingContent(message = "Loading videos...")
            } else if (selectedChannel == null) {
                EmptyState(
                    icon = {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    title = "Select a channel",
                    description = "Choose a channel from the sidebar to view its videos"
                )
            } else if (videos.isEmpty()) {
                EmptyState(
                    icon = {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    title = "No videos",
                    description = "This channel doesn't have any videos yet"
                )
            } else {
                VideosGrid(
                    videos = videos,
                    onVideoClick = onVideoClick,
                    viewingMode = viewingMode,
                    channelId = selectedChannel.id
                )
            }
        }
    }
}

@Composable
private fun VideosGrid(
    videos: List<MediaMessage>,
    onVideoClick: (MediaMessage) -> Unit,
    viewingMode: ViewingMode,
    channelId: Long,
    modifier: Modifier = Modifier
) {
    when (viewingMode) {
        ViewingMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = modifier.fillMaxSize()
            ) {
                items(videos) { video ->
                    val cardData = remember(video) {
                        MediaCardData.fromMediaMessage(video, channelId)
                    }
                    
                    // Load thumbnail
                    var thumbnailBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(video.thumbnail) {
                        video.thumbnail?.let { path ->
                            thumbnailBitmap = loadImageBitmap(path)
                        }
                    }
                    val thumbnailPainter = thumbnailBitmap?.let { BitmapPainter(it) }
                    
                    MediaCard(
                        mediaCardData = cardData,
                        onClick = { onVideoClick(video) },
                        thumbnailPainter = thumbnailPainter
                    )
                }
            }
        }
        
        ViewingMode.LIST, ViewingMode.COMPACT -> {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier.fillMaxSize()
            ) {
                items(videos) { video ->
                    val cardData = remember(video) {
                        MediaCardData.fromMediaMessage(video, channelId)
                    }
                    
                    // Load thumbnail
                    var thumbnailBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(video.thumbnail) {
                        video.thumbnail?.let { path ->
                            thumbnailBitmap = loadImageBitmap(path)
                        }
                    }
                    val thumbnailPainter = thumbnailBitmap?.let { BitmapPainter(it) }
                    
                    MediaCardCompact(
                        mediaCardData = cardData,
                        onClick = { onVideoClick(video) },
                        thumbnailPainter = thumbnailPainter
                    )
                }
            }
        }
    }
}

/**
 * Load an image from file path as ImageBitmap (cached).
 */
private suspend fun loadImageBitmap(path: String): ImageBitmap? {
    return ThumbnailCache.getOrLoad(path)
}
