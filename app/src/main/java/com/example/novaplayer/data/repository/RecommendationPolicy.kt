package com.example.novaplayer.data.repository

import com.example.novaplayer.data.local.SongEntity
import java.util.Locale

/**
 * Keeps search and autoplay focused on individual music tracks.
 *
 * A YouTube search result can be a perfectly valid video but still be a poor
 * music queue item: playlists, full albums and hour-long mixes are examples.
 * Duration is treated as a second line of defence because titles are not
 * consistent across languages and uploaders.
 */
object RecommendationPolicy {

    /** Tracks longer than this are not allowed into search results or autoplay. */
    const val MAX_TRACK_DURATION_SECONDS = 15 * 60

    private val collectionMarkers = listOf(
        "all songs",
        "all tracks",
        "full album",
        "full albums",
        "full playlist",
        "playlist",
        "compilation",
        "nonstop",
        "non stop",
        "music mix",
        "mega mix",
        "long mix",
        "live stream",
        "livestream",
        "24/7",
        "24 7",
        "derleme",
        "tam albüm",
        "çalma listesi"
    )

    private val durationMarkers = Regex(
        "\\b\\d+\\s*(hour|hours|hr|hrs|saat|minute|minutes|min)\\b|\\b\\d+\\s*h\\b",
        RegexOption.IGNORE_CASE
    )

    /** Returns true for titles that describe a collection, stream or long mix. */
    fun isCollectionOrLongFormTitle(title: String): Boolean {
        val normalized = title
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

        return collectionMarkers.any { marker ->
            normalized.contains(marker.lowercase(Locale.ROOT))
        } || durationMarkers.containsMatchIn(title)
    }

    /** Search may retain an unknown duration; a valid duration is still bounded. */
    fun isSearchCandidate(song: SongEntity): Boolean {
        if (isCollectionOrLongFormTitle(song.title)) return false
        return song.durationSeconds <= 0 || song.durationSeconds <= MAX_TRACK_DURATION_SECONDS
    }

    /** Autoplay requires a known, bounded duration so long/live items cannot sneak in. */
    fun isQueueCandidate(song: SongEntity): Boolean {
        return song.durationSeconds in 1..MAX_TRACK_DURATION_SECONDS &&
            !isCollectionOrLongFormTitle(song.title)
    }

    fun filterSearchResults(songs: List<SongEntity>): List<SongEntity> =
        songs.asSequence()
            .filter(::isSearchCandidate)
            .distinctBy { it.id }
            .toList()

    /** Keeps the selected item playable, while removing unsafe neighbours. */
    fun sanitizeQueue(selected: SongEntity, queue: List<SongEntity>): List<SongEntity> {
        val safeQueue = queue
            .asSequence()
            .filter { it.id == selected.id || isQueueCandidate(it) }
            .distinctBy { it.id }
            .toMutableList()

        if (safeQueue.none { it.id == selected.id }) {
            safeQueue.add(0, selected)
        }
        return safeQueue
    }
}
