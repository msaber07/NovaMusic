package com.example.novaplayer.data.repository

import android.content.Context
import com.google.gson.JsonParser
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

data class ArtistCatalogProfile(
    val name: String,
    val aliases: List<String>,
    val genres: List<String>,
    val language: String,
    /** Curated seed score from 1 to 100, not a claim of a live chart rank. */
    val popularity: Int
)

data class ListeningProfile(
    val genre: String,
    val language: String,
    /** How much of the profile came from reliable metadata rather than a fallback. */
    val confidence: Double
)

/**
 * Versioned, offline artist data used to make autoplay genre and language aware.
 *
 * The catalog is intentionally a seed: a release can update its dated
 * popularity values without changing the recommendation algorithm. Runtime
 * playback still resolves the actual song dynamically through the existing
 * YouTube search flow.
 */
class ArtistCatalog(private val context: Context) {

    fun profiles(): List<ArtistCatalogProfile> {
        return cachedProfiles ?: synchronized(this) {
            cachedProfiles ?: loadProfiles().also { cachedProfiles = it }
        }
    }

    fun resolveProfile(
        artistName: String?,
        title: String,
        tags: List<String>
    ): ListeningProfile = RecommendationProfileResolver.resolve(
        artistName = artistName,
        title = title,
        tags = tags,
        catalogProfiles = profiles()
    )

    fun selectArtists(
        listeningProfile: ListeningProfile,
        excludedArtistNames: Set<String>,
        count: Int,
        random: Random
    ): List<ArtistCatalogProfile> = RecommendationProfileResolver.selectArtists(
        listeningProfile = listeningProfile,
        catalogProfiles = profiles(),
        excludedArtistNames = excludedArtistNames,
        count = count,
        random = random
    )

    private fun loadProfiles(): List<ArtistCatalogProfile> = runCatching {
        context.assets.open(CATALOG_ASSET).bufferedReader().use { reader ->
            val root = JsonParser().parse(reader).asJsonObject
            root.getAsJsonArray("artists")
                .mapNotNull { element ->
                    val item = element.asJsonObject
                    val name = item.get("name")?.asString?.trim().orEmpty()
                    if (name.isBlank()) return@mapNotNull null
                    ArtistCatalogProfile(
                        name = name,
                        aliases = item.getAsJsonArray("aliases")
                            ?.mapNotNull { it.asString?.trim()?.takeIf(String::isNotBlank) }
                            .orEmpty(),
                        genres = item.getAsJsonArray("genres")
                            ?.mapNotNull { it.asString?.lowercase(Locale.ROOT) }
                            .orEmpty(),
                        language = item.get("language")?.asString
                            ?.lowercase(Locale.ROOT)
                            ?.takeIf { it in RecommendationProfileResolver.SUPPORTED_LANGUAGES }
                            ?: RecommendationProfileResolver.LANGUAGE_GLOBAL,
                        popularity = item.get("popularity")?.asInt?.coerceIn(1, 100) ?: 50
                    )
                }
        }
    }.getOrElse { emptyList() }

    private companion object {
        const val CATALOG_ASSET = "recommendation_catalog.json"

        @Volatile
        var cachedProfiles: List<ArtistCatalogProfile>? = null
    }
}

/** Pure profile and weighted-selection logic; unit-testable without Android. */
object RecommendationProfileResolver {

    fun resolve(
        artistName: String?,
        title: String,
        tags: List<String>,
        catalogProfiles: List<ArtistCatalogProfile>
    ): ListeningProfile {
        val knownArtist = findArtist(artistName, catalogProfiles)
            ?: findArtistInTitle(title, catalogProfiles)
        val genreScores = mutableMapOf<String, Int>()
        val metadataText = (tags + listOf(title, artistName.orEmpty()))
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        knownArtist?.genres?.forEachIndexed { index, genre ->
            genreScores[genre] = (genreScores[genre] ?: 0) + if (index == 0) 8 else 4
        }

        GENRE_KEYWORDS.forEach { (genre, keywords) ->
            val matches = keywords.count { keyword -> metadataText.contains(keyword) }
            if (matches > 0) {
                genreScores[genre] = (genreScores[genre] ?: 0) + matches * 3
            }
        }

        val topGenre = genreScores.maxByOrNull { it.value }?.key ?: GENRE_POP
        val topScore = genreScores[topGenre] ?: 0
        val language = knownArtist?.language ?: detectLanguage(metadataText)
        val confidence = when {
            knownArtist != null -> 0.95
            topScore >= 6 -> 0.75
            topScore >= 3 -> 0.55
            else -> 0.30
        }

        return ListeningProfile(topGenre, language, confidence)
    }

