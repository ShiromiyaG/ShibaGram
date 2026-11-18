package com.shirou.shibagram.domain.model

/**
 * Represents a Telegram channel/chat.
 * Ported from Android ShibaGram app.
 */
data class Channel(
    val id: Long,
    val name: String,
    val photoPath: String? = null,
    /** TDLib file id of the small photo, used to download it when [photoPath] isn't local yet. */
    val photoFileId: Int? = null,
    val description: String? = null,
    val isAvailableOnTelegram: Boolean = true,
    val chatType: ChatType = ChatType.SUPERGROUP
)

/**
 * Represents the type of chat.
 */
enum class ChatType {
    PRIVATE,
    BASIC_GROUP,
    SUPERGROUP,
    CHANNEL,
    SECRET
}

/**
 * Represents a Telegram chat folder.
 */
data class ChatFolder(
    val id: Int,
    val title: String,
    val icon: String? = null
)
