package com.example.novaplayer.data.repository

import android.content.Context
import com.example.novaplayer.data.local.MusicDatabase
import com.example.novaplayer.data.local.PlaylistEntity
import com.example.novaplayer.data.local.PlaylistSongCrossRef
import com.example.novaplayer.data.local.PlaylistWithSongs
import com.example.novaplayer.data.local.SongEntity
import com.example.novaplayer.data.network.InvidiousService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.ar.youtubeextractor.core.YouTubeExtractor
import com.ar.youtubeextractor.core.onSuccess
import com.ar.youtubeextractor.core.onError
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MusicRepository(private val context: Context) {

    private val db = MusicDatabase.getDatabase(context)
    private val dao = db.musicDao()

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    // Online Search
    fun searchTracks(query: String): Flow<List<SongEntity>> = flow {
        if (query.isBlank()) {
            emit(emptyList())
            return@flow
        }
        
        val instances = listOf(
            "https://inv.thepixora.com",
            "https://invidious.nerdvpn.de",
            "https://invidious.no-logs.com",
            "https://invidious.projectsegfau.lt",
            "https://yewtu.be"
        )
        
        for (base in instances) {
            try {
                val url = "$base/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=video"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: continue
                    val jsonElement = com.google.gson.JsonParser().parse(bodyStr)
                    if (!jsonElement.isJsonArray) continue
                    val jsonArray = jsonElement.asJsonArray
                    
                    val songs = mutableListOf<SongEntity>()
                    for (i in 0 until jsonArray.size()) {
                        val dto = jsonArray.get(i).asJsonObject
                        val videoId = dto.get("videoId")?.let { if (it.isJsonNull) null else it.getAsString() } ?: continue
                        val title = dto.get("title")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Title"
                        val author = dto.get("author")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Author"
                        val lengthSeconds = dto.get("lengthSeconds")?.let { if (it.isJsonNull) 0 else it.getAsInt() } ?: 0
                        
                        val songId = "yt_$videoId"
                        val localSong = dao.getSongById(songId)
                        
                        songs.add(
                            SongEntity(
                                id = songId,
                                title = title,
                                artistName = author,
                                audioUrl = "",
                                durationSeconds = lengthSeconds,
                                albumImageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                isDownloaded = localSong?.isDownloaded ?: false,
                                localUri = localSong?.localUri,
                                isFavorite = localSong?.isFavorite ?: false
                            )
                        )
                    }
                    emit(songs)
                    return@flow
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        emit(emptyList())
    }.flowOn(Dispatchers.IO)

    // Online Trending
    fun getTrendingTracks(): Flow<List<SongEntity>> = flow {
        val instances = listOf(
            "https://inv.thepixora.com",
            "https://invidious.nerdvpn.de",
            "https://invidious.no-logs.com",
            "https://invidious.projectsegfau.lt",
            "https://yewtu.be"
        )
        
        for (base in instances) {
            try {
                val url = "$base/api/v1/search?q=trending%20music&type=video"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: continue
                    val jsonElement = com.google.gson.JsonParser().parse(bodyStr)
                    if (!jsonElement.isJsonArray) continue
                    val jsonArray = jsonElement.asJsonArray
                    
                    val songs = mutableListOf<SongEntity>()
                    for (i in 0 until jsonArray.size()) {
                        val dto = jsonArray.get(i).asJsonObject
                        val videoId = dto.get("videoId")?.let { if (it.isJsonNull) null else it.getAsString() } ?: continue
                        val title = dto.get("title")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Title"
                        val author = dto.get("author")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Author"
                        val lengthSeconds = dto.get("lengthSeconds")?.let { if (it.isJsonNull) 0 else it.getAsInt() } ?: 0
                        
                        val songId = "yt_$videoId"
                        val localSong = dao.getSongById(songId)
                        
                        songs.add(
                            SongEntity(
                                id = songId,
                                title = title,
                                artistName = author,
                                audioUrl = "",
                                durationSeconds = lengthSeconds,
                                albumImageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                isDownloaded = localSong?.isDownloaded ?: false,
                                localUri = localSong?.localUri,
                                isFavorite = localSong?.isFavorite ?: false
                            )
                        )
                    }
                    emit(songs)
                    return@flow
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        emit(emptyList())
    }.flowOn(Dispatchers.IO)

    suspend fun resolveStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        if (!songId.startsWith("yt_")) return@withContext ""
        val videoId = songId.removePrefix("yt_")
        return@withContext "https://inv.thepixora.com/latest_version?id=$videoId&itag=140&local=true"
    }

    private fun searchInvidiousSync(base: String, query: String): List<SongEntity> {
        try {
            val url = "$base/api/v1/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=video"
            val request = Request.Builder()
                .url(url)
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: return emptyList()
                val jsonElement = com.google.gson.JsonParser().parse(bodyStr)
                if (jsonElement.isJsonArray) {
                    val jsonArray = jsonElement.asJsonArray
                    val songs = mutableListOf<SongEntity>()
                    for (i in 0 until minOf(jsonArray.size(), 10)) {
                        val dto = jsonArray.get(i).asJsonObject
                        val videoId = dto.get("videoId")?.let { if (it.isJsonNull) null else it.getAsString() } ?: continue
                        val title = dto.get("title")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Title"
                        val author = dto.get("author")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Author"
                        val lengthSeconds = dto.get("lengthSeconds")?.let { if (it.isJsonNull) 0 else it.getAsInt() } ?: 0
                        
                        val songId = "yt_$videoId"
                        val localSong = dao.getSongById(songId)
                        songs.add(
                            SongEntity(
                                id = songId,
                                title = title,
                                artistName = author,
                                audioUrl = "",
                                durationSeconds = lengthSeconds,
                                albumImageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                                isDownloaded = localSong?.isDownloaded ?: false,
                                localUri = localSong?.localUri,
                                isFavorite = localSong?.isFavorite ?: false
                            )
                        )
                    }
                    return songs
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    suspend fun getRecommendedVideos(songId: String): List<SongEntity> = withContext(Dispatchers.IO) {
        if (!songId.startsWith("yt_")) return@withContext emptyList()
        val videoId = songId.removePrefix("yt_")
        
        val instances = listOf(
            "https://inv.thepixora.com",
            "https://invidious.nerdvpn.de",
            "https://invidious.no-logs.com",
            "https://invidious.projectsegfau.lt",
            "https://yewtu.be"
        )
        
        for (base in instances) {
            try {
                val url = "$base/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: continue
                    val jsonObj = com.google.gson.JsonParser().parse(bodyStr).asJsonObject
                    
                    // Extract author and tags (keywords)
                    val author = jsonObj.get("author")?.let { if (it.isJsonNull) null else it.getAsString() }
                    val keywordsJson = jsonObj.get("keywords")
                    val tags = mutableListOf<String>()
                    if (keywordsJson != null && keywordsJson.isJsonArray) {
                        val arr = keywordsJson.asJsonArray
                        for (j in 0 until arr.size()) {
                            val tag = arr.get(j)?.let { if (it.isJsonNull) null else it.getAsString() }
                            if (tag != null) {
                                tags.add(tag.lowercase(java.util.Locale.ROOT))
                            }
                        }
                    }
                    
                    // Detect genre and language
                    val genreMappings = mapOf(
                        "pop" to "pop music",
                        "girlband" to "pop music",
                        "girl group" to "pop music",
                        "boyband" to "pop music",
                        "kpop" to "kpop music",
                        "rock" to "rock music",
                        "metal" to "metal music",
                        "punk" to "rock music",
                        "rap" to "rap music",
                        "hip hop" to "hip hop music",
                        "drill" to "rap music",
                        "trap" to "trap music",
                        "r&b" to "r&b music",
                        "jazz" to "jazz music",
                        "classical" to "classical music",
                        "electronic" to "electronic music",
                        "dance" to "dance music",
                        "house" to "house music",
                        "techno" to "techno music",
                        "edm" to "edm music",
                        "indie" to "indie music",
                        "alternative" to "alternative music",
                        "country" to "country music",
                        "folk" to "folk music",
                        "lofi" to "lofi hip hop",
                        "chill" to "lofi hip hop"
                    )
                    
                    val isTurkish = tags.any { tag -> tag.any { c -> c == 'ı' || c == 'ş' || c == 'ğ' || c == 'ü' || c == 'ö' || c == 'ç' } }
                    
                    val matchedKey = genreMappings.keys.find { key -> tags.any { tag -> tag.contains(key) } }
                    val mappedGenre = matchedKey?.let { genreMappings[it] }
                    
                    val genreQuery = if (!mappedGenre.isNullOrEmpty()) {
                        if (isTurkish && mappedGenre == "pop music") {
                            "popüler türkçe pop"
                        } else if (isTurkish && (mappedGenre == "rap music" || mappedGenre == "hip hop music" || mappedGenre == "trap music")) {
                            "popüler türkçe rap"
                        } else {
                            "popular $mappedGenre"
                        }
                    } else {
                        if (isTurkish) "popüler türkçe müzik" else "popular music"
                    }
                    
                    val artistQuery = if (!author.isNullOrEmpty() && author != "Unknown Author" && author != "Unknown") {
                        val lowerAuthor = author.lowercase(java.util.Locale.ROOT)
                        val qualifiers = listOf("music", "müzik", "video", "song", "şarkı", "band", "grubu", "group", "girlband", "boyband", "live", "concert", "albüm", "album")
                        val qualifiedTag = tags.find { tag ->
                            tag.contains(lowerAuthor) && qualifiers.any { q -> tag.contains(q) } && !tag.contains("reaction") && !tag.contains("cover")
                        }
                        qualifiedTag ?: (if (isTurkish) "$author şarkıları" else "$author songs")
                    } else {
                        ""
                    }
                    
                    // Parse direct recommended videos
                    val recommendedList = mutableListOf<SongEntity>()
                    val recommendedJson = jsonObj.get("recommendedVideos")
                    if (recommendedJson != null && recommendedJson.isJsonArray) {
                        val jsonArray = recommendedJson.asJsonArray
                        for (i in 0 until jsonArray.size()) {
                            val dto = jsonArray.get(i).asJsonObject
                            val recVideoId = dto.get("videoId")?.let { if (it.isJsonNull) null else it.getAsString() } ?: continue
                            val title = dto.get("title")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Title"
                            val artist = dto.get("author")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Author"
                            val lengthSeconds = dto.get("lengthSeconds")?.let { if (it.isJsonNull) 0 else it.getAsInt() } ?: 0
                            
                            val recSongId = "yt_$recVideoId"
                            val localSong = dao.getSongById(recSongId)
                            recommendedList.add(
                                SongEntity(
                                    id = recSongId,
                                    title = title,
                                    artistName = artist,
                                    audioUrl = "",
                                    durationSeconds = lengthSeconds,
                                    albumImageUrl = "https://img.youtube.com/vi/$recVideoId/hqdefault.jpg",
                                    isDownloaded = localSong?.isDownloaded ?: false,
                                    localUri = localSong?.localUri,
                                    isFavorite = localSong?.isFavorite ?: false
                                )
                            )
                        }
                    }

                    // Fetch artist songs and genre popular songs in parallel
                    val (artistSongs, genreSongs) = coroutineScope {
                        val artistDeferred = async {
                            if (!artistQuery.isEmpty()) {
                                searchInvidiousSync(base, artistQuery)
                            } else {
                                emptyList()
                            }
                        }
                        val genreDeferred = async {
                            if (!genreQuery.isEmpty()) {
                                searchInvidiousSync(base, genreQuery)
                            } else {
                                emptyList()
                            }
                        }
                        Pair(artistDeferred.await(), genreDeferred.await())
                    }
                    
                    // Mixing and Deduplication logic matching User's requested ratio:
                    // 50% Genre, 35% Artist, 15% Related
                    val finalSongs = mutableListOf<SongEntity>()
                    val seenIds = mutableSetOf<String>()
                    seenIds.add(songId) // skip seed song
                    
                    // Setup indices
                    var recIdx = 0
                    var artIdx = 0
                    var genIdx = 0
                    
                    // We target 15 songs.
                    // The distribution:
                    // - Genre: 8 songs
                    // - Artist: 5 songs
                    // - Related: 2 songs
                    // If some sources are unavailable, fallback gracefully.
                    val targetGenreCount = if (genreSongs.isNotEmpty()) 8 else 0
                    val targetArtistCount = if (artistSongs.isNotEmpty()) {
                        if (targetGenreCount == 0) 10 else 5 // If genre is empty, take up to 10 from artist (70%)
                    } else 0
                    val targetRelatedCount = 15 - (targetGenreCount + targetArtistCount) // fill the rest (min 2, max 15)
                    
                    var genreAdded = 0
                    var artistAdded = 0
                    var relatedAdded = 0
                    
                    // Interleave loops
                    while (finalSongs.size < 15 && (recIdx < recommendedList.size || artIdx < artistSongs.size || genIdx < genreSongs.size)) {
                        // 1. Add Genre (up to targetGenreCount)
                        if (genIdx < genreSongs.size && genreAdded < targetGenreCount) {
                            val s = genreSongs[genIdx++]
                            if (seenIds.add(s.id)) {
                                finalSongs.add(s)
                                genreAdded++
                            }
                        }
                        // 2. Add Artist (up to targetArtistCount)
                        if (artIdx < artistSongs.size && artistAdded < targetArtistCount && finalSongs.size < 15) {
                            val s = artistSongs[artIdx++]
                            if (seenIds.add(s.id)) {
                                finalSongs.add(s)
                                artistAdded++
                            }
                        }
                        // 3. Add Related (up to targetRelatedCount)
                        if (recIdx < recommendedList.size && relatedAdded < targetRelatedCount && finalSongs.size < 15) {
                            val s = recommendedList[recIdx++]
                            if (seenIds.add(s.id)) {
                                finalSongs.add(s)
                                relatedAdded++
                            }
                        }
                        
                        // Safety breakout: if no new songs can be added from any index progress
                        if (recIdx >= recommendedList.size && artIdx >= artistSongs.size && genIdx >= genreSongs.size) {
                            break
                        }
                    }
                    
                    // If still under 15, fill the rest with whatever is remaining in related
                    if (finalSongs.size < 15) {
                        for (s in recommendedList) {
                            if (seenIds.add(s.id)) {
                                finalSongs.add(s)
                                if (finalSongs.size >= 15) break
                            }
                        }
                    }
                    
                    return@withContext finalSongs
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return@withContext emptyList()
    }

    // Local DB Flows
    fun getFavoriteSongsFlow(): Flow<List<SongEntity>> = dao.getFavoriteSongsFlow()
    fun getDownloadedSongsFlow(): Flow<List<SongEntity>> = dao.getDownloadedSongsFlow()
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>> = dao.getAllPlaylistsFlow()
    
    fun getPlaylistWithSongsFlow(playlistId: String): Flow<PlaylistWithSongs?> = 
        dao.getPlaylistWithSongsFlow(playlistId)

    // DB Operations
    fun getSongByIdSync(songId: String): SongEntity? {
        return dao.getSongById(songId)
    }

    suspend fun insertSong(song: SongEntity) = withContext(Dispatchers.IO) {
        dao.insertSong(song)
    }

    suspend fun toggleFavorite(song: SongEntity) = withContext(Dispatchers.IO) {
        val existing = dao.getSongById(song.id)
        if (existing == null) {
            dao.insertSong(song.copy(isFavorite = true))
        } else {
            dao.updateSong(existing.copy(isFavorite = !existing.isFavorite))
        }
    }

    suspend fun createPlaylist(name: String, description: String? = null) = withContext(Dispatchers.IO) {
        val playlist = PlaylistEntity(
            playlistId = UUID.randomUUID().toString(),
            name = name,
            description = description
        )
        dao.insertPlaylist(playlist)
    }

    suspend fun deletePlaylist(playlistId: String) = withContext(Dispatchers.IO) {
        dao.deletePlaylistById(playlistId)
    }

    suspend fun addSongToPlaylist(song: SongEntity, playlistId: String) = withContext(Dispatchers.IO) {
        val existing = dao.getSongById(song.id)
        if (existing == null) {
            dao.insertSong(song)
        }
        dao.insertPlaylistSongCrossRef(PlaylistSongCrossRef(playlistId, song.id))
    }

    suspend fun removeSongFromPlaylist(songId: String, playlistId: String) = withContext(Dispatchers.IO) {
        dao.deletePlaylistSongCrossRef(PlaylistSongCrossRef(playlistId, songId))
    }

    // Download song logic
    suspend fun downloadSong(song: SongEntity, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = dao.getSongById(song.id) ?: song.also { dao.insertSong(it) }
            if (existing.isDownloaded && existing.localUri != null) {
                val file = File(existing.localUri)
                if (file.exists()) {
                    return@withContext true
                }
            }

            val downloadUrl = if (song.id.startsWith("yt_")) {
                resolveStreamUrl(song.id)
            } else {
                song.audioUrl
            }
            if (downloadUrl.isBlank()) return@withContext false

            val request = Request.Builder().url(downloadUrl).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()
            
            val destFile = File(context.filesDir, "audio_${song.id}.mp3")
            if (destFile.exists()) {
                destFile.delete()
            }

            body.byteStream().use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            onProgress(totalBytesRead.toFloat() / contentLength)
                        }
                    }
                }
            }

            val updated = (dao.getSongById(song.id) ?: song).copy(
                isDownloaded = true,
                localUri = destFile.absolutePath
            )
            dao.insertSong(updated)
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    suspend fun deleteDownloadedSong(song: SongEntity) = withContext(Dispatchers.IO) {
        val existing = dao.getSongById(song.id)
        if (existing != null) {
            existing.localUri?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            dao.updateSong(existing.copy(isDownloaded = false, localUri = null))
        }
    }
}
