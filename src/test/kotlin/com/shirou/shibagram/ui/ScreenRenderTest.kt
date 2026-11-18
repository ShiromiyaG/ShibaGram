package com.shirou.shibagram.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.Density
import com.shirou.shibagram.domain.model.*
import com.shirou.shibagram.ui.screens.ChannelsScreen
import com.shirou.shibagram.ui.theme.ShibaGramTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders screens off-screen with sample data into build/screenshots/ for visual review.
 * No TDLib or network: thumbnails are absent, so the placeholders are what you see.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenRenderTest {
    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun render(name: String, width: Int = 1280, height: Int = 800, content: @Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, Density(1f)) {
            ShibaGramTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        // A few frames so layout/animations settle
        var image = scene.render(0)
        for (i in 1..5) image = scene.render(i * 100_000_000L)
        val file = File(outDir, "$name.png")
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        assertTrue(file.length() > 0)
    }

    private val now = (System.currentTimeMillis() / 1000).toInt()

    private val videos = (1..14).map { ep ->
        MediaMessage(
            id = ep.toLong(),
            date = now - (14 - ep) * 86_400,
            videoFile = VideoFile(id = ep, size = 350_000_000L + ep * 10_000_000L, downloadedSize = 0, localPath = null,
                isDownloading = false, isDownloaded = ep == 3),
            filename = "Hunter.x.Hunter.2011.E%02d.1080p.mkv".format(ep),
            duration = 1420 + ep,
            width = 1920, height = 1080,
            mimeType = "video/x-matroska",
            chatId = 1L,
            title = "Hunter x Hunter - Episódio $ep"
        )
    }

    private val progress = mapOf(
        1L to VideoProgress(1421_000, 1421_000, true),
        2L to VideoProgress(1422_000, 1422_000, true),
        3L to VideoProgress(600_000, 1423_000, false)
    )

    private val channels = listOf(
        Channel(1, "Hunter X Hunter (2011) Definitivo"),
        Channel(2, "Filmes (MKV)"),
        Channel(3, "Séries Clássicas")
    )

    @Composable
    private fun channelsScreen(mode: ViewingMode, sort: VideoSortOrder = VideoSortOrder.NAME_ASC, filter: VideoStatusFilter = VideoStatusFilter.ALL) {
        val shown = sort.sort(videos.filter { filter.matches(it, progress[it.id]) })
        ChannelsScreen(
            channels = channels,
            selectedChannel = channels[0],
            videos = shown,
            onChannelSelect = {},
            onVideoClick = {},
            isLoading = false,
            viewingMode = mode,
            allVideoCount = videos.size,
            progress = progress,
            sortOrder = sort,
            statusFilter = filter,
            onSaveVideo = {},
            onSetWatched = { _, _ -> },
            onContinue = {},
            onPlayAll = {}
        )
    }

    /** Scroll a bit (both buttons appear), then click "last video" (only "first video" remains). */
    @Test
    fun jumpButtons() {
        val scene = ImageComposeScene(1280, 800, Density(1f)) {
            ShibaGramTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    channelsScreen(ViewingMode.COMPACT)
                }
            }
        }
        var t = 0L
        fun frame() { t += 100_000_000L; scene.render(t) }
        fun save(name: String) {
            val image = scene.render(t)
            File(outDir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
        repeat(3) { frame() }
        scene.sendPointerEvent(
            androidx.compose.ui.input.pointer.PointerEventType.Scroll,
            androidx.compose.ui.geometry.Offset(700f, 400f),
            scrollDelta = androidx.compose.ui.geometry.Offset(0f, 3f)
        )
        repeat(5) { frame() }
        save("jump-middle")
        // "Last video" is the lower of the two buttons: 20dp padding + 40dp button from the bottom-right corner
        val button = androidx.compose.ui.geometry.Offset(1280f - 20f - 20f, 800f - 20f - 20f)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, button)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, button)
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, button)
        repeat(8) { frame() }
        save("jump-end")
        scene.close()
    }

    @Test fun settings() = render("settings", height = 1500) {
        com.shirou.shibagram.ui.screens.SettingsScreen(
            isDarkTheme = true, onDarkThemeChange = {}, onLogoutClick = {}, onClearCacheClick = {},
            currentCacheSize = "1.7 GB",
            externalPlayerEnabled = true,
            externalMpvPath = "F:\\MPV My Player\\MPV-Shirou\\mpv.exe",
            audioLanguages = "jpn,ja", subtitleLanguages = "pt-BR,pt,por,en"
        )
    }

    @Test fun channelsGrid() = render("channels-grid") { channelsScreen(ViewingMode.GRID) }
    @Test fun channelsList() = render("channels-list") { channelsScreen(ViewingMode.LIST) }
    @Test fun channelsCompact() = render("channels-compact") { channelsScreen(ViewingMode.COMPACT) }
    @Test fun channelsFilteredUnwatched() = render("channels-unwatched") {
        channelsScreen(ViewingMode.GRID, VideoSortOrder.NEWEST, VideoStatusFilter.UNWATCHED)
    }
}
