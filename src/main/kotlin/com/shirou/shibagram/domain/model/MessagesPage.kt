package com.shirou.shibagram.domain.model

/**
 * Represents a page of messages from a channel.
 * Ported from Android ShibaGram app.
 */
data class MessagesPage<T>(
    val messages: List<T>,
    val hasMore: Boolean = false,
    val lastMessageId: Long = 0L
)
