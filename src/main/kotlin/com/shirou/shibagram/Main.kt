package com.shirou.shibagram

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
    var isVideoPlaying by remember { mutableStateOf(false) }
    
    val windowState = rememberWindowState(
        placement = WindowPlacement.Floating,
        width = 1280.dp,
        height = 800.dp
    )
    
    // Update window placement when fullscreen changes
    LaunchedEffect(isFullscreen) {
        windowState.placement = if (isFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }
    
    // Prevent window from minimizing when video player is active
    LaunchedEffect(isVideoPlaying, windowState.isMinimized) {
        if (isVideoPlaying && windowState.isMinimized) {
            // Immediately restore the window when it gets minimized during video playback
            windowState.isMinimized = false
        }
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
        
        // Add window focus listener to prevent minimization during video playback
        DisposableEffect(Unit) {
            val windowFocusListener = object : java.awt.event.WindowAdapter() {
                override fun windowDeactivated(e: java.awt.event.WindowEvent?) {
                    // When the window loses focus while video is playing,
                    // ensure it stays visible (not minimized)
                    if (isVideoPlaying) {
                        javax.swing.SwingUtilities.invokeLater {
                            if (window.state == java.awt.Frame.ICONIFIED) {
                                window.state = java.awt.Frame.NORMAL
                            }
                        }
                    }
                }
                
                override fun windowIconified(e: java.awt.event.WindowEvent?) {
                    // Prevent iconification (minimize) when video is playing
                    if (isVideoPlaying) {
                        javax.swing.SwingUtilities.invokeLater {
                            window.state = java.awt.Frame.NORMAL
                        }
                    }
                }
            }
            window.addWindowListener(windowFocusListener)
            onDispose {
                window.removeWindowListener(windowFocusListener)
            }
        }
        
        ShibaGramApp(
            isFullscreen = isFullscreen,
            onFullscreenChange = { isFullscreen = it },
            onVideoPlayingChange = { isVideoPlaying = it }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShibaGramApp(
    isFullscreen: Boolean = false,
    onFullscreenChange: (Boolean) -> Unit = {},
    onVideoPlayingChange: (Boolean) -> Unit = {}
) {
    // State
    var isDarkTheme by remember { mutableStateOf(false) }
    val systemDarkTheme = isSystemInDarkTheme()
    
    // Use system theme by default
    val actualDarkTheme = isDarkTheme || systemDarkTheme
    
    // Repositories
    val authRepository = remember { TelegramAuthRepository() }
    val channelsRepository = remember { TelegramChannelsRepository() }
    val messagesRepository = remember { ChannelMessagesRepository() }
    val watchHistoryRepository = remember { com.shirou.shibagram.data.repository.WatchHistoryRepository() }
    val telegramClient = remember { TelegramClientService.getInstance() }
    
    // Player preferences (persisted)
    val userPrefs = remember { com.shirou.shibagram.data.preferences.UserPreferencesRepository.getInstance() }
    var playerType by remember { mutableStateOf(userPrefs.playerType) }
    var mpvPath by remember { mutableStateOf(userPrefs.mpvPath) }

    // MPV controls visibility (managed here because Canvas mouse listener lives here)
    var showMpvControls by remember { mutableStateOf(true) }
    var mpvInteractionKey by remember { mutableStateOf(0) }
    // Debounce: ignore mouse-move-to-show for 600ms after a click-to-hide
    var lastControlsHideTime by remember { mutableStateOf(0L) }

    // Active player engine (VLC or MPV) — recreated when player type changes
    val activePlayer: MediaPlayerEngine = remember(playerType, mpvPath) {
        when (playerType) {
            PlayerType.VLC -> VlcMediaPlayer()
            PlayerType.MPV -> MpvMediaPlayer(mpvPath.takeIf { it.isNotBlank() })
        }
    }

    // Video cache manager
    val cacheManager = remember { VideoCacheManager.getInstance() }
    var maxCacheSizeGb by remember { mutableStateOf(2f) } // Default 2 GB
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

    // Update tracks when video starts playing
    LaunchedEffect(isPlayerPlaying, playerDuration, activePlayer) {
        if (isPlayerPlaying && playerDuration > 0) {
            // Small delay to let player load track info
            kotlinx.coroutines.delay(500)
            audioTracks = activePlayer.getAudioTracks()
            currentAudioTrack = activePlayer.getCurrentAudioTrack()
            subtitleTracks = activePlayer.getSubtitleTracks()
            currentSubtitleTrack = activePlayer.getCurrentSubtitleTrack()
        }
    }
    
    // Initialize / release player (restarts when playerType or mpvPath changes)
    DisposableEffect(activePlayer) {
        val player = activePlayer // capture for onDispose
        player.onError = { error -> playerError = error }
        playerInitialized = player.initialize()
        onDispose {
            player.release()
            playerInitialized = false
        }
    }
    
    // Stop playback when switching player type
    LaunchedEffect(playerType) {
        // Give a brief moment so UI can settle
        kotlinx.coroutines.delay(50)
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
    var viewingMode by remember { mutableStateOf(ViewingMode.GRID) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentPlayingVideo by remember { mutableStateOf<MediaMessage?>(null) }
    var currentPlaylist by remember { mutableStateOf<VideoPlaylist?>(null) }
    var autoPlayNext by remember { mutableStateOf(true) }
    var downloadPath by remember { mutableStateOf(java.nio.file.Paths.get(System.getProperty("user.home"), "Downloads", "ShibaGram").toString()) }
    
    // Notify parent about video playing state changes
    LaunchedEffect(currentPlayingVideo) {
        onVideoPlayingChange(currentPlayingVideo != null)
    }
    
    // Continue watching and saved videos from repository
    val continueWatchingVideos by watchHistoryRepository.continueWatchingVideos.collectAsState()
    val savedVideos by watchHistoryRepository.savedVideos.collectAsState()
    
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
    }
    
    // Save playback progress periodically (debounced - only keyed on the video, NOT on position)
    LaunchedEffect(currentPlayingVideo) {
        if (currentPlayingVideo != null) {
            while (true) {
                kotlinx.coroutines.delay(5000)
                val pos = activePlayer.currentPosition.value
                val dur = activePlayer.duration.value
                if (pos > 0 && dur > 0) {
                    watchHistoryRepository.saveProgress(
                        messageId = currentPlayingVideo!!.id,
                        chatId = currentPlayingVideo!!.chatId,
                        position = pos,
                        duration = dur
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
            println("Search error: ${e.message}")
        }
        isSearching = false
    }
    
    // Track active data provider for cleanup
    var activeDataProvider by remember { mutableStateOf<TelegramVideoDataProvider?>(null) }
    
    // Play video when selected
    LaunchedEffect(currentPlayingVideo) {
        currentPlayingVideo?.let { video ->
            if (!playerInitialized) {
                println("Player not initialized, cannot play video")
                return@let
            }
            
            // Cleanup previous stream
            currentStreamingToken?.let { token ->
                VideoStreamingServer.getInstance().unregisterVideo(token)
            }
            currentStreamingToken = null
            activeDataProvider?.close()
            activeDataProvider = null
            
            // Check if video is already fully downloaded locally
            val localPath = video.videoFile.localPath
            if (localPath != null && video.videoFile.isDownloaded) {
                // Play local file directly
                println("Playing local file: $localPath")
                activePlayer.play(localPath)
            } else {
                // Start progressive download and play from local file as it downloads
                scope.launch {
                    val fileId = video.videoFile.id
                    val fileSize = video.videoFile.size
                    
                    // Create data provider to start the download
                    val dataProvider = TelegramVideoDataProvider(
                        telegramService = telegramClient,
                        fileId = fileId,
                        fileSize = fileSize
                    )
                    activeDataProvider = dataProvider
                    
                    // Wait for initial buffer before starting playback
                    println("Buffering video (${fileSize / 1024 / 1024}MB total)...")
                    val bufferReady = dataProvider.waitForBuffer()
                    if (!bufferReady) {
                        println("Failed to buffer video")
                        dataProvider.close()
                        activeDataProvider = null
                        return@launch
                    }
                    println("Buffer ready")
                    
                    // Get the local file path that TDLib is downloading to
                    val fileInfo = telegramClient.getFileInfo(fileId)
                    val downloadingPath = fileInfo?.local?.path?.takeIf { it.isNotEmpty() }
                    
                    if (downloadingPath != null) {
                        println("Playing from local file (still downloading): $downloadingPath")
                        // Play the local file directly - player can handle partially downloaded files
                        activePlayer.play(downloadingPath)
                    } else {
                        // Fallback to HTTP streaming (lazy-start server only now)
                        val streamingServer = VideoStreamingServer.getRunningInstance()
                        val mimeType = video.mimeType ?: "video/mp4"
                        val streamUrl = streamingServer.registerVideo(fileId, fileSize, mimeType, dataProvider)
                        currentStreamingToken = "video_${fileId}"
                        println("Streaming video from: $streamUrl")
                        activePlayer.play(streamUrl)
                    }
                }
            }
        } ?: run {
            // Cleanup when stopping
            currentStreamingToken?.let { token ->
                VideoStreamingServer.getInstance().unregisterVideo(token)
            }
            currentStreamingToken = null
            activeDataProvider?.close()
            activeDataProvider = null
            if (playerInitialized) activePlayer.stop()
            // Trigger cache cleanup after video stops
            cacheManager.triggerCleanup()
        }
    }
    
    // Load videos when channel is selected
    LaunchedEffect(selectedChannel) {
        selectedChannel?.let { channel ->
            isLoadingVideos = true
            try {
                messagesRepository.getVideoMessagesWithPagination(channel.id)
                    .collect { page ->
                        currentVideos = page.messages
                        isLoadingVideos = false
                    }
            } catch (e: Exception) {
                isLoadingVideos = false
            }
        }
    }
    
    ShibaGramTheme(darkTheme = actualDarkTheme) {
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
                                activePlayer.stop()
                                currentPlayingVideo = null
                                currentPlaylist = null
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
                            audioTracks = audioTracks.map { com.shirou.shibagram.ui.screens.TrackInfo(it.id, it.name) },
                            currentAudioTrack = currentAudioTrack,
                            onAudioTrackSelect = { trackId ->
                                activePlayer.setAudioTrack(trackId)
                                currentAudioTrack = trackId
                            },
                            // Subtitle tracks
                            subtitleTracks = subtitleTracks.map { com.shirou.shibagram.ui.screens.TrackInfo(it.id, it.name) },
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
                            mpvControlsVisible = showMpvControls,
                            onMpvControlsInteraction = {
                                showMpvControls = true
                                mpvInteractionKey++
                            },
                            videoContent = {
                                // Video frame rendered directly in Compose
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (playerInitialized) {
                                        if (activePlayer.rendersToWindow) {
                                            // MPV: render into native window via a stable AWT Canvas
                                            val player = activePlayer

                                            // Thread-safe channels: AWT EDT → Compose thread
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
                                            LaunchedEffect(mpvMoveChannel) {
                                                for (event in mpvMoveChannel) {
                                                    if (!showMpvControls && activePlayer.isPlaying.value) {
                                                        // Debounce: only re-show controls if 600ms has passed
                                                        // since the user explicitly clicked to hide them
                                                        val elapsed = System.currentTimeMillis() - lastControlsHideTime
                                                        if (elapsed > 600) {
                                                            showMpvControls = true
                                                        }
                                                    }
                                                    // Only reset auto-hide timer when controls are visible
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
                                                    // Click to toggle controls, mouse move to show
                                                    // Events dispatched via channels to Compose thread (NOT AWT EDT)
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
                                            androidx.compose.ui.awt.SwingPanel(
                                                factory = { mpvCanvas },
                                                modifier = Modifier.fillMaxSize()
                                            )
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
                                    onViewModeChange = { viewingMode = it },
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
                                            channels.take(5).map { channel ->
                                                channel to filteredVideos.filter { it.chatId == channel.id }.take(10)
                                            }
                                        },
                                        continueWatchingVideos = continueWatchingVideos,
                                        savedVideos = savedVideos,
                                        onVideoClick = { video ->
                                            currentPlayingVideo = video
                                            currentPlaylist = VideoPlaylist(
                                                id = video.chatId,
                                                channelId = video.chatId,
                                                channelName = channels.find { it.id == video.chatId }?.name ?: "",
                                                videos = currentVideos,
                                                currentIndex = currentVideos.indexOf(video)
                                            )
                                        },
                                        onChannelClick = { channel ->
                                            selectedChannel = channel
                                            selectedNavIndex = 1
                                        },
                                        onSaveVideo = { video ->
                                            scope.launch {
                                                if (savedVideos.any { it.id == video.id }) {
                                                    watchHistoryRepository.unsaveVideo(video.id)
                                                } else {
                                                    watchHistoryRepository.saveVideo(video)
                                                }
                                            }
                                        },
                                        onRemoveFromContinue = { video ->
                                            scope.launch {
                                                watchHistoryRepository.removeFromContinueWatching(video.id)
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
                                            currentPlayingVideo = video
                                            currentPlaylist = VideoPlaylist(
                                                id = selectedChannel?.id ?: 0,
                                                channelId = selectedChannel?.id ?: 0,
                                                channelName = selectedChannel?.name ?: "",
                                                videos = currentVideos,
                                                currentIndex = currentVideos.indexOf(video)
                                            )
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
                                        onDarkThemeChange = { isDarkTheme = it },
                                        autoPlayNext = autoPlayNext,
                                        onAutoPlayNextChange = { autoPlayNext = it },
                                        downloadPath = downloadPath,
                                        onDownloadPathChange = { downloadPath = it },
                                        onLogoutClick = {
                                            scope.launch {
                                                authRepository.logout()
                                            }
                                        },
                                        maxCacheSizeGb = maxCacheSizeGb,
                                        onMaxCacheSizeChange = { maxCacheSizeGb = it },
                                        currentCacheSize = currentCacheSize,
                                        onClearCacheClick = { 
                                            // Clear telegram cache and downloaded videos
                                            scope.launch {
                                                try {
                                                    cacheManager.clearAllCache()
                                                    currentCacheSize = cacheManager.getFormattedCacheSize()
                                                    println("Cache cleared successfully")
                                                } catch (e: Exception) {
                                                    println("Error clearing cache: ${e.message}")
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
                                        },
                                        mpvPath = mpvPath,
                                        onMpvPathChange = {
                                            mpvPath = it
                                            userPrefs.mpvPath = it
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
