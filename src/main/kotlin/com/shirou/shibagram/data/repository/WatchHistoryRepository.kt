package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.local.PlaybackProgressTable
import com.shirou.shibagram.data.local.SavedVideosTable
import com.shirou.shibagram.data.local.ShibaGramDatabase
import com.shirou.shibagram.data.local.WatchedChannelsTable
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.domain.model.VideoFile
import com.shirou.shibagram.domain.model.VideoProgress
import com.shirou.shibagram.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Watch progress ("Continue watching"), saved videos and recently watched channels.
 * The database is opened lazily on the first call, always on Dispatchers.IO.
 */
class WatchHistoryRepository {

    companion object {
        /** Progress above this fraction counts as watched. */
        const val COMPLETED_THRESHOLD = 0.95f
        private const val MIN_CONTINUE_PROGRESS = 0.05f
    }

    private val _continueWatchingVideos = MutableStateFlow<List<Pair<MediaMessage, Float>>>(emptyList())
    val continueWatchingVideos = _continueWatchingVideos.asStateFlow()

    private val _savedVideos = MutableStateFlow<List<MediaMessage>>(emptyList())
    val savedVideos = _savedVideos.asStateFlow()

    // Bumped on every progress write so screens showing per-video progress can reload it
    private val _progressVersion = MutableStateFlow(0)
    val progressVersion = _progressVersion.asStateFlow()

    private val _watchedChannelIds = MutableStateFlow<List<Long>>(emptyList())
    val watchedChannelIds = _watchedChannelIds.asStateFlow()

    /** Runs [block] in a DB transaction on the IO dispatcher; logs and returns null on failure. */
    private suspend fun <T> db(what: String, block: Transaction.() -> T): T? = withContext(Dispatchers.IO) {
        try {
            transaction(ShibaGramDatabase.getDatabase()) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Error $what: ${e.message}")
            null
        }
    }

    private fun progressKey(chatId: Long, messageId: Long) =
        (PlaybackProgressTable.chatId eq chatId) and (PlaybackProgressTable.messageId eq messageId)

    private fun savedKey(chatId: Long, messageId: Long) =
        (SavedVideosTable.chatId eq chatId) and (SavedVideosTable.messageId eq messageId)

    /** Placeholder file info; the real file is fetched with GetMessage when the video is played. */
    private fun unresolvedFile(size: Long = 0L) =
        VideoFile(id = 0, size = size, downloadedSize = 0L, localPath = null, isDownloading = false, isDownloaded = false)

    suspend fun loadContinueWatching() {
        val videos = db("loading continue watching") {
            PlaybackProgressTable.select { PlaybackProgressTable.isCompleted eq false }
                .orderBy(PlaybackProgressTable.lastUpdated, SortOrder.DESC)
                .limit(20)
                .mapNotNull { row ->
                    val position = row[PlaybackProgressTable.position]
                    val duration = row[PlaybackProgressTable.duration]
                    val progress = if (duration > 0) position.toFloat() / duration else 0f
                    if (progress !in MIN_CONTINUE_PROGRESS..COMPLETED_THRESHOLD) return@mapNotNull null
                    MediaMessage(
                        id = row[PlaybackProgressTable.messageId],
                        chatId = row[PlaybackProgressTable.chatId],
                        date = 0,
                        videoFile = unresolvedFile(),
                        duration = (duration / 1000).toInt(),
                        thumbnail = row[PlaybackProgressTable.thumbnailPath],
                        title = row[PlaybackProgressTable.title] ?: ""
                    ) to progress
                }
        } ?: return
        _continueWatchingVideos.value = videos
    }

    suspend fun loadSavedVideos() {
        val videos = db("loading saved videos") {
            SavedVideosTable.selectAll()
                .orderBy(SavedVideosTable.savedAt, SortOrder.DESC)
                .map { row ->
                    MediaMessage(
                        id = row[SavedVideosTable.messageId],
                        chatId = row[SavedVideosTable.chatId],
                        date = 0,
                        videoFile = unresolvedFile(row[SavedVideosTable.fileSize]),
                        filename = row[SavedVideosTable.filename],
                        thumbnail = row[SavedVideosTable.thumbnailPath],
                        duration = row[SavedVideosTable.duration],
                        title = row[SavedVideosTable.title]
                    )
                }
        } ?: return
        _savedVideos.value = videos
    }

