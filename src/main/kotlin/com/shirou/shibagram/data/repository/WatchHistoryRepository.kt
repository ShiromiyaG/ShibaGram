package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.local.PlaybackProgressTable
import com.shirou.shibagram.data.local.SavedVideosTable
import com.shirou.shibagram.data.local.ShibaGramDatabase
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.VideoFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository for managing watch history, playback progress, and saved videos.
 */
class WatchHistoryRepository {
    
    private val _continueWatchingVideos = MutableStateFlow<List<Pair<MediaMessage, Float>>>(emptyList())
    val continueWatchingVideos = _continueWatchingVideos.asStateFlow()
    
    private val _savedVideos = MutableStateFlow<List<MediaMessage>>(emptyList())
    val savedVideos = _savedVideos.asStateFlow()
    
    init {
        try {
            ShibaGramDatabase.initialize()
        } catch (e: Exception) {
            println("Database already initialized or error: ${e.message}")
        }
    }
    
    /**
     * Load all continue watching videos from database.
     */
    suspend fun loadContinueWatching() = withContext(Dispatchers.IO) {
        try {
            val videos = mutableListOf<Pair<MediaMessage, Float>>()
            
            transaction(ShibaGramDatabase.getDatabase()) {
                PlaybackProgressTable.select { PlaybackProgressTable.isCompleted eq false }
                    .orderBy(PlaybackProgressTable.lastUpdated, SortOrder.DESC)
                    .limit(20)
                    .forEach { row ->
                        val position = row[PlaybackProgressTable.position]
                        val duration = row[PlaybackProgressTable.duration]
                        val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
                        
                        // Only show if progress is between 5% and 95%
                        if (progress in 0.05f..0.95f) {
                            val video = MediaMessage(
                                id = row[PlaybackProgressTable.messageId],
                                chatId = row[PlaybackProgressTable.chatId],
                                date = 0,
                                videoFile = VideoFile(
                                    id = 0,
                                    size = 0L,
                                    downloadedSize = 0L,
                                    localPath = null,
                                    isDownloading = false,
                                    isDownloaded = false
                                ),
                                duration = (duration / 1000).toInt()
                            )
                            videos.add(video to progress)
                        }
                    }
            }
            
            _continueWatchingVideos.value = videos
        } catch (e: Exception) {
            println("Error loading continue watching: ${e.message}")
        }
    }
    
    /**
     * Load all saved videos from database.
     */
    suspend fun loadSavedVideos() = withContext(Dispatchers.IO) {
        try {
            val videos = mutableListOf<MediaMessage>()
            
            transaction(ShibaGramDatabase.getDatabase()) {
                SavedVideosTable.selectAll()
                    .orderBy(SavedVideosTable.savedAt, SortOrder.DESC)
                    .forEach { row ->
                        videos.add(MediaMessage(
                            id = row[SavedVideosTable.messageId],
                            chatId = row[SavedVideosTable.chatId],
                            date = 0,
                            videoFile = VideoFile(
                                id = 0,
                                size = row[SavedVideosTable.fileSize],
                                downloadedSize = 0L,
                                localPath = null,
                                isDownloading = false,
                                isDownloaded = false
                            ),
                            filename = row[SavedVideosTable.filename],
                            thumbnail = row[SavedVideosTable.thumbnailPath],
                            duration = row[SavedVideosTable.duration],
                            title = row[SavedVideosTable.title]
                        ))
                    }
            }
            
            _savedVideos.value = videos
        } catch (e: Exception) {
            println("Error loading saved videos: ${e.message}")
        }
    }
    
    /**
     * Save playback progress for a video.
     */
    suspend fun saveProgress(
        messageId: Long,
        chatId: Long,
        position: Long,
        duration: Long
    ) = withContext(Dispatchers.IO) {
        try {
            val isCompleted = duration > 0 && position.toFloat() / duration.toFloat() > 0.95f
            
            transaction(ShibaGramDatabase.getDatabase()) {
                val existing = PlaybackProgressTable.select { PlaybackProgressTable.messageId eq messageId }
                    .firstOrNull()
                
                if (existing != null) {
                    PlaybackProgressTable.update({ PlaybackProgressTable.messageId eq messageId }) {
                        it[PlaybackProgressTable.position] = position
                        it[PlaybackProgressTable.duration] = duration
                        it[lastUpdated] = System.currentTimeMillis()
                        it[PlaybackProgressTable.isCompleted] = isCompleted
                    }
                } else {
                    PlaybackProgressTable.insert {
                        it[PlaybackProgressTable.messageId] = messageId
                        it[PlaybackProgressTable.chatId] = chatId
                        it[PlaybackProgressTable.position] = position
                        it[PlaybackProgressTable.duration] = duration
                        it[lastUpdated] = System.currentTimeMillis()
                        it[PlaybackProgressTable.isCompleted] = isCompleted
                    }
                }
            }
            
            // Reload continue watching list
            loadContinueWatching()
        } catch (e: Exception) {
            println("Error saving progress: ${e.message}")
        }
    }
    
    /**
     * Get saved playback position for a video.
     */
    suspend fun getProgress(messageId: Long): Long? = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) {
                PlaybackProgressTable.select { PlaybackProgressTable.messageId eq messageId }
                    .firstOrNull()
                    ?.get(PlaybackProgressTable.position)
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Save a video to favorites.
     */
    suspend fun saveVideo(video: MediaMessage) = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) {
                val existing = SavedVideosTable.select { SavedVideosTable.messageId eq video.id }
                    .firstOrNull()
                
                if (existing == null) {
                    SavedVideosTable.insert {
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
            
            loadSavedVideos()
        } catch (e: Exception) {
            println("Error saving video: ${e.message}")
        }
    }
    
    /**
     * Remove a video from favorites.
     */
    suspend fun unsaveVideo(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) {
                SavedVideosTable.deleteWhere { SavedVideosTable.messageId eq messageId }
            }
            
            loadSavedVideos()
        } catch (e: Exception) {
            println("Error removing saved video: ${e.message}")
        }
    }
    
    /**
     * Check if a video is saved.
     */
    suspend fun isVideoSaved(messageId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) {
                SavedVideosTable.select { SavedVideosTable.messageId eq messageId }
                    .count() > 0
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Remove video from continue watching list.
     */
    suspend fun removeFromContinueWatching(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) {
                PlaybackProgressTable.deleteWhere { PlaybackProgressTable.messageId eq messageId }
            }
            
            loadContinueWatching()
        } catch (e: Exception) {
            println("Error removing from continue watching: ${e.message}")
        }
    }
    
    /**
     * Clear all watch history.
     */
    suspend fun clearWatchHistory() = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) {
                PlaybackProgressTable.deleteAll()
            }
            _continueWatchingVideos.value = emptyList()
        } catch (e: Exception) {
            println("Error clearing watch history: ${e.message}")
        }
    }
}
