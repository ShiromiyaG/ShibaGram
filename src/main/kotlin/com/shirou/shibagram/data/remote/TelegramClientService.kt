package com.shirou.shibagram.data.remote

import com.shirou.shibagram.BuildConfig
import com.shirou.shibagram.domain.model.*
import com.shirou.shibagram.tdlib.TdLib
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.drinkless.tdlib.TdApi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Paths

// Type alias for domain ChatFolder to avoid conflict with TdApi.ChatFolder
typealias DomainChatFolder = com.shirou.shibagram.domain.model.ChatFolder

/**
 * Telegram client service for desktop.
 * Uses official TDLib object-based interface via JNI.
 */
class TelegramClientService : AutoCloseable {
    
    private val _authState = MutableStateFlow<AuthState>(AuthState.NotAuthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()
    
    private val _chatFolders = MutableStateFlow<List<DomainChatFolder>>(emptyList())
    val chatFolders: StateFlow<List<DomainChatFolder>> = _chatFolders.asStateFlow()
    
    private val _selectedFolderId = MutableStateFlow<Int?>(null)
    val selectedFolderId: StateFlow<Int?> = _selectedFolderId.asStateFlow()
    
    private val _qrCodeLink = MutableStateFlow<String?>(null)
    val qrCodeLink: StateFlow<String?> = _qrCodeLink.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @Volatile
    private var tdlibParamsSet = false
    
    private val sessionPath = Paths.get(System.getProperty("user.home"), ".ShibaGram", "session").toString()
    
    // Download location - uses system Downloads folder
    val downloadPath: String = Paths.get(System.getProperty("user.home"), "Downloads", "ShibaGram").toString()
    
    private var client: Client? = null
    
    companion object {
        // API credentials loaded from .env at compile time
        private const val API_ID = BuildConfig.TELEGRAM_API_ID
        private const val API_HASH = BuildConfig.TELEGRAM_API_HASH
        
        @Volatile
        private var instance: TelegramClientService? = null
        
        fun getInstance(): TelegramClientService {
            return instance ?: synchronized(this) {
                instance ?: TelegramClientService().also { instance = it }
            }
        }
    }
    
    init {
        File(sessionPath).mkdirs()
    }
    
    suspend fun initialize() = withContext(Dispatchers.IO) {
        try {
            // Initialize TDLib native library
            TdLib.init()
            
            // Create client with update handler
            client = TdLib.create(
                updateHandler = { update -> handleUpdate(update) },
                updateExceptionHandler = { e -> 
                    println("TDLib update exception: ${e.message}")
                    e.printStackTrace()
                },
                defaultExceptionHandler = { e ->
                    println("TDLib default exception: ${e.message}")
                    e.printStackTrace()
                }
            )
            
            println("TDLib client initialized")
            
        } catch (e: Exception) {
            e.printStackTrace()
            _authState.value = AuthState.Error(e.message ?: "Failed to initialize Telegram client")
        }
    }
    
    private fun handleUpdate(update: TdApi.Object) {
        when (update) {
            is UpdateAuthorizationState -> {
                handleAuthorizationState(update.authorizationState)
            }
            is UpdateChatFolders -> {
                // Handle chat folders update
                val folders = update.chatFolders.map { folderInfo ->
                    DomainChatFolder(
                        id = folderInfo.id,
                        title = folderInfo.name?.text?.text ?: "Folder ${folderInfo.id}",
                        icon = folderInfo.icon?.name
                    )
                }
                _chatFolders.value = folders
                println("TDLib: Received ${folders.size} chat folders")
            }
            is UpdateNewChat -> {
                // Chat was loaded
            }
            is UpdateFile -> {
                // File download progress
            }
            else -> {
                // Other updates
            }
        }
    }
    
    private fun handleAuthorizationState(state: AuthorizationState) {
        when (state) {
            is AuthorizationStateWaitTdlibParameters -> {
                // Ensure download directory exists
                java.io.File(downloadPath).mkdirs()
                
                // Send TDLib parameters immediately (non-blocking send)
                val params = SetTdlibParameters().apply {
                    databaseDirectory = "$sessionPath/data"
                    filesDirectory = downloadPath // Use Downloads/ShibaGram folder
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = API_ID
                    apiHash = API_HASH
                    systemLanguageCode = "en"
                    deviceModel = "Desktop"
                    systemVersion = System.getProperty("os.name")
                    applicationVersion = "1.0.0"
                }
                client?.send(params, { response ->
                    if (response is Error) {
                        println("TDLib: Failed to set parameters: ${response.message}")
                        _authState.value = AuthState.Error("Failed to initialize: ${response.message}")
                    } else {
                        tdlibParamsSet = true
                        println("TDLib: Parameters set successfully")
                    }
                })
            }
            is AuthorizationStateWaitPhoneNumber -> {
                _authState.value = AuthState.WaitingForPhoneNumber
                // Request QR code automatically
                scope.launch {
                    requestQRCode()
                }
            }
            is AuthorizationStateWaitCode -> {
                val phoneNumber = state.codeInfo?.phoneNumber ?: ""
                _authState.value = AuthState.WaitingForCode(phoneNumber)
            }
            is AuthorizationStateWaitPassword -> {
                _authState.value = AuthState.WaitingFor2FA
            }
            is AuthorizationStateWaitOtherDeviceConfirmation -> {
                val link = state.link
                _qrCodeLink.value = link
                _authState.value = AuthState.QRCodeReady(link)
            }
            is AuthorizationStateReady -> {
                _authState.value = AuthState.Authenticated
                scope.launch { loadChannels() }
            }
            is AuthorizationStateClosed -> {
                _authState.value = AuthState.NotAuthenticated
            }
            is AuthorizationStateLoggingOut -> {
                _authState.value = AuthState.NotAuthenticated
            }
            else -> {
                // Other states
            }
        }
    }
    
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun sendAsync(function: TdApi.Function<*>): TdApi.Object? = withContext(Dispatchers.IO) {
        val client = this@TelegramClientService.client ?: return@withContext null
        
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            client.send(function, { obj ->
                if (continuation.isActive) {
                    continuation.resume(obj, null)
                }
            }, null)
        }
    }
    
