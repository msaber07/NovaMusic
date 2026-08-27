package com.example.novaplayer.data.repository

import android.content.Context
import android.net.Uri
import com.example.novaplayer.data.local.MusicDatabase
import com.example.novaplayer.data.local.PlaylistEntity
import com.example.novaplayer.data.local.PlaylistSongCrossRef
import com.example.novaplayer.data.local.PlaylistWithSongs
import com.example.novaplayer.data.local.SongEntity
import com.example.novaplayer.data.network.InvidiousService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.yushosei.newpipe.extractor.NewPipe
import com.yushosei.newpipe.extractor.ServiceList
import com.yushosei.newpipe.extractor.stream.AudioStream
import com.yushosei.newpipe.util.DefaultDownloaderImpl
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.example.novaplayer.R

class MusicRepository(private val context: Context) {

    companion object {
        @Volatile
        private var isNewPipeInitialized = false

        private const val STREAM_CHUNK_SIZE = 512L * 1024L
        
        val resolutionStatus = kotlinx.coroutines.flow.MutableStateFlow("")
    }

    private val db = MusicDatabase.getDatabase(context)
    private val dao = db.musicDao()

    private val invidiousInstances = listOf(
        "https://inv.thepixora.com",
        "https://invidious.nerdvpn.de",
        "https://invidious.no-logs.com",
        "https://invidious.projectsegfau.lt",
        "https://yewtu.be",
        "https://invidious.io.lol",
        "https://iv.ggtyler.dev",
        "https://invidious.slipfox.xyz",
        "https://invidious.flokinet.to",
        "https://invidious.privacydev.net",
        "https://invidious.lunar.icu"
    )

    @Volatile
    private var activeInvidiousBaseUrl = "https://inv.thepixora.com"

