package com.shirou.shibagram

import com.shirou.shibagram.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.data.local.VideoCacheManager
import com.shirou.shibagram.data.repository.ChannelMessagesRepository
import com.shirou.shibagram.data.repository.TelegramAuthRepository
import com.shirou.shibagram.data.repository.TelegramChannelsRepository
import com.shirou.shibagram.domain.model.*
import com.shirou.shibagram.player.MediaPlayerEngine
import com.shirou.shibagram.player.MpvMediaPlayer
import com.shirou.shibagram.streaming.TelegramVideoDataProvider
import com.shirou.shibagram.streaming.VideoStreamingServer
import com.shirou.shibagram.ui.components.ShibaGramNavigationRail
import com.shirou.shibagram.ui.components.ShibaGramTopBar
import com.shirou.shibagram.ui.screens.*
import com.shirou.shibagram.ui.theme.ShibaGramTheme
import com.shirou.shibagram.vlc.VlcMediaPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Dimension
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO

/**
 * Main application entry point.
 */
fun main() = application {
    var isFullscreen by remember { mutableStateOf(false) }
    
    val windowState = rememberWindowState(
        placement = WindowPlacement.Floating,
        width = 1280.dp,
        height = 800.dp
    )
    
    // Update window placement when fullscreen changes
    LaunchedEffect(isFullscreen) {
        windowState.placement = if (isFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }
    
    // Load app icon (pre-computed outside composition to avoid blocking first frame)
    val appIcon = remember {
        try {
            Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use { stream ->
                BitmapPainter(ImageIO.read(stream).toComposeImageBitmap())
            }
        } catch (e: Exception) {
            null
        }
    }
    
    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "ShibaGram - Telegram Media Client",
        icon = appIcon
    ) {
        window.minimumSize = Dimension(800, 600)
        
        // Ensure window stays visible during video playback via LaunchedEffect
        // (Removing AWT listeners that were forcing Frame.NORMAL and breaking fullscreen)
        
        ShibaGramApp(
            isFullscreen = isFullscreen,
            onFullscreenChange = { isFullscreen = it },
            windowMinimized = windowState.isMinimized
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShibaGramApp(
    isFullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    windowMinimized: Boolean = false
) {
    // Repositories
    val authRepository = remember { TelegramAuthRepository() }
    val channelsRepository = remember { TelegramChannelsRepository() }
    val messagesRepository = remember { ChannelMessagesRepository() }
    val watchHistoryRepository = remember { com.shirou.shibagram.data.repository.WatchHistoryRepository() }
    val telegramClient = remember { TelegramClientService.getInstance() }
    
    // Player preferences (persisted)
    val userPrefs = remember { com.shirou.shibagram.data.preferences.UserPreferencesRepository.getInstance() }
    var playerType by remember { mutableStateOf(userPrefs.playerType) }
    var isDarkTheme by remember { mutableStateOf(userPrefs.darkTheme.value) }

    // MPV controls visibility (managed here because Canvas mouse listener lives here)
    var showMpvControls by remember { mutableStateOf(true) }
    var mpvInteractionKey by remember { mutableStateOf(0) }
    // Debounce: ignore mouse-move-to-show for 600ms after a click-to-hide
    var lastControlsHideTime by remember { mutableStateOf(0L) }

    // Active player engine (VLC or MPV) — recreated when player type changes
    val activePlayer: MediaPlayerEngine = remember(playerType) {
        when (playerType) {
            PlayerType.VLC -> VlcMediaPlayer()
            PlayerType.MPV -> MpvMediaPlayer()
        }
    }

    // Video cache manager
    val cacheManager = remember { VideoCacheManager.getInstance() }
    var maxCacheSizeGb by remember { mutableStateOf(userPrefs.maxCacheSizeGb) }
    var currentCacheSize by remember { mutableStateOf("Calculating...") }

    // Auto-hide MPV controls 3s after last interaction while playing
    val isPlayerPlaying by activePlayer.isPlaying.collectAsState()
    val playerPosition by activePlayer.currentPosition.collectAsState()
    val playerDuration by activePlayer.duration.collectAsState()
    val playerVolume by activePlayer.volume.collectAsState()
    val playerFrame by activePlayer.currentFrame.collectAsState()
    var playerInitialized by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    
    // Audio and subtitle tracks state
    var audioTracks by remember { mutableStateOf<List<MediaPlayerEngine.TrackInfo>>(emptyList()) }
    var currentAudioTrack by remember { mutableStateOf(-1) }
    var subtitleTracks by remember { mutableStateOf<List<MediaPlayerEngine.TrackInfo>>(emptyList()) }
    var currentSubtitleTrack by remember { mutableStateOf(-1) }
    var playbackSpeed by remember { mutableStateOf(1f) }

    // MPV controls: force-show when paused, auto-hide 3s after last interaction while playing
    LaunchedEffect(showMpvControls, isPlayerPlaying, mpvInteractionKey) {
        if (!activePlayer.rendersToWindow) return@LaunchedEffect
        // Rule 1: always show controls when paused
        if (!isPlayerPlaying) {
            if (!showMpvControls) showMpvControls = true
            return@LaunchedEffect
        }
        // Rule 2: auto-hide after 3s of no interaction while playing
        if (showMpvControls) {
            delay(3000)
            showMpvControls = false
        }
    }

    // Update tracks when video starts playing or tracks are refreshed
    LaunchedEffect(isPlayerPlaying, playerDuration, activePlayer, activePlayer.tracksChangeSignal.value) {
        if (isPlayerPlaying && playerDuration > 0) {
            // Small delay to let player load track info
            delay(500)
            val audio = activePlayer.getAudioTracks()
            val subs = activePlayer.getSubtitleTracks()
            audioTracks = audio
            currentAudioTrack = activePlayer.getCurrentAudioTrack()
            subtitleTracks = subs
            currentSubtitleTrack = activePlayer.getCurrentSubtitleTrack()
            Log.d("Tracks updated: ${audio.size} audio, ${subs.size} subtitle")
        }
    }
    
    // Initialize / release player (restarts when playerType or mpvPath changes)
    // Native discovery/startup (VLC scans the disk) runs on IO instead of blocking the first frame
    LaunchedEffect(activePlayer) {
        activePlayer.onError = { error -> playerError = error }
        playerInitialized = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { activePlayer.initialize() }
    }
    DisposableEffect(activePlayer) {
        val player = activePlayer // capture for onDispose
        onDispose {
            playerInitialized = false
            // Release off the UI thread: native teardown can block
            Thread({ player.release() }, "player-release").start()
        }
    }
    
    // Auth state
    val authState by authRepository.authState.collectAsState()
    
    // Channels
    val channels by channelsRepository.channels.collectAsState()
    
    // Chat folders
    val chatFolders by channelsRepository.chatFolders.collectAsState()
    val selectedFolderId by channelsRepository.selectedFolderId.collectAsState()
    
    // App state
    var selectedNavIndex by remember { mutableStateOf(0) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var currentVideos by remember { mutableStateOf<List<MediaMessage>>(emptyList()) }
    var isLoadingVideos by remember { mutableStateOf(false) }
    val viewingMode by userPrefs.viewingMode.collectAsState()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentPlayingVideo by remember { mutableStateOf<MediaMessage?>(null) }
    var currentPlaylist by remember { mutableStateOf<VideoPlaylist?>(null) }
    val autoPlayNext by userPrefs.autoPlayNext.collectAsState()
    var downloadPath by remember { mutableStateOf(userPrefs.downloadLocation) }
    
    // Continue watching and saved videos from repository
    val continueWatchingVideos by watchHistoryRepository.continueWatchingVideos.collectAsState()
    val savedVideos by watchHistoryRepository.savedVideos.collectAsState()
    val watchedChannelIds by watchHistoryRepository.watchedChannelIds.collectAsState()
    
    // Streaming server - lazy, only started when actually needed for HTTP streaming fallback
    var currentStreamingToken by remember { mutableStateOf<String?>(null) }
    
    // Filtered videos based on search
    val filteredVideos = remember(currentVideos, searchQuery) {
        if (searchQuery.isBlank()) currentVideos
        else currentVideos.filter { video ->
            video.filename?.contains(searchQuery, ignoreCase = true) == true ||
            video.caption?.contains(searchQuery, ignoreCase = true) == true ||
            video.title?.contains(searchQuery, ignoreCase = true) == true
        }
    }
    
    // Filtered channels based on search
    val filteredChannels = remember(channels, searchQuery) {
        if (searchQuery.isBlank()) channels
        else channels.filter { channel ->
            channel.name.contains(searchQuery, ignoreCase = true) ||
            channel.description?.contains(searchQuery, ignoreCase = true) == true
        }
    }
    
    // Search results from TDLib API (for cross-channel video search)
    var searchResults by remember { mutableStateOf<List<MediaMessage>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    // Coroutine scope
    val scope = rememberCoroutineScope()
    
    // Initialize Telegram client and load watch history
    LaunchedEffect(Unit) {
        // Initialize auth first (critical path)
        authRepository.initialize()
        
        // Load watch history and cache info in parallel (non-blocking)
        launch {
            watchHistoryRepository.loadContinueWatching()
        }
        launch {
            watchHistoryRepository.loadSavedVideos()
        }
        launch {
            watchHistoryRepository.loadWatchedChannels()
        }
        
        // Start cache manager monitoring (deferred - not needed at startup)
        launch {
            delay(3000)
            cacheManager.startMonitoring()
        }
        
        // Calculate cache size on IO thread (deferred)
        launch {
            delay(2000)
            currentCacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cacheManager.getFormattedCacheSize()
            }
        }
    }
    
    // Update cache manager limit when setting changes
    LaunchedEffect(maxCacheSizeGb) {
        cacheManager.maxCacheSizeBytes = (maxCacheSizeGb * 1024 * 1024 * 1024).toLong()
        userPrefs.maxCacheSizeGb = maxCacheSizeGb
    }
    
    // Save playback progress periodically (debounced - only keyed on the video, NOT on position)
    LaunchedEffect(currentPlayingVideo) {
        val video = currentPlayingVideo
        if (video != null) {
            while (true) {
                delay(5000)
                val pos = activePlayer.currentPosition.value
                val dur = activePlayer.duration.value
                if (pos > 0 && dur > 0) {
                    watchHistoryRepository.saveProgress(
                        messageId = video.id,
                        chatId = video.chatId,
                        position = pos,
                        duration = dur,
                        title = video.title.takeIf { it.isNotBlank() } ?: video.caption,
                        thumbnailPath = video.thumbnail
                    )
                }
            }
        }
    }
    
    // Perform search across channels when query changes (debounced)
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        kotlinx.coroutines.delay(300) // debounce
        try {
            val chatId = if (selectedNavIndex == 1) selectedChannel?.id else null
            telegramClient.searchMessages(searchQuery, chatId, limit = 50)
                .collect { results ->
                    searchResults = results
                }
        } catch (e: Exception) {
            Log.e("Search error: ${e.message}")
        }
        isSearching = false
    }
    
    // Track active data provider for cleanup
    var activeDataProvider by remember { mutableStateOf<TelegramVideoDataProvider?>(null) }

    fun releaseStream() {
        currentStreamingToken?.let { VideoStreamingServer.getInstance().unregisterVideo(it) }
        currentStreamingToken = null
        activeDataProvider?.close()
        activeDataProvider = null
    }

    // Start a video and the playlist around it. If the clicked video isn't part of [videos]
    // (e.g. it came from Continue Watching or search), play it as a one-item playlist.
    fun startPlayback(video: MediaMessage, videos: List<MediaMessage>, channelName: String) {
        val list = if (videos.any { it.id == video.id && it.chatId == video.chatId }) videos else listOf(video)
        currentPlaylist = VideoPlaylist(
            id = video.chatId,
            channelId = video.chatId,
            channelName = channelName,
            videos = list,
            currentIndex = list.indexOfFirst { it.id == video.id && it.chatId == video.chatId }
        )
        currentPlayingVideo = video
    }

    // Play video when selected. Runs inside the effect (not a detached scope.launch) so that
    // switching videos quickly cancels the previous start-up instead of racing it.
    LaunchedEffect(currentPlayingVideo) {
        releaseStream()
        val video = currentPlayingVideo
        if (video == null) {
            // stop() is already called in onCloseClick; just trim the cache
            cacheManager.triggerCleanup()
            return@LaunchedEffect
        }
        if (!playerInitialized) {
            // Player may still be starting on the IO thread; wait briefly for it
            if (kotlinx.coroutines.withTimeoutOrNull(10_000) { snapshotFlow { playerInitialized }.first { it } } == null) {
                com.shirou.shibagram.util.Log.w("Player not initialized, cannot play video")
                return@LaunchedEffect
            }
        }

        // If video doesn't have complete file info (from Continue Watching), fetch it
        val playingVideo = if (video.videoFile.id == 0) {
            telegramClient.getMessage(video.chatId, video.id) ?: run {
                Log.e("Failed to fetch video info for message ${video.id}")
                return@LaunchedEffect
            }
        } else {
            video
        }

        val savedPosition = watchHistoryRepository.getProgress(video.chatId, video.id)

        val localPath = playingVideo.videoFile.localPath
        if (localPath != null && playingVideo.videoFile.isDownloaded) {
            activePlayer.play(localPath)
        } else {
            // Always use HTTP streaming for incomplete files: TDLib stores partial downloads without
            // an extension, so MPV/VLC can't detect the format. The server sends a proper Content-Type.
            val mimeType = playingVideo.mimeType.takeIf { it.isNotBlank() }
                ?: when (playingVideo.filename?.substringAfterLast('.', "")?.lowercase()) {
                    "mkv" -> "video/x-matroska"
                    "webm" -> "video/webm"
                    "avi" -> "video/x-msvideo"
                    "mov" -> "video/quicktime"
                    else -> "video/mp4"
                }
            val fileId = playingVideo.videoFile.id
            val fileSize = playingVideo.videoFile.size
            val dataProvider = TelegramVideoDataProvider(
                telegramService = telegramClient,
                fileId = fileId,
                fileSize = fileSize
            )
            activeDataProvider = dataProvider
            val stream = VideoStreamingServer.getRunningInstance()
                .registerVideo(fileId, fileSize, mimeType, dataProvider)
            currentStreamingToken = stream.token
            activePlayer.play(stream.url)
        }

        // Resume from saved position
        if (savedPosition != null && savedPosition > 0) {
            delay(500) // Wait for player to be ready
            activePlayer.seekTo(savedPosition)
        }
    }

    // Auto-play the next playlist item when the current one ends (callback comes from a native thread)
    DisposableEffect(activePlayer) {
        activePlayer.onMediaEnd = {
            scope.launch {
                val playlist = currentPlaylist
                if (autoPlayNext && playlist != null && playlist.hasNext) {
                    val next = playlist.next()
                    currentPlaylist = next
                    currentPlayingVideo = next.currentVideo
                }
            }
        }
        onDispose { activePlayer.onMediaEnd = null }
    }

    // Load videos when channel is selected
    LaunchedEffect(selectedChannel) {
        selectedChannel?.let { channel ->
            isLoadingVideos = true
            try {
                messagesRepository.getVideoMessages(channel.id)
                    .collect { page ->
                        currentVideos = page.messages
                        isLoadingVideos = false
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // channel changed: the new effect owns isLoadingVideos now
            } catch (e: Exception) {
                Log.e("Error loading videos: ${e.message}")
            }
            isLoadingVideos = false
        }
    }
    
    ShibaGramTheme(darkTheme = isDarkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (authState) {
                is AuthState.Authenticated -> {
                    // Main app content
                    if (currentPlayingVideo != null) {
                        // Video player
                        VideoPlayerScreen(
                            currentVideo = currentPlayingVideo,
                            playlist = currentPlaylist,
                            isPlaying = isPlayerPlaying,
                            currentPosition = playerPosition,
                            duration = if (playerDuration > 0) playerDuration else currentPlayingVideo?.duration?.times(1000L) ?: 0L,
                            volume = playerVolume,
                            onPlayPauseClick = { activePlayer.togglePlayPause() },
                            onSeek = { position -> activePlayer.seekTo(position) },
                            onVolumeChange = { vol -> activePlayer.setVolume(vol) },
                            onPreviousClick = {
                                currentPlaylist?.let {
                                    val newPlaylist = it.previous()
                                    currentPlaylist = newPlaylist
                                    currentPlayingVideo = newPlaylist.currentVideo
                                }
                            },
                            onNextClick = {
                                currentPlaylist?.let {
                                    val newPlaylist = it.next()
                                    currentPlaylist = newPlaylist
                                    currentPlayingVideo = newPlaylist.currentVideo
                                }
                            },
                            onFullscreenToggle = { onFullscreenChange(!isFullscreen) },
                            onCloseClick = {
                                // Save progress and stop player before clearing state
                                val pos = activePlayer.currentPosition.value
                                val dur = activePlayer.duration.value
                                val video = currentPlayingVideo
                                
                                // Clear state first to prevent LaunchedEffect from running
                                currentPlayingVideo = null
                                currentPlaylist = null
                                
                                // Stop player and detach window
                                activePlayer.stop()
                                activePlayer.detachWindow()
                                
                                // Save progress and update continue watching
                                if (video != null && pos > 0 && dur > 0) {
                                    scope.launch {
                                        watchHistoryRepository.saveProgress(
                                            messageId = video.id,
                                            chatId = video.chatId,
                                            position = pos,
                                            duration = dur,
                                            title = video.title.takeIf { it.isNotBlank() } ?: video.caption,
                                            thumbnailPath = video.thumbnail
                                        )
                                        watchHistoryRepository.updateWatchedChannel(video.chatId)
                                    }
                                }
                                
                                // Exit fullscreen when closing video
                                if (isFullscreen) {
                                    onFullscreenChange(false)
                                }
                            },
                            onVideoSelect = { index ->
                                currentPlaylist?.let {
                                    val newPlaylist = it.goTo(index)
                                    currentPlaylist = newPlaylist
                                    currentPlayingVideo = newPlaylist.currentVideo
                                }
                            },
                            isFullscreen = isFullscreen,
                            // Audio tracks
                            audioTracks = audioTracks,
                            currentAudioTrack = currentAudioTrack,
                            onAudioTrackSelect = { trackId ->
                                activePlayer.setAudioTrack(trackId)
                                currentAudioTrack = trackId
                            },
                            // Subtitle tracks
                            subtitleTracks = subtitleTracks,
                            currentSubtitleTrack = currentSubtitleTrack,
                            onSubtitleTrackSelect = { trackId ->
                                activePlayer.setSubtitleTrack(trackId)
                                currentSubtitleTrack = trackId
                            },
                            // Playback speed
                            currentPlaybackSpeed = playbackSpeed,
                            onPlaybackSpeedChange = { speed ->
                                activePlayer.setPlaybackSpeed(speed)
                                playbackSpeed = speed
                            },
                            renderControlsAsPopup = activePlayer.rendersToWindow,
                            videoContent = { overlay ->
                                // Video frame rendered directly in Compose
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (playerInitialized) {
                                        if (activePlayer.rendersToWindow) {
                                            // MPV: render into native window via a stable AWT Canvas
                                            val player = activePlayer

                                            // Thread-safe channels: AWT EDT -> Compose thread
                                            val mpvClickChannel = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED) }
                                            val mpvMoveChannel = remember { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }

                                            // Process click events on Compose thread
                                            LaunchedEffect(mpvClickChannel) {
                                                for (event in mpvClickChannel) {
                                                    // Only toggle controls when playing;
                                                    // when paused, controls are always visible
                                                    if (activePlayer.isPlaying.value) {
                                                        val wasVisible = showMpvControls
                                                        showMpvControls = !wasVisible
                                                        if (wasVisible) {
                                                            // Mark time of hide so mouse-move won't re-show immediately
                                                            lastControlsHideTime = System.currentTimeMillis()
                                                        }
                                                    }
                                                    mpvInteractionKey++
                                                }
                                            }
                                            // Process mouse-move events on Compose thread (coalesced)
                                            // Only reset auto-hide timer, don't show controls on mouse move
                                            LaunchedEffect(mpvMoveChannel) {
                                                for (event in mpvMoveChannel) {
                                                    // Only reset auto-hide timer when controls are visible
                                                    // Don't show controls on mouse movement (only on click)
                                                    if (showMpvControls) {
                                                        mpvInteractionKey++
                                                    }
                                                }
                                            }

                                            val mpvCanvas = remember(player) {
                                                object : java.awt.Canvas() {
                                                    override fun paint(g: java.awt.Graphics?) { /* no-op */ }
                                                    override fun update(g: java.awt.Graphics?) { /* no-op */ }
                                                }.also { canvas ->
                                                    canvas.background = java.awt.Color.BLACK
                                                    canvas.ignoreRepaint = true
                                                    
                                                    canvas.addMouseListener(object : java.awt.event.MouseAdapter() {
                                                        override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                                                            mpvClickChannel.trySend(Unit)
                                                        }
                                                    })
                                                    canvas.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                                                        override fun mouseMoved(e: java.awt.event.MouseEvent?) {
                                                            mpvMoveChannel.trySend(Unit)
                                                        }
                                                    })
                                                }
                                            }
                                            
                                            LaunchedEffect(mpvCanvas, player) {
                                                player.attachCanvas(mpvCanvas)
                                            }

                                            // Track canvas screen position/size for the overlay dialog
                                            var canvasScreenBounds by remember { mutableStateOf<java.awt.Rectangle?>(null) }
                                            
                                            // Use SwingPanel with just the Canvas for mpv rendering
                                            androidx.compose.ui.awt.SwingPanel(
                                                factory = { mpvCanvas },
                                                modifier = Modifier.fillMaxSize(),
                                                update = { }
                                            )
                                            
                                            // Track canvas bounds reactively
                                            DisposableEffect(player) {
                                                var parentRef: java.awt.Window? = null
                                                
                                                fun updateBounds() {
                                                    if (mpvCanvas.isShowing) {
                                                        try {
                                                            val loc = mpvCanvas.locationOnScreen
                                                            canvasScreenBounds = java.awt.Rectangle(
                                                                loc.x, loc.y, mpvCanvas.width, mpvCanvas.height
                                                            )
                                                        } catch (_: Exception) { }
                                                    }
                                                }
                                                
                                                // Same listener for the canvas and its window: both just refresh the bounds
                                                val boundsListener = object : java.awt.event.ComponentAdapter() {
                                                    override fun componentResized(e: java.awt.event.ComponentEvent?) = updateBounds()
                                                    override fun componentMoved(e: java.awt.event.ComponentEvent?) = updateBounds()
                                                }
                                                mpvCanvas.addComponentListener(boundsListener)
                                                
                                                val hierarchyListener = java.awt.event.HierarchyListener { e ->
                                                    if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                                                        if (mpvCanvas.isShowing) {
                                                            updateBounds()
                                                            val pw = javax.swing.SwingUtilities.getWindowAncestor(mpvCanvas)
                                                            if (pw != parentRef) {
                                                                parentRef?.removeComponentListener(boundsListener)
                                                                pw?.addComponentListener(boundsListener)
                                                                parentRef = pw
                                                            }
                                                        }
                                                    }
                                                }
                                                mpvCanvas.addHierarchyListener(hierarchyListener)
                                                
                                                // Check if already showing
                                                if (mpvCanvas.isShowing) {
                                                    updateBounds()
                                                    val pw = javax.swing.SwingUtilities.getWindowAncestor(mpvCanvas)
                                                    pw?.addComponentListener(boundsListener)
                                                    parentRef = pw
                                                }
                                                
                                                onDispose {
                                                    mpvCanvas.removeComponentListener(boundsListener)
                                                    mpvCanvas.removeHierarchyListener(hierarchyListener)
                                                    parentRef?.removeComponentListener(boundsListener)
                                                }
                                            }
                                            
                                            // Transparent overlay dialog using Compose Desktop's Dialog
                                            // which natively supports per-pixel transparency.
                                            // mpv renders directly to the Canvas HWND via DirectX,
                                            // so a separate OS window is the only way to overlay on top.
                                            if (!windowMinimized) {
                                                canvasScreenBounds?.let { bounds ->
                                                    // Only create dialog when bounds are valid (non-zero)
                                                    if (bounds.width <= 0 || bounds.height <= 0) return@let
                                                    val density = androidx.compose.ui.platform.LocalDensity.current
                                                    val dialogState = rememberDialogState(
                                                        position = WindowPosition(
                                                            x = with(density) { bounds.x.toDp() },
                                                            y = with(density) { bounds.y.toDp() }
                                                        ),
                                                        size = androidx.compose.ui.unit.DpSize(
                                                            width = with(density) { bounds.width.toDp() },
                                                            height = with(density) { bounds.height.toDp() }
                                                        )
                                                    )
                                                    
                                                    // Keep dialog positioned over the canvas
                                                    LaunchedEffect(bounds) {
                                                        dialogState.position = WindowPosition(
                                                            x = with(density) { bounds.x.toDp() },
                                                            y = with(density) { bounds.y.toDp() }
                                                        )
                                                        dialogState.size = androidx.compose.ui.unit.DpSize(
                                                            width = with(density) { bounds.width.toDp() },
                                                            height = with(density) { bounds.height.toDp() }
                                                        )
                                                    }
                                                    
                                                    DialogWindow(
                                                        onCloseRequest = {},
                                                        state = dialogState,
                                                        undecorated = true,
                                                        transparent = true,
                                                        resizable = false,
                                                        focusable = false,
                                                        title = ""
                                                    ) {
                                                        ShibaGramTheme(darkTheme = isDarkTheme) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxSize()
                                                                    .background(Color.Transparent)
                                                                    .clickable(
                                                                        interactionSource = remember { MutableInteractionSource() },
                                                                        indication = null
                                                                    ) {
                                                                        // Click on empty space → toggle controls
                                                                        mpvClickChannel.trySend(Unit)
                                                                    }
                                                            ) {
                                                                androidx.compose.animation.AnimatedVisibility(
                                                                    visible = showMpvControls,
                                                                    enter = androidx.compose.animation.fadeIn(),
                                                                    exit = androidx.compose.animation.fadeOut()
                                                                ) {
                                                                    overlay()
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            // VLC: render frames as Image
                                            playerFrame?.let { frame ->
                                                Image(
                                                    bitmap = frame,
                                                    contentDescription = "Video",
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Fit
                                                )
                                            } ?: run {
                                                // Show loading indicator while video is loading
                                                val isBuffering by activePlayer.isBuffering.collectAsState()
                                                if (isPlayerPlaying || isBuffering) {
                                                    CircularProgressIndicator(
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                            
                                            // Invoke the overlay lambda for VLC (it's empty usually, but just in case)
                                            overlay()
                                        }
                                    } else {
                                        // Show error if player not available
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = playerError ?: "Player not available",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = when (playerType) {
                                                    PlayerType.VLC -> "Please install VLC media player"
                                                    PlayerType.MPV -> "Please install mpv (https://mpv.io)"
                                                },
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        // Main navigation layout
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Navigation rail
                            ShibaGramNavigationRail(
                                selectedIndex = selectedNavIndex,
                                onIndexSelected = { selectedNavIndex = it }
                            )
                            
                            VerticalDivider()
                            
                            // Main content
                            Column(modifier = Modifier.weight(1f)) {
                                // Top bar with search
                                ShibaGramTopBar(
                                    title = when (selectedNavIndex) {
                                        0 -> "Home"
                                        1 -> "Channels"
                                        2 -> "Settings"
                                        else -> "ShibaGram"
                                    },
                                    onSearchClick = { showSearch = !showSearch },
                                    onViewModeChange = { userPrefs.setViewingMode(it) },
                                    currentViewMode = viewingMode
                                )
                                
                                // Search bar when visible
                                if (showSearch) {
                                    com.shirou.shibagram.ui.components.SearchBar(
                                        query = searchQuery,
                                        onQueryChange = { searchQuery = it },
                                        onSearch = { /* Search is reactive via LaunchedEffect */ },
                                        onClose = { 
                                            showSearch = false
                                            searchQuery = ""
                                            searchResults = emptyList()
                                            isSearching = false
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                
                                HorizontalDivider()
                                
                                // Screen content
                                when (selectedNavIndex) {
                                    0 -> HomeScreen(
                                        recentVideos = if (searchQuery.isNotBlank() && searchResults.isNotEmpty()) {
                                            // Group search results by channel
                                            val groupedResults = searchResults.groupBy { it.chatId }
                                            groupedResults.entries.map { (chatId, videos) ->
                                                val channel = channels.find { it.id == chatId } ?: Channel(
                                                    id = chatId,
                                                    name = "Channel"
                                                )
                                                channel to videos
                                            }
                                        } else {
                                            // Show channels sorted by last watched
                                            val watchedChannels = watchedChannelIds.mapNotNull { id ->
                                                channels.find { it.id == id }
                                            }
                                            // If no watched channels yet, show first 5
                                            val channelsToShow = if (watchedChannels.isEmpty()) channels.take(5) else watchedChannels
                                            channelsToShow.map { channel ->
                                                channel to filteredVideos.filter { it.chatId == channel.id }.take(10)
                                            }
                                        },
                                        continueWatchingVideos = continueWatchingVideos,
                                        savedVideos = savedVideos,
                                        onVideoClick = { video ->
                                            startPlayback(video, currentVideos, channels.find { it.id == video.chatId }?.name ?: "")
                                        },
                                        onChannelClick = { channel ->
                                            selectedChannel = channel
                                            selectedNavIndex = 1
                                        },
                                        onSaveVideo = { video ->
                                            scope.launch {
                                                if (savedVideos.any { it.id == video.id && it.chatId == video.chatId }) {
                                                    watchHistoryRepository.unsaveVideo(video.chatId, video.id)
                                                } else {
                                                    watchHistoryRepository.saveVideo(video)
                                                }
                                            }
                                        },
                                        onRemoveFromContinue = { video ->
                                            scope.launch {
                                                watchHistoryRepository.removeFromContinueWatching(video.chatId, video.id)
                                            }
                                        },
                                        isLoading = isLoadingVideos
                                    )
                                    
                                    1 -> ChannelsScreen(
                                        channels = filteredChannels,
                                        selectedChannel = selectedChannel,
                                        videos = if (searchQuery.isNotBlank() && searchResults.isNotEmpty()) {
                                            // When searching, show search results (filtered to selected channel if one is selected)
                                            if (selectedChannel != null) {
                                                (searchResults.filter { it.chatId == selectedChannel!!.id } + filteredVideos)
                                                    .distinctBy { it.id }
                                            } else searchResults
                                        } else filteredVideos,
                                        onChannelSelect = { selectedChannel = it },
                                        onVideoClick = { video ->
                                            startPlayback(video, currentVideos, selectedChannel?.name ?: "")
                                        },
                                        isLoading = isLoadingVideos,
                                        viewingMode = viewingMode,
                                        folders = chatFolders,
                                        selectedFolderId = selectedFolderId,
                                        onFolderSelect = { folderId ->
                                            channelsRepository.selectFolder(folderId)
                                        }
                                    )
                                    
                                    2 -> SettingsScreen(
                                        isDarkTheme = isDarkTheme,
                                        onDarkThemeChange = { 
                                            isDarkTheme = it
                                            userPrefs.setDarkTheme(it)
                                        },
                                        autoPlayNext = autoPlayNext,
                                        onAutoPlayNextChange = { userPrefs.setAutoPlayNext(it) },
                                        downloadPath = downloadPath,
                                        onDownloadPathChange = {
                                            downloadPath = it
                                            userPrefs.downloadLocation = it
                                        },
                                        onLogoutClick = {
                                            scope.launch {
                                                authRepository.logout()
                                            }
                                        },
                                        maxCacheSizeGb = maxCacheSizeGb,
                                        onMaxCacheSizeChange = { maxCacheSizeGb = it },
                                        currentCacheSize = currentCacheSize,
                                        onClearCacheClick = { 
                                            scope.launch {
                                                try {
                                                    currentCacheSize = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        cacheManager.clearAllCache()
                                                        cacheManager.getFormattedCacheSize()
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("Error clearing cache: ${e.message}")
                                                }
                                            }
                                        },
                                        playerType = playerType,
                                        onPlayerTypeChange = { newType ->
                                            // Stop current video before switching
                                            if (currentPlayingVideo != null) {
                                                activePlayer.stop()
                                                currentPlayingVideo = null
                                                currentPlaylist = null
                                            }
                                            playerType = newType
                                            userPrefs.playerType = newType
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                
                else -> {
                    // Login screen
                    LoginScreen(
                        authState = authState,
                        onPhoneNumberSubmit = { phone ->
                            scope.launch {
                                authRepository.sendPhoneNumber(phone)
                            }
                        },
                        onCodeSubmit = { code ->
                            scope.launch {
                                authRepository.sendVerificationCode(code)
                            }
                        },
                        onPasswordSubmit = { password ->
                            scope.launch {
                                authRepository.send2FAPassword(password)
                            }
                        },
                        onQRLoginClick = {
                            scope.launch {
                                authRepository.initiateQRLogin()
                            }
                        },
                        onBackClick = {
                            authRepository.resetToPhoneInput()
                        }
                    )
                }
            }
        }
    }
}
