package com.shirou.shibagram.data.dto

/**
 * Pagination state for channel messages.
 * Ported from Android ShibaGram app.
 */
data class PaginationState(
    val lastMessageId: Long = 0L,
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * Key for pagination state cache.
 */
data class PaginationStateKey(
    val channelId: Long,
    val topicId: Long? = null,
    val contentType: ContentType = ContentType.VIDEO,
    val query: String = ""
)

/**
 * Content type for filtering.
 */
enum class ContentType {
    VIDEO,
    AUDIO,
    IMAGE,
    ALL
}
