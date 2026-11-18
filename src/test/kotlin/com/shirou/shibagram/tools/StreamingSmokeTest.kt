package com.shirou.shibagram.tools

import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.domain.model.AuthState
import com.shirou.shibagram.domain.model.MediaMessage
import com.shirou.shibagram.streaming.TelegramVideoDataProvider
import com.shirou.shibagram.streaming.VideoStreamingServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess

/**
 * Manual end-to-end check of streaming (not a unit test): uses the real Telegram session,
 * picks a large MKV that isn't downloaded yet, serves it through the local server and plays it
 * headless in mpv from the start, after a seek to 50% and to 85%, printing the time to start.
 *
 * Run: gradlew streamingSmokeTest -Pmpv="C:\path\to\mpv.com" [-PchannelFilter=name]
 * (close the app first: TDLib allows one instance per session)
 */
fun main(args: Array<String>) {
    val mpv = args.getOrNull(0) ?: error("usage: <mpv.com> [channel name filter]")
    val filter = args.getOrNull(1)?.takeIf { it.isNotBlank() }
    val svc = TelegramClientService.getInstance()
    runBlocking {
        svc.initialize()
        withTimeout(60_000) { svc.authState.first { it is AuthState.Authenticated } }
        delay(5_000) // let channels load
        val channels = svc.channels.value.filter { filter == null || it.name.contains(filter, ignoreCase = true) }
        println("Channels to scan: ${channels.size}")

        var target: MediaMessage? = null
        var second: MediaMessage? = null
        for (channel in channels) {
            val fresh = svc.getRecentVideos(channel.id, 20)
                .filter { it.isMatroska && !it.videoFile.isDownloaded && it.videoFile.downloadedSize == 0L && it.videoFile.size > 300L * 1024 * 1024 }
                .sortedByDescending { it.videoFile.size }
            if (fresh.size >= 2) {
                target = fresh[0]
                second = fresh[1]
                println("Using: ${fresh[0].displayTitle} (${fresh[0].formattedSize}) and ${fresh[1].displayTitle} from ${channel.name}")
                break
            }
        }
        val video = target ?: run { println("No undownloaded MKV found"); exitProcess(2) }

        val server = VideoStreamingServer.getRunningInstance()
        server.resolver = VideoStreamingServer.VideoResolver { chatId, messageId ->
            val m = svc.getMessage(chatId, messageId) ?: return@VideoResolver null
            VideoStreamingServer.StreamingFile(
                m.videoFile.id, m.videoFile.size, m.streamMimeType,
                TelegramVideoDataProvider(svc, m.videoFile.id, m.videoFile.size)
            )
        }
        val url = server.urlFor(video.chatId, video.id, video.filename)

        for ((label, start) in listOf("start" to "0", "seek 50%" to "50%", "seek 85%" to "85%")) {
            val began = System.currentTimeMillis()
            val process = ProcessBuilder(
                mpv, "--no-config", "--vo=null", "--ao=null", "--start=$start", "--length=8",
                "--network-timeout=60", "--cache=yes", "--term-playing-msg=SHIBA_PLAYING",
                "--msg-level=all=error,cplayer=info", url
            ).redirectErrorStream(true).start()
            var playingAt = -1L
            val errors = mutableListOf<String>()
            process.inputStream.bufferedReader().forEachLine { line ->
                // First decoded frame reaches the (null) video output
                if (line.contains("VO:") && playingAt < 0) playingAt = System.currentTimeMillis() - began
                else if (line.isNotBlank() && !line.startsWith(" ") && !line.contains("Playing:")) errors += line
            }
            val exit = process.waitFor()
            val total = System.currentTimeMillis() - began
            println("[$label] exit=$exit started=${if (playingAt >= 0) "${playingAt} ms" else "NO"} total=${total} ms")
            errors.take(6).forEach { println("    $it") }
            server.releaseLazyStreams()
        }

        // External player session: 2-item playlist, headless; the 2nd item has "saved" progress at 60 s
        val playlist = listOf(video, second!!)
        val active = mutableListOf<String>()
        val lastProgress = mutableMapOf<String, Long>()
        val exited = kotlinx.coroutines.CompletableDeferred<Unit>()
        val session = com.shirou.shibagram.player.ExternalMpvSession(
            mpvPath = mpv,
            playlist = playlist,
            startIndex = 0,
            urlFor = { server.urlFor(it.chatId, it.id, it.filename) },
            savedPositionMs = { if (it.id == second.id) 60_000L else null },
            onProgress = { v, pos, _ -> lastProgress[v.displayTitle] = pos },
            onActiveVideoChanged = { v ->
                v?.let { active += it.displayTitle }
                server.releaseLazyStreams(keep = setOfNotNull(v?.let { "${it.chatId}/${it.id}" }))
            },
            onExit = { exited.complete(Unit) },
            extraArgs = listOf("--no-config", "--vo=null", "--ao=null", "--length=10", "--force-window=no")
        )
        val began = System.currentTimeMillis()
        session.start()
        withTimeout(180_000) { exited.await() }
        println("[external] ${System.currentTimeMillis() - began} ms, played: $active")
        lastProgress.forEach { (title, pos) -> println("    last saved position of $title: ${pos / 1000.0} s") }

        server.releaseLazyStreams()
        svc.close()
        delay(1_000)
    }
    exitProcess(0)
}
