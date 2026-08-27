package com.example.novaplayer.data.repository

import com.example.novaplayer.data.local.SongEntity
import java.util.Locale
import kotlin.math.pow
import kotlin.random.Random

enum class RecommendationSource {
    RELATED,
    ARTIST,
    GENRE,
    CATALOG,
    HISTORY
}

data class RecommendationCandidate(
    val song: SongEntity,
    val source: RecommendationSource,
    /** 0..1 seed score from the dated local catalog. */
    val popularityBoost: Double = 0.0,
    /** 0..1 local preference signal, currently favourites/history based. */
    val affinityBoost: Double = 0.0
)

/**
 * A small local ranker for music autoplay.
 *
 * It does not try to reproduce YouTube's private recommendation model. It
 * combines several public candidate pools, scores them with music-specific
 * signals, adds a small random tie-breaker, and caps repeated artists.
 */
object RecommendationEngine {

    fun select(
        seedId: String,
        seedTitle: String,
        seedArtist: String?,
        candidates: List<RecommendationCandidate>,
        excludedIds: Set<String> = emptySet(),
        limit: Int = 10,
        random: Random = Random.Default
    ): List<SongEntity> {
        if (limit <= 0) return emptyList()

        val excluded = excludedIds + seedId
        val pool = candidates
            .asSequence()
            .filter { it.song.id !in excluded }
            .filter { RecommendationPolicy.isQueueCandidate(it.song) }
            .groupBy { it.song.id }
            .map { (_, sameSong) ->
                val strongest = sameSong.maxBy {
                    sourceWeight(it.source) + it.popularityBoost + it.affinityBoost
                }
                val independentSources = sameSong.map { it.source }.distinct().size
                strongest to (independentSources - 1) * 0.04
            }
            .map { (candidate, sourceAgreementBoost) ->
                candidate to (
                    score(
                    candidate = candidate,
                    seedTitle = seedTitle,
                    seedArtist = seedArtist,
                    random = random
                    ) + sourceAgreementBoost
                )
            }
            .toList()

        val result = mutableListOf<SongEntity>()
        val artistCounts = mutableMapOf<String, Int>()
        val remaining = pool.toMutableList()

        // High scoring candidates are more likely to be chosen, not fixed in a
        // deterministic order. The artist cap prevents a popular artist from
        // occupying the entire queue.
        while (result.size < limit && remaining.isNotEmpty()) {
            val eligible = remaining.filter { (candidate, _) ->
                (artistCounts[normalize(candidate.song.artistName)] ?: 0) < 2
            }
            if (eligible.isEmpty()) break

            val chosen = weightedPick(eligible, random)
            val artistKey = normalize(chosen.first.song.artistName)
            result += chosen.first.song
            artistCounts[artistKey] = (artistCounts[artistKey] ?: 0) + 1
            remaining.remove(chosen)
        }

        return result
    }

    private fun score(
        candidate: RecommendationCandidate,
        seedTitle: String,
        seedArtist: String?,
        random: Random
    ): Double {
        val song = candidate.song
        var score = sourceWeight(candidate.source)
        score += candidate.popularityBoost.coerceIn(0.0, 1.0) * 0.34
        score += candidate.affinityBoost.coerceIn(0.0, 1.0) * 0.18

        if (!seedArtist.isNullOrBlank() &&
            normalize(song.artistName) == normalize(seedArtist)
        ) {
            score += 0.55
        }

        val seedWords = titleWords(seedTitle)
        val candidateWords = titleWords(song.title)
        if (seedWords.isNotEmpty() && candidateWords.intersect(seedWords).isNotEmpty()) {
            score += 0.15
        }

        when (song.durationSeconds) {
            in 150..420 -> score += 0.10
            in 1..89 -> score -= 0.08
        }

        val lowerTitle = song.title.lowercase(Locale.ROOT)
        if (listOf("official", "official audio", "music video", "visualizer", "audio")
                .any { lowerTitle.contains(it) }
        ) {
            score += 0.06
        }
        if (listOf("cover", "karaoke", "reaction", "slowed", "reverb", "live")
                .any { lowerTitle.contains(it) }
        ) {
            score -= 0.12
        }

        // This only breaks close ties; weighted sampling below supplies the
        // variation between sessions.
        return score + random.nextDouble(0.0, 0.08)
    }

    private fun weightedPick(
        candidates: List<Pair<RecommendationCandidate, Double>>,
        random: Random
    ): Pair<RecommendationCandidate, Double> {
        val totalWeight = candidates.sumOf { (_, score) -> score.coerceAtLeast(0.05).pow(3) }
        var cursor = random.nextDouble() * totalWeight
        return candidates.firstOrNull { (_, score) ->
            cursor -= score.coerceAtLeast(0.05).pow(3)
            cursor <= 0.0
        } ?: candidates.last()
    }

    private fun sourceWeight(source: RecommendationSource): Double = when (source) {
        RecommendationSource.RELATED -> 0.62
        RecommendationSource.ARTIST -> 0.58
        RecommendationSource.GENRE -> 0.42
        RecommendationSource.CATALOG -> 0.76
        RecommendationSource.HISTORY -> 0.35
    }

    private fun titleWords(value: String): Set<String> =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
}
