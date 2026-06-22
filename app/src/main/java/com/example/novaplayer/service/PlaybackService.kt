package com.example.novaplayer.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri

class PlaybackService : MediaLibraryService() {

    companion object {
        private var cache: SimpleCache? = null
        private var databaseProvider: StandaloneDatabaseProvider? = null

        @Synchronized
        fun getCache(context: android.content.Context): SimpleCache {
            if (cache == null) {
                val cacheDir = File(context.cacheDir, "media_cache")
                val evictor = LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024) // 100 MB cache size
                databaseProvider = StandaloneDatabaseProvider(context)
                cache = SimpleCache(cacheDir, evictor, databaseProvider!!)
            }
            return cache!!
        }
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaLibrarySession? = null

    private val repository by lazy { com.example.novaplayer.data.repository.MusicRepository(applicationContext) }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootMetadata = MediaMetadata.Builder()
                .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                .setIsPlayable(false)
                .build()
            val rootItem = MediaItem.Builder()
                .setMediaId("[root]")
                .setMediaMetadata(rootMetadata)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    val items = when (parentId) {
                        "[root]" -> {
                            listOf(
                                MediaItem.Builder()
                                    .setMediaId("favorites")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("Favoriler")
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                            .setIsPlayable(false)
                                            .build()
                                    )
                                    .build(),
                                MediaItem.Builder()
                                    .setMediaId("downloads")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("İndirilenler")
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                            .setIsPlayable(false)
                                            .build()
                                    )
                                    .build(),
                                MediaItem.Builder()
                                    .setMediaId("playlists")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle("Oynatma Listeleri")
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                            .setIsPlayable(false)
                                            .build()
                                    )
                                    .build()
                            )
                        }
                        "favorites" -> {
                            val favorites = repository.getFavoriteSongsFlow().first()
                            favorites.map { song ->
                                MediaItem.Builder()
                                    .setMediaId(song.id)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(song.title)
                                            .setArtist(song.artistName)
                                            .setArtworkUri(song.albumImageUrl?.let { android.net.Uri.parse(it) })
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                            .setIsPlayable(true)
                                            .build()
                                    )
                                    .build()
                            }
                        }
                        "downloads" -> {
                            val downloads = repository.getDownloadedSongsFlow().first()
                            downloads.map { song ->
                                MediaItem.Builder()
                                    .setMediaId(song.id)
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(song.title)
                                            .setArtist(song.artistName)
                                            .setArtworkUri(song.albumImageUrl?.let { android.net.Uri.parse(it) })
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                            .setIsPlayable(true)
                                            .build()
                                    )
                                    .build()
                            }
                        }
                        "playlists" -> {
                            val playlists = repository.getAllPlaylistsFlow().first()
                            playlists.map { playlist ->
                                MediaItem.Builder()
                                    .setMediaId("playlist_${playlist.playlistId}")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(playlist.name)
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                            .setIsPlayable(false)
                                            .build()
                                    )
                                    .build()
                            }
                        }
                        else -> {
                            if (parentId.startsWith("playlist_")) {
                                val playlistId = parentId.removePrefix("playlist_")
                                val playlistWithSongs = repository.getPlaylistWithSongsFlow(playlistId).first()
                                playlistWithSongs?.songs?.map { song ->
                                    MediaItem.Builder()
                                        .setMediaId(song.id)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(song.title)
                                                .setArtist(song.artistName)
                                                .setArtworkUri(song.albumImageUrl?.let { android.net.Uri.parse(it) })
                                                .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                                .setIsPlayable(true)
                                                .build()
                                        )
                                        .build()
                                } ?: emptyList()
                            } else {
                                emptyList()
                            }
                        }
                    }
                    future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val future = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    val dbSong = withContext(Dispatchers.IO) { repository.getSongByIdSync(mediaId) }
                    if (dbSong != null) {
                        val item = MediaItem.Builder()
                            .setMediaId(dbSong.id)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(dbSong.title)
                                    .setArtist(dbSong.artistName)
                                    .setArtworkUri(dbSong.albumImageUrl?.let { android.net.Uri.parse(it) })
                                    .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                                    .setIsPlayable(true)
                                    .build()
                            )
                            .build()
                        future.set(LibraryResult.ofItem(item, null))
                    } else {
                        future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
                    }
                } catch (e: Exception) {
                    future.set(LibraryResult.ofError(LibraryResult.RESULT_ERROR_UNKNOWN))
                }
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                val resolvedItems = withContext(Dispatchers.IO) {
                    val list = mutableListOf<MediaItem>()
                    for (item in mediaItems) {
                        val uri = item.localConfiguration?.uri?.toString()
                        val isResolved = !uri.isNullOrEmpty()
                        if (isResolved) {
                            list.add(item)
                        } else {
                            val songId = item.mediaId
                            val dbSong = repository.getSongByIdSync(songId)
                            val playUri = if (dbSong?.isDownloaded == true && dbSong.localUri != null) {
                                repository.log("PlaybackService: Playing downloaded local file: ${dbSong.localUri}")
                                if (dbSong.localUri.startsWith("/")) {
                                    "file://${dbSong.localUri}"
                                } else {
                                    dbSong.localUri
                                }
                            } else {
                                if (songId.startsWith("yt_")) {
                                    "youtube://$songId"
                                } else {
                                    ""
                                }
                            }
                            if (playUri.isNotEmpty()) {
                                list.add(
                                    item.buildUpon()
                                        .setUri(android.net.Uri.parse(playUri))
                                        .build()
                                )
                            } else {
                                list.add(item)
                            }
                        }
                    }
                    list
                }
                future.set(resolvedItems)
            }
            return future
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(repository.okHttpClient)
            .setUserAgent("com.google.android.youtube/20.10.38 (Linux; U; Android 10; en_US;)")
            .setDefaultRequestProperties(mapOf(
                "Range" to "bytes=0-"
            ))

        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache(this))
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val resolvingDataSourceFactory = ResolvingDataSourceFactory(repository, cacheDataSourceFactory)
            
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(resolvingDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        player?.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val currentItem = player?.currentMediaItem
                val mediaUri = currentItem?.localConfiguration?.uri?.toString() ?: "null"
                repository.log("PlaybackService [EXOPLAYER ERROR]: ${error.message} (errorCode: ${error.errorCode}, name: ${error.errorCodeName}, mediaId: ${currentItem?.mediaId}, uri: $mediaUri)")
                val cause = error.cause
                if (cause != null) {
                    repository.log("  Cause: ${cause.message}")
                    val sw = java.io.StringWriter()
                    cause.printStackTrace(java.io.PrintWriter(sw))
                    repository.log("  Stacktrace:\n$sw")
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when(playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                    androidx.media3.common.Player.STATE_READY -> "READY"
                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                repository.log("PlaybackService [PLAYBACK STATE]: $stateName")
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                repository.log("PlaybackService [MEDIA ITEM TRANSITION]: mediaId=${mediaItem?.mediaId}, reason=$reason")
            }
        })

        val intent = Intent(this, com.example.novaplayer.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        player?.let { p ->
            mediaSession = MediaLibrarySession.Builder(this, p, librarySessionCallback)
                .setSessionActivity(pendingIntent)
                .build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null) {
            if (player.playWhenReady) {
                return
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

@UnstableApi
class ResolvingDataSource(
    private val repository: com.example.novaplayer.data.repository.MusicRepository,
    private val delegate: DataSource
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val scheme = uri.scheme
        val resolvedDataSpec = if (scheme == "youtube") {
            val songId = uri.host ?: uri.lastPathSegment ?: ""
            repository.log("ResolvingDataSource: Intercepted placeholder URI: $uri, songId: $songId")
            
            // Resolve the stream URL synchronously on ExoPlayer's loading background thread
            val resolvedUrl = kotlinx.coroutines.runBlocking {
                repository.resolveStreamUrl(songId)
            }
            repository.log("ResolvingDataSource: Resolved stream URL: $resolvedUrl")
            
            if (resolvedUrl.isNotEmpty()) {
                dataSpec.buildUpon()
                    .setUri(Uri.parse(resolvedUrl))
                    .build()
            } else {
                repository.log("ResolvingDataSource: Failed to resolve stream URL for $songId")
                dataSpec
            }
        } else {
            dataSpec
        }
        return delegate.open(resolvedDataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate.read(buffer, offset, length)
    }

    override fun getUri(): Uri? {
        return delegate.getUri()
    }

    override fun close() {
        delegate.close()
    }
}

@UnstableApi
class ResolvingDataSourceFactory(
    private val repository: com.example.novaplayer.data.repository.MusicRepository,
    private val delegateFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return ResolvingDataSource(repository, delegateFactory.createDataSource())
    }
}
