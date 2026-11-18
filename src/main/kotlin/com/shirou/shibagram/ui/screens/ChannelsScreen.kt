package com.shirou.shibagram.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.data.dto.MediaCardData
import com.shirou.shibagram.domain.model.Channel
import com.shirou.shibagram.domain.model.ChatFolder
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.VideoProgress
import com.shirou.shibagram.domain.model.VideoSortOrder
import com.shirou.shibagram.domain.model.VideoStatusFilter
import com.shirou.shibagram.domain.model.ViewingMode
import com.shirou.shibagram.ui.components.*
import kotlinx.coroutines.launch

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
    onFolderSelect: (Int?) -> Unit = {},
    // Video list controls
    allVideoCount: Int = videos.size,
    progress: Map<Long, VideoProgress> = emptyMap(),
    sortOrder: VideoSortOrder = VideoSortOrder.NEWEST,
    onSortOrderChange: (VideoSortOrder) -> Unit = {},
    statusFilter: VideoStatusFilter = VideoStatusFilter.ALL,
    onStatusFilterChange: (VideoStatusFilter) -> Unit = {},
    savedVideos: List<MediaMessage> = emptyList(),
    onSaveVideo: ((MediaMessage) -> Unit)? = null,
    onSetWatched: ((MediaMessage, Boolean) -> Unit)? = null,
    onContinue: (() -> Unit)? = null,
    onPlayAll: (() -> Unit)? = null,
    onPlayExternal: ((MediaMessage) -> Unit)? = null
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
                    val listState = rememberLazyListState()
                    
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .dragToScroll(listState, horizontal = false),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(channels) { channel ->
                            ChannelListItem(
                                channel = channel,
                                onClick = { onChannelSelect(channel) },
                                isSelected = selectedChannel?.id == channel.id,
                                photoPainter = rememberThumbnailPainter(channel.photoPath, channel.photoFileId)
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
            selectedChannel?.let { channel ->
                ChannelVideosHeader(
                    channel = channel,
                    videos = videos,
                    allVideoCount = allVideoCount,
                    progress = progress,
                    sortOrder = sortOrder,
                    onSortOrderChange = onSortOrderChange,
                    statusFilter = statusFilter,
                    onStatusFilterChange = onStatusFilterChange,
                    onContinue = onContinue,
                    onPlayAll = onPlayAll
                )
                HorizontalDivider()
            }

            when {
                isLoading && videos.isEmpty() -> LoadingContent(message = "Loading videos...")
                selectedChannel == null -> EmptyState(
                    icon = { VideoIcon(64) },
                    title = "Select a channel",
                    description = "Choose a channel from the sidebar to view its videos"
                )
                videos.isEmpty() -> EmptyState(
                    icon = { VideoIcon(64) },
                    title = if (allVideoCount > 0) "Nothing here" else "No videos",
                    description = if (allVideoCount > 0) "No videos match the \"${statusFilter.label}\" filter"
                    else "This channel doesn't have any videos yet"
                )
                else -> {
                    // Thin progress bar while more pages of the channel are still loading
                    if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    // New channel / order / filter: start again from the top
                    key(selectedChannel.id, sortOrder, statusFilter) {
                    VideosGrid(
                        videos = videos,
                        onVideoClick = onVideoClick,
                        viewingMode = viewingMode,
                        channelId = selectedChannel.id,
                        progress = progress,
                        savedVideos = savedVideos,
                        onSaveVideo = onSaveVideo,
                        onSetWatched = onSetWatched,
                        onPlayExternal = onPlayExternal
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoIcon(size: Int) {
    Icon(
        Icons.Default.VideoLibrary,
        contentDescription = null,
        modifier = Modifier.size(size.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatTotalDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun ChannelVideosHeader(
    channel: Channel,
    videos: List<MediaMessage>,
    allVideoCount: Int,
    progress: Map<Long, VideoProgress>,
    sortOrder: VideoSortOrder,
    onSortOrderChange: (VideoSortOrder) -> Unit,
    statusFilter: VideoStatusFilter,
    onStatusFilterChange: (VideoStatusFilter) -> Unit,
    onContinue: (() -> Unit)?,
    onPlayAll: (() -> Unit)?
) {
    val stats = remember(videos, progress) {
        val totalSeconds = videos.sumOf { it.duration.toLong() }
        val totalBytes = videos.sumOf { it.videoFile.size }
        val watched = videos.count { progress[it.id]?.isCompleted == true }
        Triple(totalSeconds, totalBytes, watched)
    }
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val countText = if (videos.size == allVideoCount) "${videos.size} videos" else "${videos.size} of $allVideoCount videos"
                    val parts = buildList {
                        add(countText)
                        if (stats.first > 0) add(formatTotalDuration(stats.first))
                        if (stats.second > 0) add(com.shirou.shibagram.data.local.VideoCacheManager.formatSize(stats.second))
                        if (stats.third > 0) add("${stats.third} watched")
                    }
                    Text(
                        text = parts.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onContinue != null && videos.isNotEmpty()) {
                    val hasProgress = videos.any { progress[it.id]?.let { p -> !p.isCompleted && p.position > 0 } == true }
                    Button(onClick = onContinue) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (hasProgress) "Continue" else "Play next")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (onPlayAll != null && videos.isNotEmpty()) {
                    OutlinedButton(onClick = onPlayAll) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Play all")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                SortMenu(sortOrder, onSortOrderChange)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoStatusFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = statusFilter == filter,
                        onClick = { onStatusFilterChange(filter) },
                        label = { Text(filter.label) },
                        leadingIcon = if (statusFilter == filter) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
private fun SortMenu(sortOrder: VideoSortOrder, onSortOrderChange: (VideoSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sortOrder.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = { onSortOrderChange(order); expanded = false },
                    leadingIcon = if (order == sortOrder) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null
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
    progress: Map<Long, VideoProgress>,
    savedVideos: List<MediaMessage>,
    onSaveVideo: ((MediaMessage) -> Unit)?,
    onSetWatched: ((MediaMessage, Boolean) -> Unit)?,
    onPlayExternal: ((MediaMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    @Composable
    fun card(video: MediaMessage, index: Int, body: @Composable (MediaCardData, Painter?) -> Unit) {
        val p = progress[video.id]
        val cardData = remember(video, p) {
            MediaCardData.fromMediaMessage(
                video, channelId,
                playbackPosition = p?.position ?: 0L,
                playbackDuration = p?.duration ?: 0L,
                isCompleted = p?.isCompleted
            )
        }
        val thumbnailPainter = rememberThumbnailPainter(video.thumbnail, video.thumbnailFileId)
        VideoContextMenu(
            video = video,
            savedVideos = savedVideos,
            onSaveVideo = onSaveVideo,
            isWatched = cardData.isWatched,
            onSetWatched = onSetWatched,
            onPlay = onVideoClick,
            onPlayExternal = onPlayExternal
        ) {
            body(cardData, thumbnailPainter)
        }
    }

    when (viewingMode) {
        ViewingMode.GRID -> {
            val gridState = rememberLazyGridState()
            Box(modifier = modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                state = gridState,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().dragToScroll(gridState, horizontal = false)
            ) {
                itemsIndexed(videos, key = { _, it -> "${it.chatId}_${it.id}" }) { index, video ->
                    card(video, index) { data, painter ->
                        MediaCard(mediaCardData = data, onClick = { onVideoClick(video) }, thumbnailPainter = painter)
                    }
                }
            }
            JumpButtons(
                canGoUp = gridState.canScrollBackward,
                canGoDown = gridState.canScrollForward,
                onFirst = { gridState.scrollToItem(0) },
                onLast = { gridState.scrollToItem((videos.size - 1).coerceAtLeast(0)) },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
            }
        }

        ViewingMode.LIST, ViewingMode.COMPACT -> {
            val listState = rememberLazyListState()
            val compact = viewingMode == ViewingMode.COMPACT
            Box(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(if (compact) 8.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 12.dp),
                modifier = Modifier.fillMaxSize().dragToScroll(listState, horizontal = false)
            ) {
                itemsIndexed(videos, key = { _, it -> "${it.chatId}_${it.id}" }) { index, video ->
                    card(video, index) { data, painter ->
                        if (compact) {
                            MediaRowDense(mediaCardData = data, onClick = { onVideoClick(video) }, thumbnailPainter = painter, index = index)
                        } else {
                            MediaCardCompact(mediaCardData = data, onClick = { onVideoClick(video) }, thumbnailPainter = painter)
                        }
                    }
                }
            }
            JumpButtons(
                canGoUp = listState.canScrollBackward,
                canGoDown = listState.canScrollForward,
                onFirst = { listState.scrollToItem(0) },
                onLast = { listState.scrollToItem((videos.size - 1).coerceAtLeast(0)) },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * Floating buttons to jump to the first / last video of the list.
 * Each one shows only when the list can scroll that way.
 */
@Composable
private fun JumpButtons(
    canGoUp: Boolean,
    canGoDown: Boolean,
    onFirst: suspend () -> Unit,
    onLast: suspend () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.End
    ) {
        AnimatedVisibility(visible = canGoUp, enter = fadeIn(), exit = fadeOut()) {
            TooltipArea(tooltip = { JumpTooltip("First video") }) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { onFirst() } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.VerticalAlignTop, contentDescription = "Go to first video")
                }
            }
        }
        AnimatedVisibility(visible = canGoDown, enter = fadeIn(), exit = fadeOut()) {
            TooltipArea(tooltip = { JumpTooltip("Last video") }) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { onLast() } },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.VerticalAlignBottom, contentDescription = "Go to last video")
                }
            }
        }
    }
}

@Composable
private fun JumpTooltip(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
