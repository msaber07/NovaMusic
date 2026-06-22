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

class MusicRepository(private val context: Context) {

    companion object {
        @Volatile
        private var isNewPipeInitialized = false
        
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
        log("Testing URL: $url")
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
                log("  URL $url -> code: ${response.code}, Content-Type: $contentType, works: $works")
                works
            }
        } catch (e: Exception) {
            log("  URL $url -> failed with exception: ${e.message}")
            false
        }
    }

    private var popularHits: com.google.gson.JsonObject? = null

    private fun loadPopularHits() {
        if (popularHits != null) return
        try {
            context.assets.open("popular_hits.json").use { inputStream ->
                val reader = java.io.InputStreamReader(inputStream)
                popularHits = com.google.gson.JsonParser().parse(reader).asJsonObject
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val okHttpClient = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
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
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

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
            log("NewPipe search found ${songs.size} results.")
            return@withContext songs
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
                emit(localSongs)
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

    private suspend fun resolveStreamUrlViaInnerTube(videoId: String): String? = withContext(Dispatchers.IO) {
        log("resolveStreamUrlViaInnerTube: Resolving $videoId via InnerTube ANDROID_VR API...")
        try {
            val (visitorData, cookies) = fetchVisitorDataAndCookies(videoId)
            
            val url = "https://www.youtube.com/youtubei/v1/player"
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            
            // Construct context JSON
            val visitorDataField = if (visitorData != null) ",\"visitorData\": \"$visitorData\"" else ""
            
            val jsonPayload = """
                {
                    "videoId": "$videoId",
                    "context": {
                        "client": {
                            "clientName": "ANDROID_VR",
                            "clientVersion": "1.65.10",
                            "deviceMake": "Oculus",
                            "deviceModel": "Quest 3",
                            "androidSdkVersion": 32,
                            "osName": "Android",
                            "osVersion": "12L",
                            "hl": "en",
                            "gl": "US",
                            "utcOffsetMinutes": 0
                            $visitorDataField
                        }
                    }
                }
            """.trimIndent()
            
            val requestBody = jsonPayload.toRequestBody(mediaType)
            val playerUa = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .header("User-Agent", playerUa)
                .header("X-YouTube-Client-Name", "28")
                .header("X-YouTube-Client-Version", "1.65.10")
                .header("Origin", "https://www.youtube.com")
                .header("Accept", "application/json")
                
            if (visitorData != null) {
                requestBuilder.header("X-Goog-Visitor-Id", visitorData)
            }
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
                        
                        val playabilityStatus = json.getAsJsonObject("playabilityStatus")
                        val status = playabilityStatus?.get("status")?.asString
                        if (status != null && status != "OK") {
                            val reason = playabilityStatus.get("reason")?.asString ?: "Unknown restriction"
                            log("  InnerTube playback check: Video playability status is $status ($reason)")
                        }
                        
                        val streamingData = json.getAsJsonObject("streamingData")
                        if (streamingData != null) {
                            val adaptiveFormats = streamingData.getAsJsonArray("adaptiveFormats")
                            if (adaptiveFormats != null && adaptiveFormats.size() > 0) {
                                var selectedUrl: String? = null
                                
                                for (element in adaptiveFormats) {
                                    val fmt = element.asJsonObject
                                    val mimeType = fmt.get("mimeType")?.asString ?: ""
                                    if (mimeType.contains("audio")) {
                                        val streamUrl = fmt.get("url")?.asString
                                        if (!streamUrl.isNullOrEmpty()) {
                                            selectedUrl = streamUrl
                                            log("  InnerTube found audio stream: mimeType=$mimeType, bitrate=${fmt.get("bitrate")?.asInt}")
                                            if (mimeType.contains("audio/mp4") || mimeType.contains("audio/m4a")) {
                                                break
                                            }
                                        }
                                    }
                                }
                                
                                if (selectedUrl != null) {
                                    log("  InnerTube successfully resolved url (length: ${selectedUrl.length})")
                                    return@withContext selectedUrl
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

    suspend fun resolveStreamUrl(songId: String): String = withContext(Dispatchers.IO) {
        log("Resolving stream URL for songId: $songId")
        resolutionStatus.value = "Çözümleniyor..."
        try {
            if (!songId.startsWith("yt_")) {
                log("  songId does not start with yt_")
                return@withContext ""
            }
            val videoId = songId.removePrefix("yt_")
            
            // 0. Try custom InnerTube resolver first (keep fallback system intact as requested)
            try {
                val innerTubeUrl = resolveStreamUrlViaInnerTube(videoId)
                if (!innerTubeUrl.isNullOrBlank()) {
                    log("  InnerTube resolved URL: $innerTubeUrl, validating stream connectivity...")
                    if (checkUrlWorks(innerTubeUrl)) {
                        log("  Successfully resolved and verified via custom InnerTube: $innerTubeUrl")
                        return@withContext innerTubeUrl
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
                    resolutionStatus.value = "Sistem hazırlanıyor..."
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
            resolutionStatus.value = "YouTube'dan çözümleniyor..."
            try {
                val service = ServiceList.YouTube
                val extractor = service.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                
                val audioStreams = extractor.audioStreams()
                log("    NewPipe found ${audioStreams?.size ?: 0} audio streams")
                if (!audioStreams.isNullOrEmpty()) {
                    for (audio in audioStreams) {
                        log("      Stream: format=${audio.format}, bitrate=${audio.bitrate}, url=${audio.getUrl()?.take(30)}...")
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
                log("  NewPipe extracted URL: $extractedUrl, validating stream connectivity...")
                if (checkUrlWorks(extractedUrl!!)) {
                    log("  Successfully resolved and verified via NewPipe: $extractedUrl")
                    return@withContext extractedUrl!!
                } else {
                    log("  NewPipe URL failed validation check. Skipping...")
                }
            }
            
            // 2. Try active cached base URL first as fallback
            log("  Local extractor failed/not found. Trying active cached Invidious URL...")
            resolutionStatus.value = "Alternatif sunucu aranıyor..."
            val activeUrl = activeInvidiousBaseUrl
            val activeStreamUrl = "$activeUrl/latest_version?id=$videoId&itag=140&local=true"
            if (checkUrlWorks(activeStreamUrl)) {
                log("  Successfully resolved with active cached URL: $activeStreamUrl")
                return@withContext activeStreamUrl
            }
            
            // 3. Probe other invidious instances in parallel!
            log("  Active URL failed. Probing other Invidious instances in parallel...")
            resolutionStatus.value = "Sunucular taranıyor..."
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
                log("  Successfully resolved with parallel Invidious check: $resolvedUrl")
                return@withContext resolvedUrl
            }
            
            // 4. Fallback: try Cobalt APIs in parallel if all Invidious instances fail
            log("  All Invidious instances failed. Trying Cobalt in parallel...")
            resolutionStatus.value = "Yedek tünel kuruluyor..."
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
                log("  Successfully resolved with Cobalt: $cobaltStreamUrl")
                return@withContext cobaltStreamUrl
            }
            
            // Final fallback: return the original default URL
            val fallbackUrl = "https://inv.thepixora.com/latest_version?id=$videoId&itag=140&local=true"
            log("  All methods failed! Returning final fallback URL: $fallbackUrl")
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
                    
                    // Extract author and tags from Invidious if local failed
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
                    
                    // Parse direct recommended videos
                    val recommendedJson = jsonObj.get("recommendedVideos")
                    if (recommendedJson != null && recommendedJson.isJsonArray) {
                        val jsonArray = recommendedJson.asJsonArray
                        for (i in 0 until jsonArray.size()) {
                            val dto = jsonArray.get(i).asJsonObject
                            val recVideoId = dto.get("videoId")?.let { if (it.isJsonNull) null else it.getAsString() } ?: continue
                            val title = dto.get("title")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Title"
                            val artist = dto.get("author")?.let { if (it.isJsonNull) null else it.getAsString() } ?: "Unknown Author"
                            val lengthSeconds = dto.get("lengthSeconds")?.let { if (it.isJsonNull) 0 else it.getAsInt() } ?: 0
                            
                            if (isSameSongTitle(title, songTitle, author)) {
                                continue
                            }
                            
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
        
        // 4. Detect genre and language
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
        
        // Fetch artist songs and genre popular songs using curated popular hits database
        loadPopularHits()
        
        val (artistSongs, genreSongs) = coroutineScope {
            // A. Get artist queries from database if available
            val dbArtistSongsDeferred = async {
                val queries = mutableListOf<String>()
                if (!author.isNullOrEmpty() && author != "Unknown Author" && author != "Unknown") {
                    val lowerAuthor = author.lowercase(java.util.Locale.ROOT)
                    val artistsObj = popularHits?.getAsJsonObject("artists")
                    if (artistsObj != null) {
                        val matchedKey = artistsObj.keySet().find { key ->
                            lowerAuthor.contains(key) || key.contains(lowerAuthor)
                        }
                        if (matchedKey != null) {
                            val arr = artistsObj.getAsJsonArray(matchedKey)
                            for (j in 0 until arr.size()) {
                                val obj = arr.get(j).asJsonObject
                                obj.get("query")?.asString?.let { queries.add(it) }
                            }
                        }
                    }
                }
                
                // Query them in parallel locally via NewPipe
                val results = queries.map { q ->
                    async { searchNewPipe(q).firstOrNull { !isSameSongTitle(it.title, songTitle, author) } }
                }.mapNotNull { it.await() }
                
                // If we didn't find enough or didn't have any queries, fall back to keyword search
                if (results.size < 3 && artistQuery.isNotEmpty()) {
                    val fallback = searchNewPipe(artistQuery).filter { !isSameSongTitle(it.title, songTitle, author) }
                    (results + fallback).distinctBy { it.id }
                } else {
                    results
                }
            }
            
            // B. Get genre queries from database if available
            val dbGenreSongsDeferred = async {
                val queries = mutableListOf<String>()
                val genresObj = popularHits?.getAsJsonObject("genres")
                if (genresObj != null && !mappedGenre.isNullOrEmpty()) {
                    val genreKey = when {
                        mappedGenre.contains("pop") -> if (isTurkish) "pop_tr" else "pop_global"
                        mappedGenre.contains("rap") || mappedGenre.contains("hip hop") || mappedGenre.contains("trap") -> if (isTurkish) "rap_tr" else "rap_global"
                        mappedGenre.contains("rock") || mappedGenre.contains("metal") || mappedGenre.contains("alternative") -> if (isTurkish) "rock_tr" else "rock_global"
                        else -> null
                    }
                    if (genreKey != null && genresObj.has(genreKey)) {
                        val arr = genresObj.getAsJsonArray(genreKey)
                        val list = mutableListOf<String>()
                        for (j in 0 until arr.size()) {
                            val obj = arr.get(j).asJsonObject
                            obj.get("query")?.asString?.let { list.add(it) }
                        }
                        list.shuffle()
                        queries.addAll(list.take(8))
                    }
                }
                
                // Query them in parallel locally via NewPipe
                val results = queries.map { q ->
                    async { searchNewPipe(q).firstOrNull { !isSameSongTitle(it.title, songTitle, author) } }
                }.mapNotNull { it.await() }
                
                // If we didn't find enough or didn't have any queries, fall back to keyword search
                if (results.size < 5 && genreQuery.isNotEmpty()) {
                    val fallback = searchNewPipe(genreQuery).filter { !isSameSongTitle(it.title, songTitle, author) }
                    (results + fallback).distinctBy { it.id }
                } else {
                    results
                }
            }
            
            Pair(dbArtistSongsDeferred.await(), dbGenreSongsDeferred.await())
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

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 10; en_US;)")
                .header("Range", "bytes=0-")
                .build()
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