    private fun checkUrlWorks(url: String): Boolean {
        log("Testing URL: ${redactUrlForLog(url)}")
        return try {
            val requestBuilder = Request.Builder().url(url)
            
            if (url.contains("googlevideo.com")) {
                requestBuilder.get()
                requestBuilder.header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 10; en_US;)")
                requestBuilder.header("Range", "bytes=0-500000")
            } else {
                requestBuilder.head()
                requestBuilder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            }
            
            val request = requestBuilder.build()
            
            val tempClient = okHttpClient.newBuilder()
                .connectTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
                
            tempClient.newCall(request).execute().use { response ->
                val isSuccessful = response.isSuccessful
                val contentType = response.header("Content-Type") ?: ""
                val works = isSuccessful && !contentType.contains("text/html") && !contentType.contains("text/plain") && !contentType.contains("application/json")
                log("  URL ${redactUrlForLog(url)} -> code: ${response.code}, Content-Type: $contentType, works: $works")
                works
            }
        } catch (e: Exception) {
            log("  URL ${redactUrlForLog(url)} -> failed with exception: ${e.message}")
            false
        }
    }

    val okHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val original = chain.request()
            val hasUserAgent = original.header("User-Agent") != null
            val request = if (hasUserAgent) {
                original
            } else {
                original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
            }
            chain.proceed(request)
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            // Request bodies contain visitor data/PoTokens and response URLs can
            // contain signed googlevideo query parameters. Keep those out of the
            // on-device diagnostic log.
            level = HttpLoggingInterceptor.Level.NONE
        })
        .build()

    /** Logs only non-sensitive URL metadata; never expose signed query values. */
    fun redactUrlForLog(rawUrl: String): String {
        return runCatching {
            val parsed = Uri.parse(rawUrl)
            val metadata = listOf("itag", "clen", "dur")
                .mapNotNull { key -> parsed.getQueryParameter(key)?.let { "$key=$it" } }
                .joinToString(",")
            buildString {
                append(parsed.scheme ?: "https")
                append("://")
                append(parsed.host ?: "redacted")
                if (!parsed.encodedPath.isNullOrEmpty()) append("/[path]")
                if (metadata.isNotEmpty()) append("?$metadata")
            }
        }.getOrDefault("[redacted-url]")
    }

    private val youtubePoTokenProvider by lazy {
        YoutubePoTokenProvider(context, okHttpClient, ::log)
    }

    private val artistCatalog by lazy { ArtistCatalog(context) }
    private val affinityStore by lazy { RecommendationAffinityStore(context) }
    private val catalogSearchCache = ConcurrentHashMap<String, List<SongEntity>>()

    // Local search helper using NewPipe Extractor
    private suspend fun searchNewPipe(query: String): List<SongEntity> = withContext(Dispatchers.IO) {
        try {
            if (!isNewPipeInitialized) {
                log("Initializing NewPipe for search...")
                NewPipe.init(DefaultDownloaderImpl.initDefault())
                isNewPipeInitialized = true
            }
            log("Searching NewPipe locally for query: $query")
            val searchInfo = com.yushosei.newpipe.util.ExtractorHelper.searchFor(
                ServiceList.YouTube.serviceId,
                query,
                emptyList(),
                ""
            )
            val relatedItems = searchInfo.relatedItems as? List<*> ?: return@withContext emptyList()
            val songs = mutableListOf<SongEntity>()
            for (item in relatedItems) {
                if (item is com.yushosei.newpipe.extractor.stream.StreamInfoItem) {
                    val url = item.url
                    val videoId = if (url.contains("v=")) {
                        url.substringAfter("v=").substringBefore("&")
                    } else if (url.contains("youtu.be/")) {
                        url.substringAfter("youtu.be/").substringBefore("?")
                    } else {
                        continue
                    }
                    
                    val title = item.name
                    val author = item.uploaderName ?: "Unknown Author"
                    val lengthSeconds = item.duration
                    
                    val songId = "yt_$videoId"
                    val localSong = dao.getSongById(songId)
                    songs.add(
                        SongEntity(
                            id = songId,
                            title = title,
                            artistName = author,
                            audioUrl = "",
                            durationSeconds = lengthSeconds.toInt(),
                            albumImageUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                            isDownloaded = localSong?.isDownloaded ?: false,
                            localUri = localSong?.localUri,
                            isFavorite = localSong?.isFavorite ?: false
                        )
                    )
                }
            }
            val filteredSongs = RecommendationPolicy.filterSearchResults(songs)
            log("NewPipe search found ${songs.size} results; ${filteredSongs.size} music candidates remain after filtering.")
            return@withContext filteredSongs
        } catch (e: Exception) {
            log("NewPipe search failed: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    // Online Search
    fun searchTracks(query: String): Flow<List<SongEntity>> = flow {
        if (query.isBlank()) {
            emit(emptyList())
            return@flow
        }
        
        // 1. Try NewPipe Extractor search first
        try {
            val localSongs = searchNewPipe(query)
            if (localSongs.isNotEmpty()) {
                emit(RecommendationPolicy.filterSearchResults(localSongs))
                return@flow
            }
        } catch (e: Exception) {
            log("Error doing NewPipe search, falling back: ${e.message}")
            e.printStackTrace()
        }
        
        // 2. Fallback to Invidious
        log("Falling back to Invidious for search query: $query")
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
                    val filteredSongs = RecommendationPolicy.filterSearchResults(songs)
                    if (filteredSongs.isNotEmpty()) {
                        emit(filteredSongs)
                        return@flow
                    }
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
                    val filteredSongs = RecommendationPolicy.filterSearchResults(songs)
                    if (filteredSongs.isNotEmpty()) {
                        emit(filteredSongs)
                        return@flow
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        emit(emptyList())
    }.flowOn(Dispatchers.IO)

    private fun fetchVisitorDataAndCookies(videoId: String): Pair<String?, String?> {
        log("fetchVisitorDataAndCookies: Fetching watch page for $videoId...")
        try {
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val browserUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val request = Request.Builder()
                .url(watchUrl)
                .header("User-Agent", browserUa)
                .build()
                
            val tempClient = okHttpClient.newBuilder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
                
            tempClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    
                    // Extract visitorData
                    var visitorData: String? = null
                    val pattern1 = java.util.regex.Pattern.compile("\"visitorData\"\\s*:\\s*\"([^\"]+)\"")
                    val matcher1 = pattern1.matcher(html)
                    if (matcher1.find()) {
                        visitorData = matcher1.group(1)
                    } else {
                        val pattern2 = java.util.regex.Pattern.compile("VISITOR_DATA\\s*:\\s*\"([^\"]+)\"")
                        val matcher2 = pattern2.matcher(html)
                        if (matcher2.find()) {
                            visitorData = matcher2.group(1)
                        }
                    }
                    
                    // Extract cookies
                    val cookieList = mutableListOf<String>()
                    val headersMap = response.headers
                    for (i in 0 until headersMap.size) {
                        if (headersMap.name(i).equals("Set-Cookie", ignoreCase = true)) {
                            val value = headersMap.value(i)
                            val parts = value.split(";")
                            if (parts.isNotEmpty()) {
                                cookieList.add(parts[0])
                            }
                        }
                    }
                    val cookies = if (cookieList.isNotEmpty()) cookieList.joinToString("; ") else null
                    
                    log("  visitorData extracted: ${visitorData?.take(30)}... cookies count: ${cookieList.size}")
                    return Pair(visitorData, cookies)
                } else {
                    log("  watch page fetch failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            log("  watch page fetch error: ${e.message}")
            e.printStackTrace()
        }
        return Pair(null, null)
    }

    private suspend fun resolveStreamUrlViaInnerTube(videoId: String): YoutubeSabrStreamInfo? = withContext(Dispatchers.IO) {
        log("resolveStreamUrlViaInnerTube: Resolving $videoId via InnerTube ANDROID API with SABR support...")
        try {
            // Keep the initial player request anonymous. The visitor data returned by
            // this response is used to mint the short-lived streaming poToken below.
            val cookies: String? = null
            
            val url = "https://www.youtube.com/youtubei/v1/player"
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            
            // Construct context JSON
            val jsonPayload = """
                {
                    "videoId": "$videoId",
                    "context": {
                        "client": {
                            "clientName": "ANDROID",
                            "clientVersion": "20.10.38",
                            "androidSdkVersion": 34,
                            "osName": "Android",
                            "osVersion": "14",
                            "hl": "en",
                            "gl": "US",
                            "utcOffsetMinutes": 0
                        }
                    }
                }
            """.trimIndent()
            
            val requestBody = jsonPayload.toRequestBody(mediaType)
            val playerUa = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", playerUa)
                .header("X-YouTube-Client-Name", "3")
                .header("X-YouTube-Client-Version", "20.10.38")
                .header("Accept", "application/json")
                
            if (cookies != null) {
                requestBuilder.header("Cookie", cookies)
            }
            
            val request = requestBuilder.build()
            
            val tempClient = okHttpClient.newBuilder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                
            tempClient.newCall(request).execute().use { response ->
                log("  InnerTube response code: ${response.code}")
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    if (bodyStr.isNotEmpty()) {
                        val json = com.google.gson.JsonParser().parse(bodyStr).asJsonObject
                        val responseVisitorData = json.getAsJsonObject("responseContext")
                            ?.get("visitorData")?.asString
                        
                        val poTokens = responseVisitorData?.let { visitor ->
                            runCatching {
                                youtubePoTokenProvider.getTokens(videoId, visitor)
                            }.onFailure { error ->
                                log("  YouTube poToken generation failed: ${error.message}")
                            }.getOrNull()
                        }

                        // Web BotGuard tokens must be bound to the player
                        // response that supplies the media URLs. Reissue the
                        // request with both visitorData and the player token;
                        // otherwise the token may be valid but ignored by the
                        // playback/SABR backend.
                        val playerJson = if (poTokens != null) {
                            val tokenPayload = """
                                {
                                    "videoId": "$videoId",
                                    "contentCheckOk": true,
                                    "racyCheckOk": true,
                                    "context": {
                                        "client": {
                                            "clientName": "ANDROID",
                                            "clientVersion": "20.10.38",
                                            "androidSdkVersion": 34,
                                            "osName": "Android",
                                            "osVersion": "14",
                                            "hl": "en",
                                            "gl": "US",
                                            "utcOffsetMinutes": 0,
                                            "visitorData": "${poTokens.visitorData}"
                                        }
                                    },
                                    "serviceIntegrityDimensions": {
                                        "poToken": "${poTokens.playerPoToken}"
                                    }
                                }
                            """.trimIndent()
                            val tokenRequest = Request.Builder()
                                .url(url)
                                .post(tokenPayload.toRequestBody(mediaType))
                                .header("Content-Type", "application/json")
                                .header("User-Agent", playerUa)
                                .header("X-YouTube-Client-Name", "3")
                                .header("X-YouTube-Client-Version", "20.10.38")
                                .header("Accept", "application/json")
                                .header("X-Goog-Visitor-Id", poTokens.visitorData)
                                .build()
                            runCatching {
                                tempClient.newCall(tokenRequest).execute().use { tokenResponse ->
                                    if (tokenResponse.isSuccessful) {
                                        tokenResponse.body?.string()?.takeIf { it.isNotBlank() }?.let {
                                            com.google.gson.JsonParser().parse(it).asJsonObject
                                        }
                                    } else {
                                        log("  Token-bound InnerTube request failed: ${tokenResponse.code}")
                                        null
                                    }
                                }
                            }.getOrNull() ?: json
                        } else {
                            json
                        }

                        val effectiveVisitorData = poTokens?.visitorData
                            ?: playerJson.getAsJsonObject("responseContext")
                                ?.get("visitorData")?.asString
                            ?: responseVisitorData

                        val playabilityStatus = playerJson.getAsJsonObject("playabilityStatus")
                        val status = playabilityStatus?.get("status")?.asString
                        if (status != null && status != "OK") {
                            val reason = playabilityStatus.get("reason")?.asString ?: "Unknown restriction"
                            log("  InnerTube playback check: Video playability status is $status ($reason)")
                        }
                        
                        val streamingData = playerJson.getAsJsonObject("streamingData")
                        if (streamingData != null) {
                            val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
                            if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
                                var selectedUrl: String? = null
                                var selectedItag = -1
                                var selectedVideoItag = -1
                                var selectedLastModified = 0L
                                var selectedVideoLastModified = 0L
                                var selectedContentLength = -1L
                                var bestScore = -1
                                
                                for (element in adaptiveFormats) {
                                    val fmt = element.asJsonObject
                                    val mimeType = fmt.get("mimeType")?.asString ?: ""
                                    if (mimeType.startsWith("video/") && selectedVideoItag <= 0) {
                                        selectedVideoItag = fmt.get("itag")?.asInt ?: -1
                                        selectedVideoLastModified = fmt.get("lastModified")?.asLong ?: 0L
                                    }
                                    if (mimeType.contains("audio")) {
                                        val streamUrl = fmt.get("url")?.asString
                                        if (!streamUrl.isNullOrEmpty()) {
                                            val itag = fmt.get("itag")?.asInt ?: -1
                                            val score = when (itag) {
                                                140 -> 5
                                                251 -> 4
                                                250 -> 3
                                                249 -> 2
                                                139 -> 1
                                                else -> 0
                                            }
                                            
                                            log("  InnerTube found audio stream: itag=$itag, score=$score, mimeType=$mimeType, bitrate=${fmt.get("bitrate")?.asInt}")
                                            
                                            if (score > bestScore || selectedUrl == null) {
                                                bestScore = score
                                                selectedUrl = streamUrl
                                                selectedItag = itag
                                                selectedLastModified = fmt.get("lastModified")?.asLong ?: 0L
                                                selectedContentLength = fmt.get("contentLength")?.asLong ?: -1L
                                            }
                                        }
                                    }
                                }

                                // YouTube still exposes a progressive muxed MP4 for
                                // some videos. It is a reliable compatibility path
                                // for audio playback because it is not subject to the
                                // short byte-range window applied to SABR adaptive
                                // URLs. Prefer it only for the direct fallback; SABR
                                // continues to use the selected adaptive audio format.
                                val playerProgressiveUrl = streamingData.getAsJsonArray("formats")
                                    ?.map { it.asJsonObject }
                                    ?.firstOrNull { !it.get("url")?.asString.isNullOrBlank() }
                                    ?.get("url")?.asString
                                val originalProgressiveUrl = json.getAsJsonObject("streamingData")
                                    ?.getAsJsonArray("formats")
                                    ?.map { it.asJsonObject }
                                    ?.firstOrNull { !it.get("url")?.asString.isNullOrBlank() }
                                    ?.get("url")?.asString
                                val progressiveUrl = playerProgressiveUrl ?: originalProgressiveUrl
                                
                                val serverAbrUrl = streamingData.get("serverAbrStreamingUrl")?.asString
                                // When a PoToken-bound player response is used, its
                                // Ustreamer config is bound to the same playback
                                // context. Pairing it with the anonymous response's
                                // config makes SABR reject the request as malformed.
                                val ustreamerConfig = playerJson.getAsJsonObject("playerConfig")
                                    ?.getAsJsonObject("mediaCommonConfig")
                                    ?.getAsJsonObject("mediaUstreamerRequestConfig")
                                    ?.get("videoPlaybackUstreamerConfig")
                                    ?.asString
                                if (!selectedUrl.isNullOrBlank() && !serverAbrUrl.isNullOrBlank() && !ustreamerConfig.isNullOrBlank()) {
                                    val directUrl = progressiveUrl ?: selectedUrl
                                    val resolvedDirectUrl = if (progressiveUrl != null) {
                                        // The anonymous Android response's muxed MP4
                                        // remains usable for the direct compatibility
                                        // path and does not need a streaming PoToken.
                                        directUrl
                                    } else poTokens?.streamingPoToken?.let { token ->
                                        Uri.parse(directUrl).buildUpon()
                                            .appendQueryParameter("pot", token)
                                            .build()
                                            .toString()
                                    } ?: directUrl
                                    val durationMs = playerJson.getAsJsonObject("videoDetails")
                                        ?.get("lengthSeconds")?.asLong?.times(1000L) ?: -1L
                                    log(
                                        "  InnerTube SABR session ready: itag=$selectedItag, " +
                                            "videoItag=$selectedVideoItag, " +
                                            "contentLength=$selectedContentLength, durationMs=$durationMs, " +
                                            "poToken=${poTokens != null}, " +
                                            "directTransport=" + when {
                                                playerProgressiveUrl != null -> "progressive-token-bound"
                                                originalProgressiveUrl != null -> "progressive-anonymous"
                                                else -> "adaptive"
                                            }
                                    )
                                    return@withContext YoutubeSabrStreamInfo(
                                        videoId = videoId,
                                        directUrl = resolvedDirectUrl,
                                        serverAbrStreamingUrl = serverAbrUrl,
                                        videoPlaybackUstreamerConfig = ustreamerConfig,
                                        formatId = selectedItag,
                                        // SABR needs a video format selected as a discarded track
                                        // even for audio-only playback. The DataSource marks this
                                        // format as fully buffered, matching the reference client.
                                        videoFormatId = selectedVideoItag,
                                        formatLastModified = selectedLastModified,
                                        videoFormatLastModified = selectedVideoLastModified,
                                        contentLength = selectedContentLength,
                                        durationMs = durationMs,
                                        streamingPoToken = poTokens?.streamingPoToken,
                                        userAgent = playerUa,
                                        visitorData = effectiveVisitorData,
                                        cookies = cookies
                                    )
                                }
                            }
                        }
                    }
                } else {
                    log("  InnerTube request failed: ${response.message}")
                }
            }
        } catch (e: Exception) {
            log("  InnerTube resolution failed with exception: ${e.message}")
            e.printStackTrace()
        }
        null
    }

    suspend fun resolveYoutubeSabrStream(songId: String): YoutubeSabrStreamInfo? = withContext(Dispatchers.IO) {
        if (!songId.startsWith("yt_")) return@withContext null
        resolveStreamUrlViaInnerTube(songId.removePrefix("yt_"))
    }

    suspend fun resolveStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        log("Resolving stream URL for songId: $songId")
        resolutionStatus.value = context.getString(R.string.status_resolving)
        try {
            if (!songId.startsWith("yt_")) {
                log("  songId does not start with yt_")
                return@withContext ""
            }
            val videoId = songId.removePrefix("yt_")
            
            // 0. Try custom InnerTube resolver first (keep fallback system intact as requested)
            try {
                val innerTubeInfo = resolveStreamUrlViaInnerTube(videoId)
                if (innerTubeInfo != null) {
                    log("  InnerTube resolved SABR URL, validating direct stream connectivity...")
                    if (checkUrlWorks(innerTubeInfo.directUrl)) {
                        log("  Successfully resolved and verified via custom InnerTube SABR session")
                        return@withContext innerTubeInfo.directUrl
                    } else {
                        log("  Custom InnerTube URL failed validation check (returned 403 or failed). Skipping...")
                    }
                }
            } catch (e: Exception) {
                log("  Custom InnerTube resolution error: ${e.message}")
                e.printStackTrace()
            }

            // 1. Try NewPipe Extractor first
            try {
                if (!isNewPipeInitialized) {
                    log("  Initializing NewPipe Extractor...")
                    resolutionStatus.value = context.getString(R.string.status_system_preparing)
                    NewPipe.init(DefaultDownloaderImpl.initDefault())
                    isNewPipeInitialized = true
                    log("  NewPipe Extractor initialized successfully.")
                }
            } catch (e: Exception) {
                log("  NewPipe Extractor initialization failed: ${e.message}")
                e.printStackTrace()
            }

            var extractedUrl: String? = null
            log("  Attempting NewPipe extraction for videoId: $videoId")
            resolutionStatus.value = context.getString(R.string.status_resolving_youtube)
            try {
                val service = ServiceList.YouTube
                val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                
                val audioStreams = extractor.audioStreams()
                log("    NewPipe found ${audioStreams?.size ?: 0} audio streams")
                if (!audioStreams.isNullOrEmpty()) {
                    for (audio in audioStreams) {
                        log("      Stream: format=${audio.format}, bitrate=${audio.bitrate}")
                    }
                    val bestAudio = audioStreams.find { it.format?.suffix?.lowercase() == "m4a" }
                        ?: audioStreams.find { it.format?.name?.lowercase() == "m4a" }
                        ?: audioStreams.find { it.format?.suffix?.lowercase() == "webm" }
                        ?: audioStreams.first()
                    
                    extractedUrl = bestAudio.getUrl()
                    log("    Selected NewPipe stream: format=${bestAudio.format}, bitrate=${bestAudio.bitrate}")
                } else {
                    log("    No audio streams found via NewPipe Extractor.")
                }
            } catch (e: Exception) {
                log("    Exception during NewPipe extraction: ${e.message}")
                e.printStackTrace()
            }
            
            if (!extractedUrl.isNullOrBlank()) {
                log("  NewPipe extracted stream, validating connectivity...")
                if (checkUrlWorks(extractedUrl!!)) {
                    log("  Successfully resolved and verified via NewPipe: ${redactUrlForLog(extractedUrl)}")
                    return@withContext extractedUrl!!
                } else {
                    log("  NewPipe URL failed validation check. Skipping...")
                }
            }
            
            // 2. Try active cached base URL first as fallback
            log("  Local extractor failed/not found. Trying active cached Invidious URL...")
            resolutionStatus.value = context.getString(R.string.status_searching_alternative)
            val activeUrl = activeInvidiousBaseUrl
            val activeStreamUrl = "$activeUrl/latest_version?id=$videoId&itag=140&local=true"
            if (checkUrlWorks(activeStreamUrl)) {
                log("  Successfully resolved with active cached URL: ${redactUrlForLog(activeStreamUrl)}")
                return@withContext activeStreamUrl
            }
            
            // 3. Probe other invidious instances in parallel!
            log("  Active URL failed. Probing other Invidious instances in parallel...")
            resolutionStatus.value = context.getString(R.string.status_scanning_servers)
            val workingBase = coroutineScope {
                val deferreds = invidiousInstances.map { base ->
                    async {
                        if (base == activeUrl) null
                        else {
                            val candidateUrl = "$base/latest_version?id=$videoId&itag=140&local=true"
                            if (checkUrlWorks(candidateUrl)) base else null
                        }
                    }
                }
                deferreds.map { it.await() }.firstOrNull { it != null }
            }
            
            if (workingBase != null) {
                activeInvidiousBaseUrl = workingBase
                val resolvedUrl = "$workingBase/latest_version?id=$videoId&itag=140&local=true"
                log("  Successfully resolved with parallel Invidious check: ${redactUrlForLog(resolvedUrl)}")
                return@withContext resolvedUrl
            }
            
            // 4. Fallback: try Cobalt APIs in parallel if all Invidious instances fail
            log("  All Invidious instances failed. Trying Cobalt in parallel...")
            resolutionStatus.value = context.getString(R.string.status_establishing_backup)
            val cobaltApis = listOf(
                "https://rue-cobalt.xenon.zone/",
                "https://cobaltapi.kittycat.boo/"
            )
            val cobaltStreamUrl = coroutineScope {
                val deferreds = cobaltApis.map { api ->
                    async {
                        try {
                            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                            val jsonBody = """{"url":"https://www.youtube.com/watch?v=$videoId","downloadMode":"audio","audioFormat":"mp3"}"""
                            val requestBody = jsonBody.toRequestBody(mediaType)
                            val request = Request.Builder()
                                .url(api)
                                .post(requestBody)
                                .header("Accept", "application/json")
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                                .build()
                            
                            val tempClient = okHttpClient.newBuilder()
                                .connectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .build()
                            
                            tempClient.newCall(request).execute().use { response ->
                                log("  Cobalt request to $api -> code: ${response.code}")
                                if (response.isSuccessful) {
                                    val bodyStr = response.body?.string() ?: ""
                                    val json = com.google.gson.JsonParser().parse(bodyStr).asJsonObject
                                    val status = json.get("status")?.asString ?: ""
                                    val streamUrl = json.get("url")?.asString ?: ""
                                    log("    Cobalt response -> status: $status, streamUrl length: ${streamUrl.length}")
                                    if ((status == "tunnel" || status == "stream" || status == "redirect" || status == "success") && streamUrl.isNotEmpty()) {
                                        streamUrl
                                    } else null
                                } else null
                            }
                        } catch (e: Exception) {
                            log("    Cobalt request to $api failed: ${e.message}")
                            null
                        }
                    }
                }
                deferreds.map { it.await() }.firstOrNull { it != null }
            }
            
            if (cobaltStreamUrl != null) {
                log("  Successfully resolved with Cobalt: ${redactUrlForLog(cobaltStreamUrl)}")
                return@withContext cobaltStreamUrl
            }
            
            // Final fallback: return the original default URL
            val fallbackUrl = "https://inv.thepixora.com/latest_version?id=$videoId&itag=140&local=true"
            log("  All methods failed! Returning final fallback stream")
            return@withContext fallbackUrl
        } finally {
            resolutionStatus.value = ""
        }
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
                    return RecommendationPolicy.filterSearchResults(songs)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

    private fun genreSearchQueryFor(profile: ListeningProfile): String {
        val turkishQuery = when (profile.genre) {
            RecommendationProfileResolver.GENRE_POP -> "popüler türkçe pop"
            RecommendationProfileResolver.GENRE_ROCK -> "popüler türkçe rock"
            RecommendationProfileResolver.GENRE_RAP -> "popüler türkçe rap"
            RecommendationProfileResolver.GENRE_ELECTRONIC -> "popüler türkçe elektronik müzik"
            RecommendationProfileResolver.GENRE_INDIE -> "popüler türkçe alternatif müzik"
            RecommendationProfileResolver.GENRE_FOLK -> "popüler türkçe halk müziği"
            RecommendationProfileResolver.GENRE_RNB -> "popüler türkçe r&b"
            else -> "popüler türkçe müzik"
        }
        val globalQuery = when (profile.genre) {
            RecommendationProfileResolver.GENRE_POP -> "popular pop songs"
            RecommendationProfileResolver.GENRE_ROCK -> "popular rock songs"
            RecommendationProfileResolver.GENRE_RAP -> "popular hip hop songs"
            RecommendationProfileResolver.GENRE_ELECTRONIC -> "popular electronic dance songs"
            RecommendationProfileResolver.GENRE_INDIE -> "popular indie songs"
            RecommendationProfileResolver.GENRE_FOLK -> "popular country folk songs"
            RecommendationProfileResolver.GENRE_RNB -> "popular r&b songs"
            else -> "popular music"
        }
        return if (profile.language == RecommendationProfileResolver.LANGUAGE_TURKISH) {
            turkishQuery
        } else {
            globalQuery
        }
    }

    private suspend fun searchCatalogArtist(profile: ArtistCatalogProfile): List<SongEntity> {
        val cacheKey = RecommendationProfileResolver.normalize(profile.name)
        catalogSearchCache[cacheKey]?.let { return it }

        val result = searchNewPipe("${profile.name} official audio")
        val normalizedArtist = RecommendationProfileResolver.normalize(profile.name)
        val matchingResults = result.filter { song ->
            RecommendationProfileResolver.normalize(song.title).contains(normalizedArtist) ||
                RecommendationProfileResolver.normalize(song.artistName).contains(normalizedArtist)
        }
        val usableResults = (matchingResults.ifEmpty { result }).take(2)
        catalogSearchCache.putIfAbsent(cacheKey, usableResults)
        return usableResults
    }

    suspend fun getRecommendedVideos(
        songId: String,
        excludedIds: Set<String> = emptySet()
    ): List<SongEntity> = withContext(Dispatchers.IO) {
        if (!songId.startsWith("yt_")) return@withContext emptyList()
        val videoId = songId.removePrefix("yt_")
        
        var author: String? = null
        val tags = mutableListOf<String>()
        var songTitle = ""
        
        // 1. Try local extraction of metadata using NewPipe
        try {
            if (!isNewPipeInitialized) {
                log("Initializing NewPipe for getRecommendedVideos...")
                NewPipe.init(DefaultDownloaderImpl.initDefault())
                isNewPipeInitialized = true
            }
            log("Fetching local metadata for recommendations, videoId: $videoId")
            val streamInfo = com.yushosei.newpipe.util.ExtractorHelper.getStreamInfo(
                ServiceList.YouTube.serviceId,
                "https://www.youtube.com/watch?v=$videoId",
                false
            )
            author = streamInfo.uploaderName
            songTitle = streamInfo.name
            val localTags = streamInfo.tags
            if (localTags.isNotEmpty()) {
                for (tag in localTags) {
                    tags.add(tag.lowercase(java.util.Locale.ROOT))
                }
            }
            log("Locally resolved uploader: $author, title: $songTitle, tags count: ${tags.size}")
        } catch (e: Exception) {
            log("Failed to resolve metadata locally: ${e.message}")
            e.printStackTrace()
        }
        
        // Try DB if local uploader/title is blank
        if (songTitle.isBlank() || author.isNullOrBlank()) {
            val dbSong = dao.getSongById(songId)
            if (dbSong != null) {
                if (songTitle.isBlank()) songTitle = dbSong.title
                if (author.isNullOrBlank()) author = dbSong.artistName
            }
        }

        val recommendedList = mutableListOf<SongEntity>()
        
        // 2. Try Invidious to get direct recommended videos list
        val instances = listOf(
            "https://inv.thepixora.com",
            "https://invidious.nerdvpn.de",
            "https://invidious.no-logs.com",
            "https://invidious.projectsegfau.lt",
            "https://yewtu.be"
        )
        
        var invidiousSuccess = false
        for (base in instances) {
            try {
                val url = "$base/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .build()
                
                val tempClient = okHttpClient.newBuilder()
                    .connectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                
                val response = tempClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: continue
                    val jsonObj = com.google.gson.JsonParser().parse(bodyStr).asJsonObject
                    
                    if (author.isNullOrBlank()) {
                        author = jsonObj.get("author")?.let { if (it.isJsonNull) null else it.getAsString() }
                    }
                    if (tags.isEmpty()) {
                        val keywordsJson = jsonObj.get("keywords")
                        if (keywordsJson != null && keywordsJson.isJsonArray) {
                            val arr = keywordsJson.asJsonArray
                            for (j in 0 until arr.size()) {
                                val tag = arr.get(j)?.let { if (it.isJsonNull) null else it.getAsString() }
                                if (tag != null) {
                                    tags.add(tag.lowercase(java.util.Locale.ROOT))
                                }
                            }
                        }
                    }
                    
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
                            val candidateSong = SongEntity(
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

                            if (isSameSongTitle(title, songTitle, author) ||
                                !RecommendationPolicy.isQueueCandidate(candidateSong)
                            ) {
                                continue
                            }

                            recommendedList.add(candidateSong)
                        }
                    }
                    invidiousSuccess = true
                    log("Successfully fetched ${recommendedList.size} recommended videos from Invidious instance: $base")
                    break
                }
            } catch (e: Exception) {
                log("Invidious info query failed for $base: ${e.message}")
            }
        }
        
        // 3. Fallback: If Invidious recommended list is empty/failed, use local NewPipe search
        if (!invidiousSuccess || recommendedList.isEmpty()) {
            log("Invidious failed or returned empty. Using local NewPipe search for recommended fallback...")
            val queryText = if (!author.isNullOrBlank()) "$author popular songs" else songTitle
            if (queryText.isNotBlank()) {
                try {
                    val localRelated = searchNewPipe(queryText).filter { !isSameSongTitle(it.title, songTitle, author) }
                    recommendedList.addAll(localRelated)
                    log("Local search fallback found ${localRelated.size} similar items.")
                } catch (e: Exception) {
                    log("Local search fallback failed: ${e.message}")
                }
            }
        }
        
        // 4. Build a stable genre/language profile, then sample high-popularity
        // artists from the matching catalog bucket. The actual tracks are still
        // resolved live, so the catalog does not lock the app to old song URLs.
        val listeningProfile = artistCatalog.resolveProfile(author, songTitle, tags)
        val genreQuery = genreSearchQueryFor(listeningProfile)
        val artistQuery = author
            ?.takeIf { it != "Unknown Author" && it != "Unknown" }
            ?.let { "$it official audio" }
            .orEmpty()
        val recommendationRandom = kotlin.random.Random(System.nanoTime())
        val historySongs = runCatching { dao.getAllSongs() }.getOrDefault(emptyList())
        val excludedArtistNames = buildSet {
            author?.takeIf { it.isNotBlank() }?.let(::add)
        }
        val catalogArtists = artistCatalog.selectArtists(
            listeningProfile = listeningProfile,
            excludedArtistNames = excludedArtistNames,
            count = 3,
            random = recommendationRandom
        )
        log(
            "Recommendation profile: genre=${listeningProfile.genre}, " +
                "language=${listeningProfile.language}, confidence=${listeningProfile.confidence}, " +
                "catalogArtists=${catalogArtists.joinToString { it.name }}"
        )

        val (artistSongs, genreSongs, catalogSelections) = coroutineScope {
            val artistSongsDeferred = async {
                if (artistQuery.isBlank()) {
                    emptyList()
                } else {
                    runCatching {
                        searchNewPipe(artistQuery)
                            .filter { !isSameSongTitle(it.title, songTitle, author) }
                    }.onFailure { log("Artist query search failed: ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }
            val genreSongsDeferred = async {
                runCatching {
                    searchNewPipe(genreQuery)
                        .filter { !isSameSongTitle(it.title, songTitle, author) }
                }.onFailure { log("Genre query search failed: ${it.message}") }
                    .getOrDefault(emptyList())
            }
            val catalogRequests = catalogArtists.map { catalogArtist ->
                async {
                    catalogArtist to searchCatalogArtist(catalogArtist)
                        .filter { !isSameSongTitle(it.title, songTitle, author) }
                }
            }

            Triple(
                artistSongsDeferred.await(),
                genreSongsDeferred.await(),
                catalogRequests.awaitAll()
            )
        }

        fun candidateFor(
            song: SongEntity,
            source: RecommendationSource,
            popularityBoost: Double = 0.0
        ) = RecommendationCandidate(
            song = song,
            source = source,
            popularityBoost = popularityBoost,
            affinityBoost = affinityStore.boostFor(song)
        )

        val candidates = buildList {
            addAll(recommendedList.map { candidateFor(it, RecommendationSource.RELATED) })
            addAll(artistSongs.map { candidateFor(it, RecommendationSource.ARTIST) })
            addAll(genreSongs.map { candidateFor(it, RecommendationSource.GENRE) })
            catalogSelections.forEach { (catalogArtist, songs) ->
                val popularityBoost = catalogArtist.popularity / 100.0
                addAll(songs.map { song ->
                    candidateFor(song, RecommendationSource.CATALOG, popularityBoost)
                })
            }
            addAll(historySongs.map { song ->
                candidateFor(song, RecommendationSource.HISTORY)
            })
        }

        val finalSongs = RecommendationEngine.select(
            seedId = songId,
            seedTitle = songTitle,
            seedArtist = author,
            candidates = candidates,
            excludedIds = excludedIds,
            limit = 10,
            random = recommendationRandom
        )

        log(
            "Recommendations loaded successfully. Candidate pool=${candidates.size}, " +
                "selected=${finalSongs.size}, excluded=${excludedIds.size}"
        )
        return@withContext finalSongs
    }

    // Local DB Flows
    fun getFavoriteSongsFlow(): Flow<List<SongEntity>> = dao.getFavoriteSongsFlow()
    fun getDownloadedSongsFlow(): Flow<List<SongEntity>> = dao.getDownloadedSongsFlow()
    fun getAllSongsFlow(): Flow<List<SongEntity>> = dao.getAllSongsFlow()
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>> = dao.getAllPlaylistsFlow()
    
    fun getPlaylistWithSongsFlow(playlistId: String): Flow<PlaylistWithSongs?> = 
        dao.getPlaylistWithSongsFlow(playlistId)

    // DB Operations
    fun getSongByIdSync(songId: String): SongEntity? {
        return dao.getSongById(songId)
    }

    suspend fun insertSong(song: SongEntity) = withContext(Dispatchers.IO) {
        dao.insertSong(song.copy(addedAt = System.currentTimeMillis()))
    }

    suspend fun toggleFavorite(song: SongEntity) = withContext(Dispatchers.IO) {
        val existing = dao.getSongById(song.id)
        if (existing == null) {
            dao.insertSong(song.copy(isFavorite = true))
            affinityStore.recordFavorite(song)
        } else {
            val updated = existing.copy(isFavorite = !existing.isFavorite)
            dao.updateSong(updated)
            if (updated.isFavorite) {
                affinityStore.recordFavorite(updated)
            }
        }
    }

    suspend fun recordCompleted(song: SongEntity) = withContext(Dispatchers.IO) {
        affinityStore.recordCompleted(song)
    }

    suspend fun recordQuickSkip(song: SongEntity) = withContext(Dispatchers.IO) {
        affinityStore.recordQuickSkip(song)
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

    private fun streamContentLength(url: String): Long {
        return try {
            android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun contentRangeLength(header: String?): Long {
        val total = header?.substringAfterLast('/', "")?.toLongOrNull() ?: return -1L
        return total.takeIf { it > 0L } ?: -1L
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

            val destFile = File(context.filesDir, "audio_${song.id}.mp3")
            if (song.id.startsWith("yt_")) {
                val sabrInfo = resolveYoutubeSabrStream(song.id)
                if (sabrInfo != null) {
                    if (destFile.exists()) destFile.delete()
                    log("Downloading YouTube audio through SABR: ${song.id}")
                    val downloaded = downloadSabrStream(sabrInfo, destFile, onProgress)
                    if (downloaded) {
                        val updated = (dao.getSongById(song.id) ?: song).copy(
                            isDownloaded = true,
                            localUri = destFile.absolutePath
                        )
                        dao.insertSong(updated)
                        return@withContext true
                    }
                    log("SABR download failed; trying direct compatibility stream")
                    destFile.delete()
                }
            }

            val downloadUrl = if (song.id.startsWith("yt_")) {
                resolveStreamUrl(song.id)
            } else {
                song.audioUrl
            }
            if (downloadUrl.isBlank()) return@withContext false

            var totalLength = streamContentLength(downloadUrl)

            if (destFile.exists()) {
                destFile.delete()
            }

            FileOutputStream(destFile).use { outputStream ->
                var position = 0L
                var finished = false

                while (!finished) {
                    val requestedEnd = if (totalLength > 0L) {
                        minOf(totalLength - 1L, position + STREAM_CHUNK_SIZE - 1L)
                    } else {
                        position + STREAM_CHUNK_SIZE - 1L
                    }
                    val request = Request.Builder()
                        .url(downloadUrl)
                        .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 10; en_US;)")
                        .header("Range", "bytes=$position-$requestedEnd")
                        .build()

                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext false
                        val body = response.body ?: return@withContext false
                        val responseTotal = contentRangeLength(response.header("Content-Range"))
                        if (totalLength <= 0L && responseTotal > 0L) {
                            totalLength = responseTotal
                        }

                        var chunkBytesRead = 0L
                        body.byteStream().use { inputStream ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break
                                if (bytesRead == 0) continue
                                outputStream.write(buffer, 0, bytesRead)
                                chunkBytesRead += bytesRead
                            }
                        }

                        if (chunkBytesRead <= 0L) return@withContext false
                        position += chunkBytesRead
                        if (totalLength > 0L) {
                            onProgress((position.toFloat() / totalLength).coerceIn(0f, 1f))
                        }

                        // A normal 200 response is already the complete resource. For a
                        // ranged response, stop at the advertised length or a short chunk.
                        finished = response.code == 200 ||
                            (totalLength > 0L && position >= totalLength) ||
                            (response.code == 206 && chunkBytesRead < STREAM_CHUNK_SIZE)
                    }
                }
                if (totalLength <= 0L) {
                    onProgress(1f)
                }
            }

            if (!destFile.exists() || destFile.length() <= 0L) {
                return@withContext false
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

    private fun downloadSabrStream(
        streamInfo: YoutubeSabrStreamInfo,
        destination: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val dataSource = YoutubeSabrDataSource(okHttpClient, streamInfo, ::log)
        return try {
            val dataSpec = androidx.media3.datasource.DataSpec(
                Uri.parse("youtube://${streamInfo.videoId}")
            )
            val totalLength = dataSource.open(dataSpec)
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(32 * 1024)
                var position = 0L
                while (true) {
                    val read = dataSource.read(buffer, 0, buffer.size)
                    if (read == androidx.media3.common.C.RESULT_END_OF_INPUT) break
                    if (read <= 0) continue
                    output.write(buffer, 0, read)
                    position += read
                    if (totalLength > 0L) {
                        onProgress((position.toFloat() / totalLength).coerceIn(0f, 1f))
                    }
                }
                output.flush()
            }
            onProgress(1f)
            destination.exists() && destination.length() > 0L
        } catch (e: Exception) {
            log("SABR download failed: ${e.message}")
            false
        } finally {
            dataSource.close()
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

    private fun isSameSongTitle(candidateTitle: String, seedTitle: String, author: String?): Boolean {
        fun cleanTitle(title: String): String {
            var t = title.lowercase(java.util.Locale.ROOT)
            if (!author.isNullOrBlank()) {
                val lowerAuthor = author.lowercase(java.util.Locale.ROOT)
                t = t.replace(lowerAuthor, "")
            }
            t = t.replace(Regex("\\([^)]*\\)"), "")
            t = t.replace(Regex("\\[[^]]*\\]"), "")
            val removals = listOf(
                "official", "video", "audio", "lyrics", "clip", "klip", "müzik", "music",
                "vevo", "hd", "4k", "remix", "cover", "live", "konser", "acoustic", "akustik",
                "karaoke", "instrumental", "enstrümantal", "slowed", "speed", "reverb", "loop",
                "hour", "original", "orijinal"
            )
            for (word in removals) {
                t = t.replace(word, "")
            }
            t = t.replace(Regex("[^a-zA-Z0-9\\sıişğüöç\\d]"), " ")
            return t.trim()
        }

        val cleanSeed = cleanTitle(seedTitle)
        val cleanCandidate = cleanTitle(candidateTitle)

        val seedWords = cleanSeed.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length > 2 }

        if (seedWords.isEmpty()) return false

        val candidateWords = cleanCandidate.split(Regex("\\s+")).map { it.trim() }
        val matchesAll = seedWords.all { word ->
            candidateWords.any { cWord -> cWord.contains(word) || word.contains(cWord) }
        }
        
        return matchesAll
    }

    fun log(message: String) {
        try {
            val file = java.io.File(context.filesDir, "app_debug_logs.txt")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            file.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