    fun selectArtists(
        listeningProfile: ListeningProfile,
        catalogProfiles: List<ArtistCatalogProfile>,
        excludedArtistNames: Set<String>,
        count: Int,
        random: Random
    ): List<ArtistCatalogProfile> {
        if (count <= 0) return emptyList()

        val excluded = excludedArtistNames.map(::normalize).toSet()
        val exactMatches = catalogProfiles.filter { profile ->
            profile.language == listeningProfile.language &&
                listeningProfile.genre in profile.genres &&
                normalize(profile.name) !in excluded
        }
        val sameGenre = catalogProfiles.filter { profile ->
            listeningProfile.genre in profile.genres && normalize(profile.name) !in excluded
        }
        val pool = (exactMatches.ifEmpty { sameGenre }).distinctBy { normalize(it.name) }
        val remaining = pool.toMutableList()
        val selected = mutableListOf<ArtistCatalogProfile>()

        while (selected.size < count && remaining.isNotEmpty()) {
            val totalWeight = remaining.sumOf { popularityWeight(it, listeningProfile) }
            var cursor = random.nextDouble() * totalWeight
            val chosen = remaining.firstOrNull { profile ->
                cursor -= popularityWeight(profile, listeningProfile)
                cursor <= 0.0
            } ?: remaining.last()
            selected += chosen
            remaining.remove(chosen)
        }

        return selected
    }

    fun findArtist(
        artistName: String?,
        catalogProfiles: List<ArtistCatalogProfile>
    ): ArtistCatalogProfile? {
        val normalizedArtist = normalize(artistName.orEmpty())
        if (normalizedArtist.isBlank()) return null
        return catalogProfiles.firstOrNull { profile ->
            val names = listOf(profile.name) + profile.aliases
            names.any { alias ->
                val normalizedAlias = normalize(alias)
                normalizedArtist == normalizedAlias ||
                    normalizedArtist.contains(normalizedAlias) ||
                    normalizedAlias.contains(normalizedArtist)
            }
        }
    }

    private fun findArtistInTitle(
        title: String,
        catalogProfiles: List<ArtistCatalogProfile>
    ): ArtistCatalogProfile? {
        val normalizedTitle = normalize(title)
        return catalogProfiles.firstOrNull { profile ->
            (listOf(profile.name) + profile.aliases).any { alias ->
                val normalizedAlias = normalize(alias)
                normalizedAlias.length >= 3 && normalizedTitle.contains(normalizedAlias)
            }
        }
    }

    fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun popularityWeight(
        profile: ArtistCatalogProfile,
        listeningProfile: ListeningProfile
    ): Double {
        val popularity = max(1, profile.popularity).toDouble()
        val languageBoost = if (profile.language == listeningProfile.language) 1.20 else 1.0
        return popularity * popularity * languageBoost
    }

    private fun detectLanguage(metadataText: String): String {
        val hasTurkishLetter = metadataText.any { it in TURKISH_CHARACTERS }
        val hasTurkishKeyword = TURKISH_KEYWORDS.any { metadataText.contains(it) }
        return if (hasTurkishLetter || hasTurkishKeyword) LANGUAGE_TURKISH else LANGUAGE_GLOBAL
    }

    private val GENRE_KEYWORDS = mapOf(
        GENRE_POP to listOf("pop", "girl group", "boy band", "dance pop"),
        GENRE_ROCK to listOf("rock", "metal", "punk", "grunge", "alternative rock"),
        GENRE_RAP to listOf("rap", "hip hop", "hip-hop", "trap", "drill"),
        GENRE_ELECTRONIC to listOf("electronic", "edm", "dance", "house", "techno", "dj"),
        GENRE_INDIE to listOf("indie", "alternative", "lofi", "lo-fi"),
        GENRE_FOLK to listOf("folk", "country", "arabesque", "türkü", "fantezi"),
        GENRE_RNB to listOf("r&b", "rnb", "soul", "neo soul")
    )

    private val TURKISH_CHARACTERS = setOf('ç', 'ğ', 'ı', 'ö', 'ş', 'ü')
    private val TURKISH_KEYWORDS = listOf("türkçe", "turkish", "şarkı", "müzik", "resmi klip")

    const val LANGUAGE_TURKISH = "tr"
    const val LANGUAGE_GLOBAL = "global"
    val SUPPORTED_LANGUAGES = setOf(LANGUAGE_TURKISH, LANGUAGE_GLOBAL)

    const val GENRE_POP = "pop"
    const val GENRE_ROCK = "rock"
    const val GENRE_RAP = "rap"
    const val GENRE_ELECTRONIC = "electronic"
    const val GENRE_INDIE = "indie"
    const val GENRE_FOLK = "folk"
    const val GENRE_RNB = "rnb"
}
