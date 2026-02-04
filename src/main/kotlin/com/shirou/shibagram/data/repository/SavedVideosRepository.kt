package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.local.ShibaGramDatabase
import com.shirou.shibagram.data.local.SavedVideosTable
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.VideoFile
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository for managing saved/bookmarked videos.
 * Ported from Android ShibaGram app.
 */
class SavedVideosRepository {
    
    fun saveVideo(video: MediaMessage) {
        transaction(ShibaGramDatabase.getDatabase()) {
            SavedVideosTable.upsert(SavedVideosTable.messageId) {
                it[messageId] = video.id
                it[chatId] = video.chatId
                it[title] = video.title
                it[filename] = video.filename
                it[thumbnailPath] = video.thumbnail
                it[duration] = video.duration
                it[fileSize] = video.videoFile.size
                it[qualityLabel] = video.qualityLabel
                it[savedAt] = System.currentTimeMillis()
            }
        }
    }
    
    fun unsaveVideo(messageId: Long) {
        transaction(ShibaGramDatabase.getDatabase()) {
            SavedVideosTable.deleteWhere { SavedVideosTable.messageId eq messageId }
        }
    }
    
    fun isVideoSaved(messageId: Long): Boolean {
        return transaction(ShibaGramDatabase.getDatabase()) {
            SavedVideosTable.select { SavedVideosTable.messageId eq messageId }
                .count() > 0
        }
    }
    
    fun getSavedVideos(limit: Int = 100): List<MediaMessage> {
        return transaction(ShibaGramDatabase.getDatabase()) {
            SavedVideosTable.selectAll()
                .orderBy(SavedVideosTable.savedAt, SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    MediaMessage(
                        id = row[SavedVideosTable.messageId],
                        date = 0,
                        videoFile = VideoFile(
                            id = 0,
                            size = row[SavedVideosTable.fileSize],
                            downloadedSize = 0,
                            localPath = null,
                            isDownloading = false,
                            isDownloaded = false
                        ),
                        filename = row[SavedVideosTable.filename],
                        thumbnailFileId = null,
                        thumbnail = row[SavedVideosTable.thumbnailPath],
                        duration = row[SavedVideosTable.duration],
                        width = 0,
                        height = 0,
                        caption = null,
                        mimeType = "video/mp4",
                        chatId = row[SavedVideosTable.chatId],
                        title = row[SavedVideosTable.title]
                    )
                }
        }
    }
    
    fun clearAllSavedVideos() {
        transaction(ShibaGramDatabase.getDatabase()) {
            SavedVideosTable.deleteAll()
        }
    }
}
