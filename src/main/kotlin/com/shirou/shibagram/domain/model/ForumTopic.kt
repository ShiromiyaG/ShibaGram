package com.shirou.shibagram.domain.model

/**
 * Represents a forum topic in a supergroup.
 * Ported from Android ShibaGram app.
 */
data class ForumTopic(
    val id: Long,
    val name: String,
    val icon: ForumTopicIcon? = null,
    val creationDate: Int = 0,
    val messageCount: Int = 0,
    val unreadCount: Int = 0,
    val lastMessageId: Long = 0L,
    val isPinned: Boolean = false,
    val isClosed: Boolean = false,
    val isHidden: Boolean = false
)

/**
 * Represents a forum topic icon.
 */
data class ForumTopicIcon(
    val color: Int = 0,
    val customEmojiId: Long = 0L
)