    suspend fun sendPhoneNumber(phoneNumber: String) = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(SetAuthenticationPhoneNumber().apply {
                this.phoneNumber = phoneNumber
            })
            if (response is Error) {
                _authState.value = AuthState.Error(response.message)
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to send phone number")
        }
    }
    
    suspend fun sendVerificationCode(code: String) = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(CheckAuthenticationCode().apply {
                this.code = code
            })
            if (response is Error) {
                _authState.value = AuthState.Error(response.message)
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to verify code")
        }
    }
    
    suspend fun send2FAPassword(password: String) = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(CheckAuthenticationPassword().apply {
                this.password = password
            })
            if (response is Error) {
                _authState.value = AuthState.Error(response.message)
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to verify password")
        }
    }
    
    suspend fun requestQRCode(): String? = withContext(Dispatchers.IO) {
        try {
            // Wait for TDLib parameters to be set before requesting QR code
            var retries = 0
            while (!tdlibParamsSet && retries < 50) {
                delay(100)
                retries++
            }
            if (!tdlibParamsSet) {
                _authState.value = AuthState.Error("TDLib initialization timeout. Please restart the app.")
                return@withContext null
            }
            
            _authState.value = AuthState.QRLoginInProgress
            val response = sendAsync(RequestQrCodeAuthentication().apply {
                otherUserIds = longArrayOf()
            })
            if (response is Error) {
                _authState.value = AuthState.Error(response.message)
            }
            _qrCodeLink.value
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to request QR code")
            null
        }
    }
    
    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(LogOut())
            if (response is Error) {
                _authState.value = AuthState.Error(response.message)
            } else {
                _authState.value = AuthState.NotAuthenticated
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to logout")
        }
    }
    
    private suspend fun loadChannels() = withContext(Dispatchers.IO) {
        try {
            val selectedFolder = _selectedFolderId.value
            
            // If a folder is selected, load only that folder's chats
            if (selectedFolder != null) {
                val folderChannels = loadChannelsForFolder(selectedFolder)
                _channels.value = folderChannels
                println("Loaded ${folderChannels.size} channels from folder $selectedFolder")
                return@withContext
            }
            
            // Otherwise, load all chats (main + archive)
            val allChatIds = mutableListOf<Pair<Long, Long>>() // chatId to position
            
            // Load main chat list
            val mainChats = sendAsync(GetChats().apply {
                chatList = ChatListMain()
                limit = 200
            })
            
            if (mainChats is Chats) {
                mainChats.chatIds.forEachIndexed { index, chatId ->
                    allChatIds.add(chatId to index.toLong())
                }
            }
            
            // Load archived chats
            val archiveChats = sendAsync(GetChats().apply {
                chatList = ChatListArchive()
                limit = 200
            })
            
            if (archiveChats is Chats) {
                archiveChats.chatIds.forEachIndexed { index, chatId ->
                    if (allChatIds.none { it.first == chatId }) {
                        allChatIds.add(chatId to (10000L + index))
                    }
                }
            }
            
            // Load chats in parallel batches of 20 for faster startup
            val allChannels = java.util.concurrent.ConcurrentHashMap<Long, Channel>()
            val chatPositions = java.util.concurrent.ConcurrentHashMap<Long, Long>()
            
            allChatIds.chunked(20).forEach { batch ->
                val jobs = batch.map { (chatId, position) ->
                    scope.async {
                        loadChat(chatId)?.let { channel ->
                            val finalChannel = if (position >= 10000L) {
                                channel.copy(name = "📦 ${channel.name}")
                            } else channel
                            allChannels[chatId] = finalChannel
                            chatPositions[chatId] = position
                        }
                    }
                }
                jobs.forEach { it.await() }
                
                // Emit partial results after each batch so UI updates incrementally
                val sortedSoFar = allChannels.entries
                    .sortedBy { chatPositions[it.key] ?: Long.MAX_VALUE }
                    .map { it.value }
                _channels.value = sortedSoFar
            }
            
            println("Loaded ${allChannels.size} channels")
            
        } catch (e: Exception) {
            println("Error loading channels: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun loadChat(chatId: Long): Channel? = withContext(Dispatchers.IO) {
        try {
            val chat = sendAsync(GetChat().apply { this.chatId = chatId })
            
            if (chat is Chat) {
                val chatType = when (val type = chat.type) {
                    is ChatTypeSupergroup -> {
                        if (type.isChannel) com.shirou.shibagram.domain.model.ChatType.SUPERGROUP 
                        else com.shirou.shibagram.domain.model.ChatType.BASIC_GROUP
                    }
                    is ChatTypeBasicGroup -> com.shirou.shibagram.domain.model.ChatType.BASIC_GROUP
                    is ChatTypePrivate -> com.shirou.shibagram.domain.model.ChatType.PRIVATE
                    is ChatTypeSecret -> com.shirou.shibagram.domain.model.ChatType.SECRET
                    else -> com.shirou.shibagram.domain.model.ChatType.PRIVATE
                }
                
                // Try to get the photo path, download if not already downloaded
                var photoPath = chat.photo?.small?.local?.path?.takeIf { it.isNotEmpty() }
                if (photoPath == null && chat.photo?.small != null) {
                    // Start async download (non-blocking) — photo will appear once downloaded
                    val photoFile = chat.photo.small
                    if (!photoFile.local.isDownloadingCompleted && photoFile.id != 0) {
                        try {
                            sendAsync(DownloadFile().apply {
                                this.fileId = photoFile.id
                                this.priority = 1
                                this.synchronous = false
                            })
                        } catch (e: Exception) { /* ignore photo download failures */ }
                    }
                }
                
                Channel(
                    id = chat.id,
                    name = chat.title,
                    photoPath = photoPath,
                    description = null,
                    isAvailableOnTelegram = true,
                    chatType = chatType
                )
            } else null
        } catch (e: Exception) {
            println("Error loading chat $chatId: ${e.message}")
            null
        }
    }
    
    suspend fun refreshChannels() {
        loadChannels()
    }
    
    /**
     * Select a folder to filter channels.
     * @param folderId The folder ID, or null for "All Chats"
     */
    fun selectFolder(folderId: Int?) {
        _selectedFolderId.value = folderId
        scope.launch { loadChannels() }
    }
    
    /**
     * Get chats for a specific folder.
     */
    private suspend fun loadChannelsForFolder(folderId: Int): List<Channel> = withContext(Dispatchers.IO) {
        val channels = java.util.Collections.synchronizedList(mutableListOf<Channel>())
        try {
            val response = sendAsync(GetChats().apply {
                chatList = ChatListFolder(folderId)
                limit = 200
            })
            
            if (response is Chats) {
                // Load in parallel batches of 20
                response.chatIds.toList().chunked(20).forEach { batch ->
                    val jobs = batch.map { chatId ->
                        scope.async {
                            loadChat(chatId)?.let { channel ->
                                channels.add(channel)
                            }
                        }
                    }
                    jobs.forEach { it.await() }
                }
            }
        } catch (e: Exception) {
            println("Error loading folder channels: ${e.message}")
        }
        channels
    }
    
    fun getVideoMessages(channelId: Long, limit: Int = 100, maxVideos: Int = 500): Flow<List<MediaMessage>> = callbackFlow {
        val allVideos = mutableListOf<MediaMessage>()
        
        fun isVideoFile(mimeType: String?, fileName: String?): Boolean {
            if (mimeType?.startsWith("video/") == true) return true
            val extension = fileName?.substringAfterLast(".", "")?.lowercase() ?: ""
            return extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg")
        }
        
        try {
            // Search for video messages with pagination
            var fromMessageId = 0L
            var hasMore = true
            
            while (hasMore && allVideos.size < maxVideos) {
                val videoResponse = sendAsync(SearchChatMessages().apply {
                    this.chatId = channelId
                    this.limit = limit
                    this.fromMessageId = fromMessageId
                    this.filter = SearchMessagesFilterVideo()
                })
                
                if (videoResponse is FoundChatMessages) {
                    val messages = videoResponse.messages
                    if (messages.isEmpty()) {
                        hasMore = false
                    } else {
                        fromMessageId = messages.last().id
                        
                        for (msg in messages) {
                            val content = msg.content
                            
                            if (content is MessageVideo) {
                                val video = content.video
                                val file = video.video
                                val local = file.local
                                val thumbnail = video.thumbnail
                                
                                // Get thumbnail path if already downloaded, or start async download
                                var thumbnailPath = thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() }
                                if (thumbnailPath == null && thumbnail?.file?.id != null && thumbnail.file.id != 0) {
                                    try {
                                        sendAsync(DownloadFile().apply {
                                            this.fileId = thumbnail.file.id
                                            this.priority = 1
                                            this.synchronous = false
                                        })
                                    } catch (e: Exception) { }
                                }
                                
                                allVideos.add(MediaMessage(
                                    id = msg.id,
                                    date = msg.date,
                                    videoFile = VideoFile(
                                        id = file.id,
                                        size = file.size,
                                        downloadedSize = local.downloadedSize,
                                        localPath = local.path?.takeIf { it.isNotEmpty() },
                                        isDownloading = local.isDownloadingActive,
                                        isDownloaded = local.isDownloadingCompleted
                                    ),
                                    filename = video.fileName,
                                    thumbnailFileId = thumbnail?.file?.id,
                                    thumbnail = thumbnailPath,
                                    duration = video.duration,
                                    width = video.width,
                                    height = video.height,
                                    caption = content.caption?.text,
                                    mimeType = video.mimeType ?: "",
                                    chatId = msg.chatId,
                                    title = video.fileName ?: "Video"
                                ))
                            }
                        }
                        
                        // Emit intermediate results so UI can show videos as they load
                        trySend(allVideos.sortedByDescending { it.date })
                        
                        if (messages.size < limit) {
                            hasMore = false
                        }
                    }
                } else {
                    hasMore = false
                }
            }
            
            // Search for document messages (for mkv and other video files) with pagination
            fromMessageId = 0L
            hasMore = true
            
            while (hasMore && allVideos.size < maxVideos) {
                val docResponse = sendAsync(SearchChatMessages().apply {
                    this.chatId = channelId
                    this.limit = limit
                    this.fromMessageId = fromMessageId
                    this.filter = SearchMessagesFilterDocument()
                })
                
                if (docResponse is FoundChatMessages) {
                    val messages = docResponse.messages
                    if (messages.isEmpty()) {
                        hasMore = false
                    } else {
                        fromMessageId = messages.last().id
                        
                        for (msg in messages) {
                            val content = msg.content
                            
                            if (content is MessageDocument) {
                                val doc = content.document
                                val mimeType = doc.mimeType
                                val fileName = doc.fileName
                                
                                if (isVideoFile(mimeType, fileName)) {
                                    val file = doc.document
                                    val local = file.local
                                    val thumbnail = doc.thumbnail
                                    
                                    var thumbnailPath = thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() }
                                    if (thumbnailPath == null && thumbnail?.file?.id != null && thumbnail.file.id != 0) {
                                        try {
                                            sendAsync(DownloadFile().apply {
                                                this.fileId = thumbnail.file.id
                                                this.priority = 1
                                                this.synchronous = false
                                            })
                                        } catch (e: Exception) { }
                                    }
                                    
                                    allVideos.add(MediaMessage(
                                        id = msg.id,
                                        date = msg.date,
                                        videoFile = VideoFile(
                                            id = file.id,
                                            size = file.size,
                                            downloadedSize = local.downloadedSize,
                                            localPath = local.path?.takeIf { it.isNotEmpty() },
                                            isDownloading = local.isDownloadingActive,
                                            isDownloaded = local.isDownloadingCompleted
                                        ),
                                        filename = fileName,
                                        thumbnailFileId = thumbnail?.file?.id,
                                        thumbnail = thumbnailPath,
                                        caption = content.caption?.text,
                                        mimeType = mimeType ?: "",
                                        chatId = msg.chatId,
                                        title = fileName ?: "Document"
                                    ))
                                }
                            }
                        }
                        
                        // Emit intermediate results for documents too
                        trySend(allVideos.sortedByDescending { it.date })
                        
                        if (messages.size < limit) {
                            hasMore = false
                        }
                    }
                } else {
                    hasMore = false
                }
            }
            
            // Final sorted emission
            trySend(allVideos.sortedByDescending { it.date })
            
        } catch (e: Exception) {
            println("Error getting video messages: ${e.message}")
            trySend(emptyList())
        }
        
        awaitClose()
    }
    
    suspend fun downloadFile(fileId: Int, priority: Int = 1): File? = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(DownloadFile().apply {
                this.fileId = fileId
                this.priority = priority
                this.synchronous = true
            })
            
            if (response is TdApi.File) {
                val local = response.local
                val isComplete = local?.isDownloadingCompleted ?: false
                val path = local?.path
                
                if (isComplete && path != null && path.isNotEmpty()) {
                    File(path)
                } else null
            } else null
        } catch (e: Exception) {
            println("Error downloading file: ${e.message}")
            null
        }
    }

    /**
     * Synchronously download a specific part of a file.
     * Used for streaming to fetch metadata or specific chunks.
     */
    suspend fun downloadFilePart(fileId: Int, offset: Long, limit: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(DownloadFile().apply {
                this.fileId = fileId
                this.priority = 32
                this.offset = offset
                this.limit = limit.toLong()
                this.synchronous = true
            })
            
            // If success, TDLib returns the File object (updated)
            response is TdApi.File
        } catch (e: Exception) {
            println("Error downloading file part: ${e.message}")
            false
        }
    }
    
    suspend fun downloadFileProgressively(
        fileId: Int,
        onChunkDownloaded: (offset: Long, data: ByteArray, filePath: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            var lastDownloadedSize = 0L
            var filePath: String
            
            // Start the download
            sendAsync(DownloadFile().apply {
                this.fileId = fileId
                this.priority = 32
                this.synchronous = false
            })
            
            // Poll for download progress
            while (true) {
                delay(100)
                
                val fileInfo = sendAsync(GetFile().apply {
                    this.fileId = fileId
                })
                
                if (fileInfo is TdApi.File) {
                    val local = fileInfo.local ?: continue
                    val currentDownloaded = local.downloadedSize
                    filePath = local.path ?: ""
                    
                    if (currentDownloaded > lastDownloadedSize && filePath.isNotEmpty()) {
                        try {
                            val file = File(filePath)
                            if (file.exists()) {
                                val newBytes = (currentDownloaded - lastDownloadedSize).toInt()
                                val data = ByteArray(newBytes)
                                file.inputStream().use { stream ->
                                    stream.skip(lastDownloadedSize)
                                    stream.read(data)
                                }
                                onChunkDownloaded(lastDownloadedSize, data, filePath)
                                lastDownloadedSize = currentDownloaded
                            }
                        } catch (e: Exception) {
                            println("Error reading chunk: ${e.message}")
                        }
                    }
                    
                    if (local.isDownloadingCompleted) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            println("Error in progressive download: ${e.message}")
        }
    }
    
    suspend fun getFileInfo(fileId: Int): TdApi.File? = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(GetFile().apply {
                this.fileId = fileId
            })
            if (response is TdApi.File) response else null
        } catch (e: Exception) {
            println("Error getting file info: ${e.message}")
            null
        }
    }
    
    fun getFileDownloadProgress(fileId: Int): Flow<Float> = callbackFlow {
        trySend(0f)
        awaitClose()
    }
    
    /**
     * Search for channels/chats by name from the loaded channels list.
     */
    fun searchChannelsByName(query: String): List<Channel> {
        if (query.isBlank()) return _channels.value
        return _channels.value.filter { channel ->
            channel.name.contains(query, ignoreCase = true) ||
            channel.description?.contains(query, ignoreCase = true) == true
        }
    }
    
    /**
     * Search messages across all chats or a specific chat.
     * Uses TDLib SearchMessages for global search.
     */
    fun searchMessages(query: String, chatId: Long? = null, limit: Int = 50): Flow<List<MediaMessage>> = callbackFlow {
        if (query.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        
        val results = mutableListOf<MediaMessage>()
        
        fun isVideoFile(mimeType: String?, fileName: String?): Boolean {
            if (mimeType?.startsWith("video/") == true) return true
            val extension = fileName?.substringAfterLast(".", "")?.lowercase() ?: ""
            return extension in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg")
        }
        
        try {
            if (chatId != null) {
                // Search within a specific chat
                val response = sendAsync(SearchChatMessages().apply {
                    this.chatId = chatId
                    this.query = query
                    this.limit = limit
                    this.fromMessageId = 0L
                    this.filter = SearchMessagesFilterVideo()
                })
                
                if (response is FoundChatMessages) {
                    for (msg in response.messages) {
                        val content = msg.content
                        if (content is MessageVideo) {
                            val video = content.video
                            val file = video.video
                            val local = file.local
                            val thumbnail = video.thumbnail
                            var thumbnailPath = thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() }
                            
                            results.add(MediaMessage(
                                id = msg.id,
                                date = msg.date,
                                videoFile = VideoFile(
                                    id = file.id,
                                    size = file.size,
                                    downloadedSize = local.downloadedSize,
                                    localPath = local.path?.takeIf { it.isNotEmpty() },
                                    isDownloading = local.isDownloadingActive,
                                    isDownloaded = local.isDownloadingCompleted
                                ),
                                filename = video.fileName,
                                thumbnailFileId = thumbnail?.file?.id,
                                thumbnail = thumbnailPath,
                                duration = video.duration,
                                width = video.width,
                                height = video.height,
                                caption = content.caption?.text,
                                mimeType = video.mimeType ?: "",
                                chatId = msg.chatId,
                                title = video.fileName ?: "Video"
                            ))
                        }
                    }
                }
                
                // Also search documents that are video files
                val docResponse = sendAsync(SearchChatMessages().apply {
                    this.chatId = chatId
                    this.query = query
                    this.limit = limit
                    this.fromMessageId = 0L
                    this.filter = SearchMessagesFilterDocument()
                })
                
                if (docResponse is FoundChatMessages) {
                    for (msg in docResponse.messages) {
                        val content = msg.content
                        if (content is MessageDocument) {
                            val doc = content.document
                            if (isVideoFile(doc.mimeType, doc.fileName)) {
                                val file = doc.document
                                val local = file.local
                                val thumbnail = doc.thumbnail
                                var thumbnailPath = thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() }
                                
                                results.add(MediaMessage(
                                    id = msg.id,
                                    date = msg.date,
                                    videoFile = VideoFile(
                                        id = file.id,
                                        size = file.size,
                                        downloadedSize = local.downloadedSize,
                                        localPath = local.path?.takeIf { it.isNotEmpty() },
                                        isDownloading = local.isDownloadingActive,
                                        isDownloaded = local.isDownloadingCompleted
                                    ),
                                    filename = doc.fileName,
                                    thumbnailFileId = thumbnail?.file?.id,
                                    thumbnail = thumbnailPath,
                                    caption = content.caption?.text,
                                    mimeType = doc.mimeType ?: "",
                                    chatId = msg.chatId,
                                    title = doc.fileName ?: "Document"
                                ))
                            }
                        }
                    }
                }
            } else {
                // Global search across all chats - parallel for speed
                val channelsToSearch = _channels.value.take(20)
                val parallelResults = java.util.Collections.synchronizedList(mutableListOf<MediaMessage>())
                
                channelsToSearch.chunked(5).forEach { batch ->
                    val jobs = batch.map { channel ->
                        scope.async {
                            try {
                                val response = sendAsync(SearchChatMessages().apply {
                                    this.chatId = channel.id
                                    this.query = query
                                    this.limit = 10
                                    this.fromMessageId = 0L
                                    this.filter = SearchMessagesFilterVideo()
                                })
                                
                                if (response is FoundChatMessages) {
                                    for (msg in response.messages) {
                                        val content = msg.content
                                        if (content is MessageVideo) {
                                            val video = content.video
                                            val file = video.video
                                            val local = file.local
                                            val thumbnail = video.thumbnail
                                            var thumbnailPath = thumbnail?.file?.local?.path?.takeIf { it.isNotEmpty() }
                                            
                                            parallelResults.add(MediaMessage(
                                                id = msg.id,
                                                date = msg.date,
                                                videoFile = VideoFile(
                                                    id = file.id,
                                                    size = file.size,
                                                    downloadedSize = local.downloadedSize,
                                                    localPath = local.path?.takeIf { it.isNotEmpty() },
                                                    isDownloading = local.isDownloadingActive,
                                                    isDownloaded = local.isDownloadingCompleted
                                                ),
                                                filename = video.fileName,
                                                thumbnailFileId = thumbnail?.file?.id,
                                                thumbnail = thumbnailPath,
                                                duration = video.duration,
                                                width = video.width,
                                                height = video.height,
                                                caption = content.caption?.text,
                                                mimeType = video.mimeType ?: "",
                                                chatId = msg.chatId,
                                                title = video.fileName ?: "Video"
                                            ))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Skip failed channels
                            }
                        }
                    }
                    jobs.forEach { it.await() }
                    
                    if (parallelResults.size >= limit) return@forEach
                }
                
                results.addAll(parallelResults.take(limit))
            }
            
            trySend(results.sortedByDescending { it.date })
        } catch (e: Exception) {
            println("Error searching messages: ${e.message}")
            trySend(emptyList())
        }
        
        close()
    }
    
    override fun close() {
        scope.cancel()
    }
}
