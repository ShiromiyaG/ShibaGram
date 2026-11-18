package com.shirou.shibagram.util

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Minimal logger. Debug output is off unless the app runs with -Dshibagram.debug=true
 * or the SHIBAGRAM_DEBUG environment variable is set, so release builds stay quiet.
 */
object Log {
    @Volatile
    var debugEnabled: Boolean = System.getProperty("shibagram.debug")?.toBoolean()
        ?: (System.getenv("SHIBAGRAM_DEBUG") != null)

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private fun emit(level: Char, msg: String, t: Throwable? = null) {
        val line = "${LocalTime.now().format(time)} $level/${Thread.currentThread().name}: $msg"
        if (level == 'E' || level == 'W') System.err.println(line) else println(line)
        t?.printStackTrace()
    }

    fun d(msg: String) { if (debugEnabled) emit('D', msg) }
    fun i(msg: String) = emit('I', msg)
    fun w(msg: String, t: Throwable? = null) = emit('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = emit('E', msg, t)
}
