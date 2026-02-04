package com.shirou.shibagram.data.local

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

/**
 * Local database for storing playback progress and saved videos.
 * Ported from Android ShibaGram's Room database.
 */
object ShibaGramDatabase {
    private var database: Database? = null
    
    fun initialize() {
        val dbPath = File(System.getProperty("user.home"), ".ShibaGram/ShibaGram.db")
        dbPath.parentFile?.mkdirs()
        
        database = Database.connect(
            url = "jdbc:sqlite:${dbPath.absolutePath}",
            driver = "org.sqlite.JDBC"
        )
        
        transaction(database) {
            SchemaUtils.create(PlaybackProgressTable, SavedVideosTable, ChannelCacheTable)
        }
    }
    
    fun getDatabase(): Database {
        return database ?: throw IllegalStateException("Database not initialized")
    }
}

/**
 * Table for storing video playback progress.
 */
object PlaybackProgressTable : Table("playback_progress") {
    val messageId = long("message_id").uniqueIndex()
    val chatId = long("chat_id")
    val position = long("position")
    val duration = long("duration")
    val lastUpdated = long("last_updated")
    val isCompleted = bool("is_completed").default(false)
    
    override val primaryKey = PrimaryKey(messageId)
}

/**
 * Table for storing saved/bookmarked videos.
 */
object SavedVideosTable : Table("saved_videos") {
    val messageId = long("message_id").uniqueIndex()
    val chatId = long("chat_id")
    val title = varchar("title", 500)
    val filename = varchar("filename", 500).nullable()
    val thumbnailPath = varchar("thumbnail_path", 1000).nullable()
    val duration = integer("duration")
    val fileSize = long("file_size")
    val qualityLabel = varchar("quality_label", 20).nullable()
    val savedAt = long("saved_at")
    
    override val primaryKey = PrimaryKey(messageId)
}

/**
 * Table for caching channel information.
 */
object ChannelCacheTable : Table("channel_cache") {
    val channelId = long("channel_id").uniqueIndex()
    val name = varchar("name", 500)
    val photoPath = varchar("photo_path", 1000).nullable()
    val description = text("description").nullable()
    val lastUpdated = long("last_updated")
    
    override val primaryKey = PrimaryKey(channelId)
}
