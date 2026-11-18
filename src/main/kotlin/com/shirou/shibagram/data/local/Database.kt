package com.shirou.shibagram.data.local

import com.shirou.shibagram.util.Log
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

/**
 * Local database for playback progress, saved videos and recently watched channels.
 *
 * Telegram message ids are only unique inside a chat, so progress and saved videos are keyed by
 * (chat_id, message_id). Older databases keyed by message_id alone are migrated on first open.
 */
object ShibaGramDatabase {
    private val db: Database by lazy {
        val dbPath = File(System.getProperty("user.home"), ".ShibaGram/ShibaGram.db")
        dbPath.parentFile?.mkdirs()

        val database = Database.connect(
            url = "jdbc:sqlite:${dbPath.absolutePath}",
            driver = "org.sqlite.JDBC"
        )

        transaction(database) {
            migrateLegacyTables()
            SchemaUtils.create(PlaybackProgressTable, SavedVideosTable, WatchedChannelsTable)
        }

        database
    }

    fun getDatabase(): Database = db

    private fun Transaction.tableExists(name: String): Boolean =
        exec("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'") { it.next() } == true

    private fun Transaction.columns(table: String): Set<String> =
        exec("PRAGMA table_info($table)") { rs ->
            buildSet { while (rs.next()) add(rs.getString("name")) }
        } ?: emptySet()

    /** Copies data from the v1 tables (message_id primary key) into the v2 tables, then drops them. */
    private fun Transaction.migrateLegacyTables() {
        if (tableExists("playback_progress")) {
            Log.i("Migrating playback_progress to (chat_id, message_id) key")
            SchemaUtils.create(PlaybackProgressTable)
            val cols = columns("playback_progress")
            val title = if ("title" in cols) "title" else "NULL"
            val thumb = if ("thumbnail_path" in cols) "thumbnail_path" else "NULL"
            exec(
                "INSERT OR IGNORE INTO ${PlaybackProgressTable.tableName} " +
                    "(chat_id, message_id, position, duration, last_updated, is_completed, title, thumbnail_path) " +
                    "SELECT chat_id, message_id, position, duration, last_updated, is_completed, $title, $thumb FROM playback_progress"
            )
            exec("DROP TABLE playback_progress")
        }
        if (tableExists("saved_videos")) {
            Log.i("Migrating saved_videos to (chat_id, message_id) key")
            SchemaUtils.create(SavedVideosTable)
            exec(
                "INSERT OR IGNORE INTO ${SavedVideosTable.tableName} " +
                    "(chat_id, message_id, title, filename, thumbnail_path, duration, file_size, quality_label, saved_at) " +
                    "SELECT chat_id, message_id, title, filename, thumbnail_path, duration, file_size, quality_label, saved_at FROM saved_videos"
            )
            exec("DROP TABLE saved_videos")
        }
    }
}

/**
 * Video playback progress.
 */
object PlaybackProgressTable : Table("playback_progress_v2") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val position = long("position")
    val duration = long("duration")
    val lastUpdated = long("last_updated")
    val isCompleted = bool("is_completed").default(false)
    val title = varchar("title", 500).nullable()
    val thumbnailPath = varchar("thumbnail_path", 1000).nullable()

    override val primaryKey = PrimaryKey(chatId, messageId)
}

/**
 * Saved/bookmarked videos.
 */
object SavedVideosTable : Table("saved_videos_v2") {
    val chatId = long("chat_id")
    val messageId = long("message_id")
    val title = varchar("title", 500)
    val filename = varchar("filename", 500).nullable()
    val thumbnailPath = varchar("thumbnail_path", 1000).nullable()
    val duration = integer("duration")
    val fileSize = long("file_size")
    val qualityLabel = varchar("quality_label", 20).nullable()
    val savedAt = long("saved_at")

    override val primaryKey = PrimaryKey(chatId, messageId)
}

object WatchedChannelsTable : Table("watched_channels") {
    val chatId = long("chat_id")
    val lastWatched = long("last_watched")

    override val primaryKey = PrimaryKey(chatId)
}
