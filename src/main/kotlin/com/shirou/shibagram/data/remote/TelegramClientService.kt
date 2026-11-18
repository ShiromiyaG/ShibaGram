package com.shirou.shibagram.data.remote

import com.shirou.shibagram.util.Log
import com.shirou.shibagram.BuildConfig
import com.shirou.shibagram.domain.model.*
import com.shirou.shibagram.tdlib.TdLib
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.drinkless.tdlib.TdApi.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.nio.file.Paths

// Type alias for domain ChatFolder to avoid conflict with TdApi.ChatFolder
typealias DomainChatFolder = com.shirou.shibagram.domain.model.ChatFolder

/**
 * Telegram client service for desktop.
 * Uses official TDLib object-based interface via JNI.
 */
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "mpeg", "mpg")

internal fun isVideoFile(mimeType: String?, fileName: String?): Boolean {
    if (mimeType?.startsWith("video/") == true) return true
    val extension = fileName?.substringAfterLast(".", "")?.lowercase() ?: return false
    return extension in VIDEO_EXTENSIONS
}

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
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Completed once TDLib accepted SetTdlibParameters (QR login must wait for it)
    private val tdlibParamsReady = CompletableDeferred<Unit>()

    // File state changes pushed by TDLib (download progress); used by the streaming layer
    private val _fileUpdates = MutableSharedFlow<TdApi.File>(extraBufferCapacity = 256, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val fileUpdates: SharedFlow<TdApi.File> = _fileUpdates.asSharedFlow()

    // Current channel (re)load; cancelled when a new folder is selected so older loads can't overwrite newer ones
    private var loadJob: Job? = null
    
    private val sessionPath = Paths.get(System.getProperty("user.home"), ".ShibaGram", "session").toString()
    
    // Download location - uses system Downloads folder
    // Read once at startup: changing the folder in Settings takes effect on the next launch
    val downloadPath: String = com.shirou.shibagram.data.preferences.UserPreferencesRepository.getInstance().downloadLocation
    
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
                    Log.e("TDLib update exception: ${e.message}")
                    Log.e("Unexpected error", e)
                },
                defaultExceptionHandler = { e ->
                    Log.e("TDLib default exception: ${e.message}")
                    Log.e("Unexpected error", e)
                }
            )
            
            Log.d("TDLib client initialized")
            
        } catch (e: Throwable) {
            // Throwable: a missing tdjni.dll surfaces as UnsatisfiedLinkError, which is not an Exception
            if (e is CancellationException) throw e
            Log.e("Unexpected error", e)
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
                Log.d("TDLib: Received ${folders.size} chat folders")
            }
            is UpdateNewChat -> {
                // Chat was loaded
            }
            is UpdateFile -> {
                _fileUpdates.tryEmit(update.file)
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
                    applicationVersion = BuildConfig.VERSION
                }
                client?.send(params, { response ->
                    if (response is Error) {
                        Log.e("TDLib: Failed to set parameters: ${response.message}")
                        _authState.value = AuthState.Error("Failed to initialize: ${response.message}")
                    } else {
                        tdlibParamsReady.complete(Unit)
                        Log.d("TDLib: Parameters set successfully")
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
                reloadChannels()
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
                if (response.code == 406) {
                    _authState.value = AuthState.WaitingFor2FA
                } else {
                    _authState.value = AuthState.Error(response.message)
                }
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error(e.message ?: "Failed to verify password")
        }
    }
    
    suspend fun requestQRCode(): String? = withContext(Dispatchers.IO) {
        try {
            // Wait for TDLib parameters to be set before requesting QR code
            if (withTimeoutOrNull(15_000) { tdlibParamsReady.await() } == null) {
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
    
    fun resetToPhoneInput() {
        _authState.value = AuthState.WaitingForPhoneNumber
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
                Log.d("Loaded ${folderChannels.size} channels from folder $selectedFolder")
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
            
            for (batch in allChatIds.chunked(20)) {
                coroutineScope { batch.map { (chatId, position) ->
                    async {
                        loadChat(chatId)?.let { channel ->
                            val finalChannel = if (position >= 10000L) {
                                channel.copy(name = "📦 ${channel.name}")
                            } else channel
                            allChannels[chatId] = finalChannel
                            chatPositions[chatId] = position
                        }
                    }
                }.awaitAll() }
                
                // Emit partial results after each batch so UI updates incrementally
                val sortedSoFar = allChannels.entries
                    .sortedBy { chatPositions[it.key] ?: Long.MAX_VALUE }
                    .map { it.value }
                _channels.value = sortedSoFar
            }
            
            Log.d("Loaded ${allChannels.size} channels")
            
        } catch (e: Exception) {
            Log.e("Error loading channels: ${e.message}")
            Log.e("Unexpected error", e)
        }
    }
    
    private suspend fun loadChat(chatId: Long): Channel? = withContext(Dispatchers.IO) {
        try {
            val chat = sendAsync(GetChat().apply { this.chatId = chatId })
            
            if (chat is Chat) {
                val chatType = when (val type = chat.type) {
                    is ChatTypeSupergroup -> {
                        if (type.isChannel) com.shirou.shibagram.domain.model.ChatType.CHANNEL
                        else com.shirou.shibagram.domain.model.ChatType.SUPERGROUP
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
                            client?.send(DownloadFile(photoFile.id, 1, 0, 0, false), null)
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
            Log.e("Error loading chat $chatId: ${e.message}")
            null
        }
    }
    
    private fun reloadChannels() {
        loadJob?.cancel()
        loadJob = scope.launch { loadChannels() }
    }
    
    /**
     * Select a folder to filter channels.
     * @param folderId The folder ID, or null for "All Chats"
     */
    fun selectFolder(folderId: Int?) {
        _selectedFolderId.value = folderId
        reloadChannels()
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
                for (batch in response.chatIds.toList().chunked(20)) {
                    coroutineScope {
                        batch.map { chatId -> async { loadChat(chatId) } }.awaitAll()
                    }.filterNotNullTo(channels)
                }
            }
        } catch (e: Exception) {
            Log.e("Error loading folder channels: ${e.message}")
        }
        channels
    }
    
    /**
     * Maps a TDLib message to a [MediaMessage] when it carries a video (native video, or a document
     * that looks like a video file). Returns null for anything else.
     * @param anyDocument accept documents even if they don't look like a video (used for direct lookups).
     * @param requestThumbnail start a background download of the thumbnail if it isn't local yet.
     */
    private fun Message.toMediaMessage(anyDocument: Boolean = false, requestThumbnail: Boolean = false): MediaMessage? {
        val content = content
        val file: TdApi.File
        val thumbnail: TdApi.Thumbnail?
        val fileName: String?
        val mimeType: String?
        var duration = 0
        var width = 0
        var height = 0
        val caption: String?
        val fallbackTitle: String
        when (content) {
            is MessageVideo -> {
                val video = content.video
                file = video.video
                thumbnail = video.thumbnail
                fileName = video.fileName
                mimeType = video.mimeType
                duration = video.duration
                width = video.width
                height = video.height
                caption = content.caption?.text
                fallbackTitle = "Video"
            }
            is MessageDocument -> {
                val doc = content.document
                if (!anyDocument && !isVideoFile(doc.mimeType, doc.fileName)) return null
                file = doc.document
                thumbnail = doc.thumbnail
                fileName = doc.fileName
                mimeType = doc.mimeType
                caption = content.caption?.text
                fallbackTitle = "Document"
            }
            else -> return null
        }

        val local = file.local
        val thumbFile = thumbnail?.file
        val thumbnailPath = thumbFile?.local?.path?.takeIf { it.isNotEmpty() }
        if (requestThumbnail && thumbnailPath == null && thumbFile != null && thumbFile.id != 0) {
            // Fire-and-forget: don't wait for a TDLib round trip per message
            client?.send(DownloadFile(thumbFile.id, 1, 0, 0, false), null)
        }

        return MediaMessage(
            id = id,
            date = date,
            videoFile = VideoFile(
                id = file.id,
                size = file.size,
                downloadedSize = local.downloadedSize,
                localPath = local.path?.takeIf { it.isNotEmpty() },
                isDownloading = local.isDownloadingActive,
                isDownloaded = local.isDownloadingCompleted
            ),
            filename = fileName,
            thumbnailFileId = thumbFile?.id,
            thumbnail = thumbnailPath,
            duration = duration,
            width = width,
            height = height,
            caption = caption,
            mimeType = mimeType ?: "",
            chatId = chatId,
            title = caption?.takeIf { it.isNotBlank() } ?: fileName ?: fallbackTitle
        )
    }

    private suspend fun searchChat(
        chatId: Long,
        filter: SearchMessagesFilter,
        query: String = "",
        limit: Int,
        fromMessageId: Long = 0L
    ): Array<Message>? {
        val response = sendAsync(SearchChatMessages().apply {
            this.chatId = chatId
            this.query = query
            this.limit = limit
            this.fromMessageId = fromMessageId
            this.filter = filter
        })
        return (response as? FoundChatMessages)?.messages
    }

    fun getVideoMessages(channelId: Long, limit: Int = 100, maxVideos: Int = 500): Flow<List<MediaMessage>> = flow {
        val allVideos = mutableListOf<MediaMessage>()
        try {
            // Native videos first, then documents (mkv and other video files sent as files)
            for (filter in listOf(SearchMessagesFilterVideo(), SearchMessagesFilterDocument())) {
                var fromMessageId = 0L
                while (allVideos.size < maxVideos) {
                    val messages = searchChat(channelId, filter, limit = limit, fromMessageId = fromMessageId)
                    if (messages.isNullOrEmpty()) break
                    fromMessageId = messages.last().id
                    messages.mapNotNullTo(allVideos) { it.toMediaMessage(requestThumbnail = true) }
                    // Emit intermediate results so the UI can show videos as they load
                    emit(allVideos.sortedByDescending { it.date })
                    if (messages.size < limit) break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Error getting video messages: ${e.message}")
        }
        emit(allVideos.sortedByDescending { it.date })
    }

    /**
     * Start downloading a file from a specific offset.
     * @param fileId The file ID
     * @param offset The offset to start downloading from
     * @param limit The number of bytes to download
     * @param priority Download priority (1 = highest, 32 = normal)
     */
    suspend fun startDownload(fileId: Int, offset: Long, limit: Long, priority: Int = 32): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(DownloadFile().apply {
                this.fileId = fileId
                this.priority = priority
                this.offset = offset
                this.limit = limit
                this.synchronous = false
            })
            response is TdApi.File
        } catch (e: Exception) {
            Log.e("Error starting download: ${e.message}")
            false
        }
    }
    
    /**
     * Download file synchronously, waiting for the data to be available.
     */
    suspend fun downloadFileSync(fileId: Int, offset: Long, limit: Long, priority: Int = 32): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(DownloadFile().apply {
                this.fileId = fileId
                this.priority = priority
                this.offset = offset
                this.limit = limit
                this.synchronous = true
            })
            response is TdApi.File
        } catch (e: Exception) {
            Log.e("Error downloading file sync: ${e.message}")
            false
        }
    }

    suspend fun getFileInfo(fileId: Int): TdApi.File? = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(GetFile().apply {
                this.fileId = fileId
            })
            if (response is TdApi.File) response else null
        } catch (e: Exception) {
            Log.e("Error getting file info: ${e.message}")
            null
        }
    }
    
    fun cancelDownload(fileId: Int) {
        try {
            client?.send(CancelDownloadFile(fileId, false), null)
        } catch (e: Exception) {
            Log.e("Error canceling download: ${e.message}")
        }
    }
    
    /**
     * Search video messages in a specific chat, or across the first channels when [chatId] is null.
     */
    fun searchMessages(query: String, chatId: Long? = null, limit: Int = 50): Flow<List<MediaMessage>> = flow {
        if (query.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val results = try {
            if (chatId != null) {
                listOf(SearchMessagesFilterVideo(), SearchMessagesFilterDocument()).flatMap { filter ->
                    searchChat(chatId, filter, query, limit).orEmpty().mapNotNull { it.toMediaMessage() }
                }
            } else {
                // Global search across the first channels, 5 in parallel, stopping once we have enough
                val found = mutableListOf<MediaMessage>()
                for (batch in _channels.value.take(20).chunked(5)) {
                    found += coroutineScope {
                        batch.map { channel ->
                            async {
                                try {
                                    searchChat(channel.id, SearchMessagesFilterVideo(), query, 10)
                                        .orEmpty().mapNotNull { it.toMediaMessage() }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    emptyList() // skip failed channels
                                }
                            }
                        }.awaitAll().flatten()
                    }
                    if (found.size >= limit) break
                }
                found.take(limit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Error searching messages: ${e.message}")
            emptyList()
        }
        emit(results.sortedByDescending { it.date })
    }

    suspend fun getMessage(chatId: Long, messageId: Long): MediaMessage? = withContext(Dispatchers.IO) {
        try {
            val response = sendAsync(GetMessage().apply {
                this.chatId = chatId
                this.messageId = messageId
            })
            (response as? Message)?.toMediaMessage(anyDocument = true)
        } catch (e: Exception) {
            Log.e("Error getting message: ${e.message}")
            null
        }
    }

    
    /** Bytes available locally starting at [offset] (TDLib tracks arbitrary downloaded ranges). */
    suspend fun getDownloadedPrefixSize(fileId: Int, offset: Long): Long {
        val response = sendAsync(GetFileDownloadedPrefixSize(fileId, offset))
        return (response as? FileDownloadedPrefixSize)?.size ?: 0L
    }

    // Media file types managed by the video cache (thumbnails and profile photos are kept)
    private val cacheFileTypes: Array<FileType>
        get() = arrayOf(FileTypeVideo(), FileTypeDocument(), FileTypeAnimation(), FileTypeVideoNote(), FileTypeVideoStory(), FileTypeUnknown())

    /** Total size of files TDLib keeps on disk, or null if TDLib isn't ready. */
    suspend fun getFilesSize(): Long? = (sendAsync(GetStorageStatisticsFast()) as? StorageStatisticsFast)?.filesSize

    /**
     * Let TDLib delete cached media (least recently used first) until the cache fits [maxBytes].
     * Going through TDLib keeps its file database consistent, unlike deleting files by hand.
     * Pass 0 to delete all cached media.
     */
    suspend fun optimizeStorage(maxBytes: Long): Boolean {
        val request = if (maxBytes <= 0) {
            OptimizeStorage(0, 0, 0, 0, cacheFileTypes, longArrayOf(), longArrayOf(), false, 0)
        } else {
            // ttl/count = -1: no age/count limits; immunityDelay -1 = TDLib default
            OptimizeStorage(maxBytes, -1, -1, -1, cacheFileTypes, longArrayOf(), longArrayOf(), false, 0)
        }
        val response = sendAsync(request)
        if (response is Error) Log.w("TDLib optimizeStorage failed: ${response.message}")
        return response is StorageStatistics
    }

    override fun close() {
        // Let TDLib flush its databases before the process exits
        client?.send(Close(), null)
        scope.cancel()
    }
}
