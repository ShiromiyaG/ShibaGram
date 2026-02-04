package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.dto.ContentType
import com.shirou.shibagram.data.dto.PaginationState
import com.shirou.shibagram.data.dto.PaginationStateKey
import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.MessagesPage
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for handling channel messages with pagination.
 * Ported from Android ShibaGram app.
 */
class ChannelMessagesRepository(
    private val telegramClient: TelegramClientService = TelegramClientService.getInstance()
) {
    private val paginationStates = ConcurrentHashMap<PaginationStateKey, PaginationState>()
    
    fun getVideoMessagesWithPagination(
        channelId: Long,
        topicId: Long? = null,
        query: String = "",
        limit: Int = 50
    ): Flow<MessagesPage<MediaMessage>> = flow {
        val key = PaginationStateKey(channelId, topicId, ContentType.VIDEO, query)
        val currentState = paginationStates.getOrPut(key) { PaginationState() }
        
        if (!currentState.hasMore || currentState.isLoading) {
            return@flow
        }
        
        paginationStates[key] = currentState.copy(isLoading = true)
        
        try {
            telegramClient.getVideoMessages(channelId, limit)
                .collect { messages ->
                    val hasMore = messages.size >= limit
                    val lastMessageId = messages.lastOrNull()?.id ?: 0L
                    
                    paginationStates[key] = PaginationState(
                        lastMessageId = lastMessageId,
                        hasMore = hasMore,
                        isLoading = false
                    )
                    
                    emit(MessagesPage(
                        messages = messages,
                        hasMore = hasMore,
                        lastMessageId = lastMessageId
                    ))
                }
        } catch (e: Exception) {
            paginationStates[key] = currentState.copy(
                isLoading = false,
                error = e.message
            )
            emit(MessagesPage(emptyList()))
        }
    }
    
    fun clearPagination(channelId: Long, topicId: Long? = null) {
        paginationStates.keys
            .filter { it.channelId == channelId && it.topicId == topicId }
            .forEach { paginationStates.remove(it) }
    }
    
    fun clearAllPagination() {
        paginationStates.clear()
    }
}