    suspend fun saveProgress(
        messageId: Long,
        chatId: Long,
        position: Long,
        duration: Long,
        title: String? = null,
        thumbnailPath: String? = null
    ) {
        val isCompleted = duration > 0 && position.toFloat() / duration > COMPLETED_THRESHOLD
        db("saving progress") {
            val updated = PlaybackProgressTable.update({ progressKey(chatId, messageId) }) {
                it[PlaybackProgressTable.position] = position
                it[PlaybackProgressTable.duration] = duration
                it[lastUpdated] = System.currentTimeMillis()
                it[PlaybackProgressTable.isCompleted] = isCompleted
                title?.let { t -> it[PlaybackProgressTable.title] = t }
                thumbnailPath?.let { tp -> it[PlaybackProgressTable.thumbnailPath] = tp }
            }
            if (updated == 0) {
                PlaybackProgressTable.insert {
                    it[PlaybackProgressTable.messageId] = messageId
                    it[PlaybackProgressTable.chatId] = chatId
                    it[PlaybackProgressTable.position] = position
                    it[PlaybackProgressTable.duration] = duration
                    it[lastUpdated] = System.currentTimeMillis()
                    it[PlaybackProgressTable.isCompleted] = isCompleted
                    it[PlaybackProgressTable.title] = title
                    it[PlaybackProgressTable.thumbnailPath] = thumbnailPath
                }
            }
        }
        _progressVersion.value++
        loadContinueWatching()
    }

    /** Progress of every video of a chat that has any, keyed by message id. */
    suspend fun getProgressForChat(chatId: Long): Map<Long, VideoProgress> = db("reading chat progress") {
        PlaybackProgressTable.select { PlaybackProgressTable.chatId eq chatId }
            .associate { row ->
                row[PlaybackProgressTable.messageId] to VideoProgress(
                    position = row[PlaybackProgressTable.position],
                    duration = row[PlaybackProgressTable.duration],
                    isCompleted = row[PlaybackProgressTable.isCompleted]
                )
            }
    } ?: emptyMap()

    /** Mark a video as fully watched, or clear its progress (unwatched). */
    suspend fun setWatched(video: MediaMessage, watched: Boolean) {
        if (watched) {
            val duration = (video.duration * 1000L).coerceAtLeast(1L)
            saveProgress(video.id, video.chatId, duration, duration, video.title.ifBlank { null }, video.thumbnail)
        } else {
            removeFromContinueWatching(video.chatId, video.id)
        }
    }

    /** Saved playback position (ms) for a video, if any. */
    suspend fun getProgress(chatId: Long, messageId: Long): Long? = db("reading progress") {
        PlaybackProgressTable.select { progressKey(chatId, messageId) }
            .firstOrNull()
            ?.get(PlaybackProgressTable.position)
    }

    suspend fun saveVideo(video: MediaMessage) {
        db("saving video") {
            SavedVideosTable.insertIgnore {
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
        loadSavedVideos()
    }

    suspend fun unsaveVideo(chatId: Long, messageId: Long) {
        db("removing saved video") { SavedVideosTable.deleteWhere { savedKey(chatId, messageId) } }
        loadSavedVideos()
    }

    suspend fun removeFromContinueWatching(chatId: Long, messageId: Long) {
        db("removing from continue watching") { PlaybackProgressTable.deleteWhere { progressKey(chatId, messageId) } }
        _progressVersion.value++
        loadContinueWatching()
    }

    suspend fun updateWatchedChannel(chatId: Long) {
        db("updating watched channel") {
            WatchedChannelsTable.upsert(WatchedChannelsTable.chatId) {
                it[WatchedChannelsTable.chatId] = chatId
                it[WatchedChannelsTable.lastWatched] = System.currentTimeMillis()
            }
        }
        loadWatchedChannels()
    }

    suspend fun loadWatchedChannels() {
        val ids = db("loading watched channels") {
            WatchedChannelsTable.selectAll()
                .orderBy(WatchedChannelsTable.lastWatched, SortOrder.DESC)
                .limit(5)
                .map { it[WatchedChannelsTable.chatId] }
        } ?: return
        _watchedChannelIds.value = ids
    }
}
