package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.MessagesPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Repository for loading the video messages of a channel.
 */
class ChannelMessagesRepository(
    private val telegramClient: TelegramClientService = TelegramClientService.getInstance()
) {
    /**
     * Emits progressively larger pages while the channel history is scanned, then completes.
     */
    fun getVideoMessages(channelId: Long, limit: Int = 50): Flow<MessagesPage<MediaMessage>> =
        telegramClient.getVideoMessages(channelId, limit)
            .map { messages ->
                MessagesPage(
                    messages = messages,
                    hasMore = messages.size >= limit,
                    lastMessageId = messages.lastOrNull()?.id ?: 0L
                )
            }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(MessagesPage(emptyList()))
            }
}
