package com.shirou.shibagram.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Union
import java.io.File

interface LibMpv : Library {
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_destroy(ctx: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_async(ctx: Pointer, reply_userdata: Long, args: Array<String?>): Int
    fun mpv_set_option(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_async(ctx: Pointer, reply_userdata: Long, name: String, format: Int, data: Pointer): Int
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Pointer): Int
    fun mpv_observe_property(ctx: Pointer, reply_userdata: Long, name: String, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, registered_reply_userdata: Long): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_free(data: Pointer?)
    fun mpv_error_string(error: Int): String
    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int

    companion object {
        val INSTANCE: LibMpv by lazy {
            // Priority:
            // 1. libs/libmpv-2.dll (relative to CWD)
            // 2. libmpv-2.dll (system path/resource)
            // 3. mpv-2 (fallback)
            
            val cwd = System.getProperty("user.dir")
            val libPath = File(cwd, "libs/libmpv-2.dll").absolutePath
            
            try {
                if (File(libPath).exists()) {
                    Native.load(libPath, LibMpv::class.java)
                } else {
                    // Try to look in root if not in libs
                    val rootLibPath = File(cwd, "libmpv-2.dll").absolutePath
                     if (File(rootLibPath).exists()) {
                        Native.load(rootLibPath, LibMpv::class.java)
                    } else {
                        Native.load("libmpv-2", LibMpv::class.java)
                    }
                }
            } catch (e: Throwable) {
                try {
                    Native.load("mpv-2", LibMpv::class.java)
                } catch (e2: Throwable) {
                    Native.load("mpv", LibMpv::class.java)
                }
            }
        }

        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_OSD_STRING = 2
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
        const val MPV_FORMAT_NODE = 6
        const val MPV_FORMAT_NODE_ARRAY = 7
        const val MPV_FORMAT_NODE_MAP = 8
        const val MPV_FORMAT_BYTE_ARRAY = 9

        const val MPV_EVENT_NONE = 0
        const val MPV_EVENT_SHUTDOWN = 1
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_GET_PROPERTY_REPLY = 3
        const val MPV_EVENT_SET_PROPERTY_REPLY = 4
        const val MPV_EVENT_COMMAND_REPLY = 5
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_IDLE = 11
        const val MPV_EVENT_TICK = 14
        const val MPV_EVENT_CLIENT_MESSAGE = 16
        const val MPV_EVENT_VIDEO_RECONFIG = 17
        const val MPV_EVENT_AUDIO_RECONFIG = 18
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_EVENT_PROPERTY_CHANGE = 22
        const val MPV_EVENT_QUEUE_OVERFLOW = 24
        const val MPV_EVENT_HOOK = 25
    }
}

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
open class MpvEvent(p: Pointer? = null) : Structure(p) {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null

    init {
        if (p != null) read()
    }
}

@Structure.FieldOrder("name", "format", "data")
open class MpvEventProperty(p: Pointer? = null) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null

    init {
        if (p != null) read()
    }
}

@Structure.FieldOrder("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries")
open class MpvEventEndFile(p: Pointer? = null) : Structure(p) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0

    init {
        if (p != null) read()
    }
}

@Structure.FieldOrder("u", "format")
open class MpvNode(p: Pointer? = null) : Structure(p) {
    class U : Union() {
        @JvmField var string: String? = null
        @JvmField var flag: Int = 0
        @JvmField var int64: Long = 0
        @JvmField var double_: Double = 0.0
        @JvmField var list: Pointer? = null
        @JvmField var ba: Pointer? = null
    }
    @JvmField var u: U = U()
    @JvmField var format: Int = 0

    init {
        if (p != null) {
            read()
            u.read()
        }
    }
    
    // Explicitly read union based on format if needed, but JNA usually handles simple Unions.
    // Since this is a Union of different sizes, we trust JNA to handle memory layout.
    
    fun getString(): String? = if (format == LibMpv.MPV_FORMAT_STRING) u.string else null
    fun getBoolean(): Boolean = if (format == LibMpv.MPV_FORMAT_FLAG) u.flag != 0 else false
    fun getLong(): Long = if (format == LibMpv.MPV_FORMAT_INT64) u.int64 else 0L
    fun getDouble(): Double = if (format == LibMpv.MPV_FORMAT_DOUBLE) u.double_ else 0.0
    fun getList(): MpvNodeList? = if (format == LibMpv.MPV_FORMAT_NODE_ARRAY || format == LibMpv.MPV_FORMAT_NODE_MAP) {
         u.list?.let { MpvNodeList(it) }
    } else null
}

@Structure.FieldOrder("num", "values", "keys")
open class MpvNodeList(p: Pointer? = null) : Structure(p) {
    @JvmField var num: Int = 0
    @JvmField var values: Pointer? = null
    @JvmField var keys: Pointer? = null

    init {
        if (p != null) read()
    }
    
    fun getNodeAt(index: Int): MpvNode? {
        if (index < 0 || index >= num || values == null) return null
        val nodeSize = MpvNode().size()
        val p = values!!.share(index * nodeSize.toLong())
        return MpvNode(p)
    }
    
    fun getKeyAt(index: Int): String? {
        if (index < 0 || index >= num || keys == null) return null
        val p = keys!!.getPointer(index * Native.POINTER_SIZE.toLong())
        return p?.getString(0)
    }
}

@Structure.FieldOrder("prefix", "level", "text")
open class MpvEventLogMessage(p: Pointer? = null) : Structure(p) {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null

    init {
        if (p != null) read()
    }
}
