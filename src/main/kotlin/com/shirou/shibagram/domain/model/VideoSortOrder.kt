package com.shirou.shibagram.domain.model

/**
 * How the videos of a channel are ordered. Persisted in user preferences.
 */
enum class VideoSortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME_ASC("Name (A → Z)"),
    NAME_DESC("Name (Z → A)"),
    LONGEST("Longest first"),
    SHORTEST("Shortest first"),
    LARGEST("Largest first"),
    SMALLEST("Smallest first");

    fun sort(videos: List<MediaMessage>): List<MediaMessage> = when (this) {
        NEWEST -> videos.sortedWith(compareByDescending<MediaMessage> { it.date }.thenByDescending { it.id })
        OLDEST -> videos.sortedWith(compareBy<MediaMessage> { it.date }.thenBy { it.id })
        NAME_ASC -> videos.sortedWith(compareBy(NaturalOrder) { it.displayTitle })
        NAME_DESC -> videos.sortedWith(compareByDescending(NaturalOrder) { it.displayTitle })
        LONGEST -> videos.sortedByDescending { it.duration }
        SHORTEST -> videos.sortedBy { it.duration }
        LARGEST -> videos.sortedByDescending { it.videoFile.size }
        SMALLEST -> videos.sortedBy { it.videoFile.size }
    }
}

/** Which videos of a channel to show, based on watch progress / local availability. */
enum class VideoStatusFilter(val label: String) {
    ALL("All"),
    UNWATCHED("Unwatched"),
    IN_PROGRESS("In progress"),
    WATCHED("Watched"),
    DOWNLOADED("Downloaded");

    fun matches(video: MediaMessage, progress: VideoProgress?): Boolean = when (this) {
        ALL -> true
        UNWATCHED -> progress == null || (!progress.isCompleted && progress.position <= 0)
        IN_PROGRESS -> progress != null && !progress.isCompleted && progress.position > 0
        WATCHED -> progress?.isCompleted == true
        DOWNLOADED -> video.videoFile.isDownloaded
    }
}

/** Saved playback state of one video. */
data class VideoProgress(val position: Long, val duration: Long, val isCompleted: Boolean) {
    val ratio: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

/**
 * "Natural" string order: digit runs compare by numeric value, so "Ep 2" < "Ep 10".
 * Case-insensitive; ties fall back to plain comparison so the order is total.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}
