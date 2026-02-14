package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.local.ShibaGramDatabase
import com.shirou.shibagram.data.local.PlaybackProgressTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository for managing video playback progress.
 * Ported from Android ShibaGram app.
 */
class VideoPlaybackRepository {
    
    data class PlaybackProgress(
        val messageId: Long,
        val chatId: Long,
        val position: Long,
        val duration: Long,
        val lastUpdated: Long,
        val isCompleted: Boolean
    )
    
fun saveProgress(
        messageId: Long,
        chatId: Long,
        position: Long,
        duration: Long,
        title: String? = null,
        thumbnailPath: String? = null
    ) {
        val isCompleted = duration > 0 && position.toFloat() / duration >= 0.9f
        
        transaction(ShibaGramDatabase.getDatabase()) {
            PlaybackProgressTable.upsert(PlaybackProgressTable.messageId) {
                it[PlaybackProgressTable.messageId] = messageId
                it[PlaybackProgressTable.chatId] = chatId
                it[PlaybackProgressTable.position] = position
                it[PlaybackProgressTable.duration] = duration
                it[lastUpdated] = System.currentTimeMillis()
                it[PlaybackProgressTable.isCompleted] = isCompleted
                title?.let { t -> it[PlaybackProgressTable.title] = t }
                thumbnailPath?.let { tp -> it[PlaybackProgressTable.thumbnailPath] = tp }
            }
        }
    }
    
    fun getProgress(messageId: Long): PlaybackProgress? {
        return transaction(ShibaGramDatabase.getDatabase()) {
            PlaybackProgressTable.select { PlaybackProgressTable.messageId eq messageId }
                .firstOrNull()
                ?.let {
                    PlaybackProgress(
                        messageId = it[PlaybackProgressTable.messageId],
                        chatId = it[PlaybackProgressTable.chatId],
                        position = it[PlaybackProgressTable.position],
                        duration = it[PlaybackProgressTable.duration],
                        lastUpdated = it[PlaybackProgressTable.lastUpdated],
                        isCompleted = it[PlaybackProgressTable.isCompleted]
                    )
                }
        }
    }
    
    fun getInProgressVideos(limit: Int = 50): List<PlaybackProgress> {
        return transaction(ShibaGramDatabase.getDatabase()) {
            PlaybackProgressTable.select {
                (PlaybackProgressTable.isCompleted eq false) and
                (PlaybackProgressTable.position greater 0L)
            }
                .orderBy(PlaybackProgressTable.lastUpdated, SortOrder.DESC)
                .limit(limit)
                .map {
                    PlaybackProgress(
                        messageId = it[PlaybackProgressTable.messageId],
                        chatId = it[PlaybackProgressTable.chatId],
                        position = it[PlaybackProgressTable.position],
                        duration = it[PlaybackProgressTable.duration],
                        lastUpdated = it[PlaybackProgressTable.lastUpdated],
                        isCompleted = it[PlaybackProgressTable.isCompleted]
                    )
                }
        }
    }
    
    fun clearProgress(messageId: Long) {
        transaction(ShibaGramDatabase.getDatabase()) {
            PlaybackProgressTable.deleteWhere { PlaybackProgressTable.messageId eq messageId }
        }
    }
    
    fun clearAllProgress() {
        transaction(ShibaGramDatabase.getDatabase()) {
            PlaybackProgressTable.deleteAll()
        }
    }
}
