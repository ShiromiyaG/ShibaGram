package com.shirou.shibagram.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoSortOrderTest {
    private fun video(id: Long, title: String, date: Int = 0, duration: Int = 0, size: Long = 0, downloaded: Boolean = false) =
        MediaMessage(
            id = id, date = date, title = title, duration = duration,
            videoFile = VideoFile(id = id.toInt(), size = size, downloadedSize = 0, localPath = null, isDownloading = false, isDownloaded = downloaded)
        )

    @Test
    fun naturalOrderSortsEpisodeNumbersNumerically() {
        val names = listOf("Ep 10", "ep 2", "Ep 1", "Ep 02 extra", "Ep 100", "Bonus")
        assertEquals(listOf("Bonus", "Ep 1", "ep 2", "Ep 02 extra", "Ep 10", "Ep 100"), names.sortedWith(NaturalOrder))
    }

    @Test
    fun sortOrders() {
        val a = video(1, "B 2", date = 100, duration = 30, size = 5)
        val b = video(2, "B 10", date = 300, duration = 10, size = 50)
        val c = video(3, "A", date = 200, duration = 20, size = 1)
        val all = listOf(a, b, c)
        assertEquals(listOf(b, c, a), VideoSortOrder.NEWEST.sort(all))
        assertEquals(listOf(a, c, b), VideoSortOrder.OLDEST.sort(all))
        assertEquals(listOf(c, a, b), VideoSortOrder.NAME_ASC.sort(all))
        assertEquals(listOf(b, a, c), VideoSortOrder.NAME_DESC.sort(all))
        assertEquals(listOf(a, c, b), VideoSortOrder.LONGEST.sort(all))
        assertEquals(listOf(b, a, c), VideoSortOrder.LARGEST.sort(all))
    }

    @Test
    fun statusFilters() {
        val v = video(1, "x")
        val downloaded = video(2, "y", downloaded = true)
        val inProgress = VideoProgress(position = 10, duration = 100, isCompleted = false)
        val done = VideoProgress(position = 100, duration = 100, isCompleted = true)
        assert(VideoStatusFilter.UNWATCHED.matches(v, null))
        assert(!VideoStatusFilter.UNWATCHED.matches(v, inProgress))
        assert(VideoStatusFilter.IN_PROGRESS.matches(v, inProgress))
        assert(VideoStatusFilter.WATCHED.matches(v, done))
        assert(!VideoStatusFilter.WATCHED.matches(v, inProgress))
        assert(VideoStatusFilter.DOWNLOADED.matches(downloaded, null))
        assert(!VideoStatusFilter.DOWNLOADED.matches(v, null))
    }
}
