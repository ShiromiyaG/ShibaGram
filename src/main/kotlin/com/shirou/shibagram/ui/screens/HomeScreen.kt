package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.data.dto.MediaCardData
import com.shirou.shibagram.data.local.ThumbnailCache
import com.shirou.shibagram.domain.model.Channel
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.ui.components.*
import java.io.File

/**
 * Home screen with Material Design 3.
 * Shows recent videos, continue watching and saved sections.
 */
@Composable
fun HomeScreen(
    recentVideos: List<Pair<Channel, List<MediaMessage>>>,
    continueWatchingVideos: List<Pair<MediaMessage, Float>>,
    savedVideos: List<MediaMessage>,
    onVideoClick: (MediaMessage) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onSaveVideo: (MediaMessage) -> Unit,
    onRemoveFromContinue: (MediaMessage) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // Header — warmer, more expressive greeting
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "Welcome back",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Watch videos from your Telegram channels",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        if (isLoading) {
            LoadingContent(
                message = "Loading your content...",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )
        } else {
            // Continue Watching section
            if (continueWatchingVideos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                
                HomeSection(
                    title = "Continue Watching",
                    showSeeAll = false
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(continueWatchingVideos.take(10)) { (video, progress) ->
                            val cardData = remember(video, progress) {
                                MediaCardData.fromMediaMessage(
                                    video,
                                    video.chatId,
                                    playbackPosition = (progress * video.duration * 1000).toLong(),
                                    playbackDuration = video.duration * 1000L
                                )
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
                                modifier = Modifier.width(280.dp),
                                thumbnailPainter = thumbnailPainter
                            )
                        }
                    }
                }
            }
            
            // Saved Videos section
            if (savedVideos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                
                HomeSection(
                    title = "Saved Videos",
                    showSeeAll = false
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(savedVideos.take(10)) { video ->
                            val cardData = remember(video) {
                                MediaCardData.fromMediaMessage(video, video.chatId)
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
                                modifier = Modifier.width(280.dp),
                                thumbnailPainter = thumbnailPainter
                            )
                        }
                    }
                }
            }
            
            // Recent from channels sections
            recentVideos.forEach { (channel, videos) ->
                if (videos.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    HomeSection(
                        title = channel.name,
                        onSeeAll = { onChannelClick(channel) }
                    ) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(videos.take(10)) { video ->
                                val cardData = remember(video) {
                                    MediaCardData.fromMediaMessage(video, channel.id)
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
                                    modifier = Modifier.width(280.dp),
                                    thumbnailPainter = thumbnailPainter
                                )
                            }
                        }
                    }
                }
            }
            
            // Empty state
            if (recentVideos.isEmpty() && continueWatchingVideos.isEmpty() && savedVideos.isEmpty()) {
                EmptyState(
                    icon = {
                        Icon(
                            Icons.Default.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    title = "No videos yet",
                    description = "Join channels with videos in Telegram to see them here",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HomeSection(
    title: String,
    showSeeAll: Boolean = true,
    onSeeAll: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (showSeeAll) {
                FilledTonalButton(
                    onClick = onSeeAll,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        "See all",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        content()
    }
}

/**
 * Load an image from file path as ImageBitmap (cached).
 */
private suspend fun loadImageBitmap(path: String): ImageBitmap? {
    return ThumbnailCache.getOrLoad(path)
}
