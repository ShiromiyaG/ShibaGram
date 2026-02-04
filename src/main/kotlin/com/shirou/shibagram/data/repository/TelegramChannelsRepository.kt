package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.domain.model.Channel
import com.shirou.shibagram.domain.model.ChatFolder
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for handling Telegram channels.
 * Ported from Android ShibaGram app.
 */
class TelegramChannelsRepository(
    private val telegramClient: TelegramClientService = TelegramClientService.getInstance()
) {
    val channels: StateFlow<List<Channel>> = telegramClient.channels
    
    @Suppress("UNCHECKED_CAST")
    val chatFolders: StateFlow<List<ChatFolder>> = telegramClient.chatFolders as StateFlow<List<ChatFolder>>
    
    val selectedFolderId: StateFlow<Int?> = telegramClient.selectedFolderId
    
    suspend fun refreshChannels() {
        telegramClient.refreshChannels()
    }
    
    fun selectFolder(folderId: Int?) {
        telegramClient.selectFolder(folderId)
    }
}
