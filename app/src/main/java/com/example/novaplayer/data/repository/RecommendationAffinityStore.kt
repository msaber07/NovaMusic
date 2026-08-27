package com.example.novaplayer.data.repository

import android.content.Context
import com.example.novaplayer.data.local.SongEntity

/**
 * Lightweight, on-device preference signals for recommendations.
 *
 * This deliberately uses SharedPreferences rather than a Room schema change so
 * existing libraries remain untouched. The values are only aggregate genre and
 * artist hints; no listening history leaves the device.
 */
class RecommendationAffinityStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val catalog = ArtistCatalog(context.applicationContext)

    fun recordFavorite(song: SongEntity) = adjust(song, artistDelta = 4, genreDelta = 2)

    fun recordCompleted(song: SongEntity) = adjust(song, artistDelta = 1, genreDelta = 1)

    fun recordQuickSkip(song: SongEntity) = adjust(song, artistDelta = -4, genreDelta = -2)

    /** Returns a bounded 0..1 score consumed by [RecommendationEngine]. */
    fun boostFor(song: SongEntity): Double {
        val profile = catalog.resolveProfile(song.artistName, song.title, emptyList())
        val artistValue = preferences.getInt(artistKey(song.artistName), 0)
        val genreValue = preferences.getInt(genreKey(profile.genre), 0)
        return ((artistValue * 0.70 + genreValue * 0.30) / MAX_ABSOLUTE_SCORE)
            .coerceIn(-1.0, 1.0)
            .coerceAtLeast(0.0)
    }

    private fun adjust(song: SongEntity, artistDelta: Int, genreDelta: Int) {
        val profile = catalog.resolveProfile(song.artistName, song.title, emptyList())
        val artistKey = artistKey(song.artistName)
        val genreKey = genreKey(profile.genre)
        preferences.edit()
            .putInt(
                artistKey,
                (preferences.getInt(artistKey, 0) + artistDelta)
                    .coerceIn(-MAX_STORED_SCORE, MAX_STORED_SCORE)
            )
            .putInt(
                genreKey,
                (preferences.getInt(genreKey, 0) + genreDelta)
                    .coerceIn(-MAX_STORED_SCORE, MAX_STORED_SCORE)
            )
            .apply()
    }

    private fun artistKey(artistName: String): String =
        "artist:${RecommendationProfileResolver.normalize(artistName)}"

    private fun genreKey(genre: String): String = "genre:$genre"

    private companion object {
        const val PREFERENCES_NAME = "recommendation_affinity"
        const val MAX_STORED_SCORE = 20
        const val MAX_ABSOLUTE_SCORE = 20.0
    }
}
