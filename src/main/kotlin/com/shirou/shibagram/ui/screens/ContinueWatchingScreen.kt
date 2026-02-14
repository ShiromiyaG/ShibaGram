package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.data.dto.MediaCardData
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.ViewingMode
import com.shirou.shibagram.ui.components.*
import kotlinx.coroutines.launch

@Composable
fun ContinueWatchingScreen(
    videos: List<Pair<MediaMessage, Float>>,
    onVideoClick: (MediaMessage) -> Unit,
    viewingMode: ViewingMode,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Text(
                text = "Continue Watching",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(16.dp)
            )
        }
        
        HorizontalDivider()
        
        if (videos.isEmpty()) {
            EmptyState(
                icon = {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                title = "No videos in progress",
                description = "Videos you start watching will appear here"
            )
        } else {
            when (viewingMode) {
                ViewingMode.GRID -> {
                    val gridState = rememberLazyGridState()
                    
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 280.dp),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { _, dragAmount ->
                                    scope.launch {
                                        gridState.scrollBy(-dragAmount.y)
                                    }
                                }
                            }
                    ) {
                        items(videos) { (video, progress) ->
                            val cardData = remember(video, progress) {
                                MediaCardData.fromMediaMessage(
                                    video, 
                                    video.chatId,
                                    playbackPosition = (progress * video.duration * 1000).toLong(),
                                    playbackDuration = video.duration * 1000L
                                )
                            }
                            MediaCard(
                                mediaCardData = cardData,
                                onClick = { onVideoClick(video) }
                            )
                        }
                    }
                }
                
                ViewingMode.LIST, ViewingMode.COMPACT -> {
                    val listState = rememberLazyListState()
                    
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectDragGestures { _, dragAmount ->
                                    scope.launch {
                                        listState.scrollBy(-dragAmount.y)
                                    }
                                }
                            }
                    ) {
                        items(videos) { (video, progress) ->
                            val cardData = remember(video, progress) {
                                MediaCardData.fromMediaMessage(
                                    video, 
                                    video.chatId,
                                    playbackPosition = (progress * video.duration * 1000).toLong(),
                                    playbackDuration = video.duration * 1000L
                                )
                            }
                            MediaCardCompact(
                                mediaCardData = cardData,
                                onClick = { onVideoClick(video) }
                            )
                        }
                    }
                }
            }
        }
    }
}
