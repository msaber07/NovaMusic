package com.example.novaplayer.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
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
                    mediaItems.map { item ->
                        val uri = item.localConfiguration?.uri?.toString()
                        val isResolved = !uri.isNullOrEmpty()
                        if (isResolved) {
                            item
                        } else {
                            val songId = item.mediaId
                            val dbSong = repository.getSongByIdSync(songId)
                            val playUri = if (dbSong?.isDownloaded == true && dbSong.localUri != null) {
                                dbSong.localUri
                            } else {
                                if (songId.startsWith("yt_")) {
                                    val videoId = songId.removePrefix("yt_")
                                    "https://inv.thepixora.com/latest_version?id=$videoId&itag=140&local=true"
                                } else {
                                    ""
                                }
                            }
                            if (playUri.isNotEmpty()) {
                                item.buildUpon()
                                    .setUri(android.net.Uri.parse(playUri))
                                    .build()
                            } else {
                                item
                            }
                        }
                    }.toMutableList()
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

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache(this))
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

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
