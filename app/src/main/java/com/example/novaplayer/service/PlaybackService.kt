package com.example.novaplayer.service

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.LibraryResult
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.SimpleBitmapLoader
import com.example.novaplayer.data.local.SongEntity
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
import com.example.novaplayer.R

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

    private var player: Player? = null
    private var mediaSession: MediaLibrarySession? = null

    /** Last browsable parent Auto/car requested children for; used to expand single-item play into a queue. */
    @Volatile
    private var lastBrowsedParentId: String? = null

    private val repository by lazy { com.example.novaplayer.data.repository.MusicRepository(applicationContext) }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val librarySessionCallback = object : MediaLibrarySession.Callback {

        /**
         * Android Auto / car dual-screen UIs often connect as untrusted Media3
         * controllers. The default policy can leave them browse-only.
         *
         * Important: do NOT grant session.player.availableCommands here. That
         * snapshot is frozen for the life of the connection. If Auto connects
         * before a multi-item queue is loaded, COMMAND_SEEK_TO_NEXT /
         * COMMAND_SEEK_TO_PREVIOUS are missing and never reappear — so the car
         * UI only shows play/pause. Grant DEFAULT_PLAYER_COMMANDS (which
         * includes skip next/previous); Media3 intersects them with the
         * player's live availableCommands as the queue changes.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            repository.log(
                "PlaybackService: granting DEFAULT player commands (" +
                    "${playerCommands.size()}, includes skip next/previous) to " +
                    "${controller.packageName}"
            )
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

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
                                            .setTitle(getString(R.string.favorites))
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                            .setIsPlayable(false)
                                            .build()
                                    )
                                    .build(),
                                MediaItem.Builder()
                                    .setMediaId("downloads")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(getString(R.string.downloads))
                                            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
                                            .setIsPlayable(false)
                                            .build()
                                    )
                                    .build(),
                                MediaItem.Builder()
                                    .setMediaId("playlists")
                                    .setMediaMetadata(
                                        MediaMetadata.Builder()
                                            .setTitle(getString(R.string.playlists))
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
                    if (parentId == "favorites" || parentId == "downloads" || parentId.startsWith("playlist_")) {
                        lastBrowsedParentId = parentId
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
                    resolveMediaItemsList(mediaItems)
                }
                future.set(resolvedItems)
            }
            return future
        }

        /**
         * Android Auto / car hosts request a SINGLE browsed item. Without expanding
         * that into the parent folder playlist, ExoPlayer has a 1-item timeline and
         * next/previous controls stay hidden — even when DEFAULT_PLAYER_COMMANDS and
         * AlwaysSkipEnabledPlayer advertise skip. Phone-start works because
         * MusicViewModel.play() already sets a full queue.
         */
        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        if (mediaItems.size == 1) {
                            val requested = mediaItems.first()
                            val parentId = findParentContainingSong(requested.mediaId)
                            if (parentId != null) {
                                val songs = loadSongsForParent(parentId)
                                if (songs.isNotEmpty()) {
                                    val playlist = songs.map { songToMediaItem(it) }
                                    val index = songs.indexOfFirst { it.id == requested.mediaId }
                                        .coerceAtLeast(0)
                                    repository.log(
                                        "PlaybackService: onSetMediaItems expanded ${requested.mediaId} " +
                                            "via parent=$parentId to ${playlist.size} items, " +
                                            "startIndex=$index (from ${controller.packageName})"
                                    )
                                    return@withContext MediaSession.MediaItemsWithStartPosition(
                                        playlist,
                                        index,
                                        startPositionMs
                                    )
                                }
                            }
                        }
                        val resolved = resolveMediaItemsList(mediaItems)
                        repository.log(
                            "PlaybackService: onSetMediaItems resolved ${resolved.size} item(s), " +
                                "startIndex=$startIndex (from ${controller.packageName})"
                        )
                        MediaSession.MediaItemsWithStartPosition(
                            resolved,
                            startIndex,
                            startPositionMs
                        )
                    }
                    future.set(result)
                } catch (e: Exception) {
                    repository.log("PlaybackService: onSetMediaItems failed: ${e.message}")
                    future.setException(e)
                }
            }
            return future
        }
    }

    private suspend fun loadSongsForParent(parentId: String): List<SongEntity> {
        return when {
            parentId == "favorites" -> repository.getFavoriteSongsFlow().first()
            parentId == "downloads" -> repository.getDownloadedSongsFlow().first()
            parentId.startsWith("playlist_") -> {
                val playlistId = parentId.removePrefix("playlist_")
                repository.getPlaylistWithSongsFlow(playlistId).first()?.songs ?: emptyList()
            }
            else -> emptyList()
        }
    }

    private suspend fun findParentContainingSong(songId: String): String? {
        lastBrowsedParentId?.let { parent ->
            val songs = loadSongsForParent(parent)
            if (songs.any { it.id == songId }) return parent
        }
        if (repository.getFavoriteSongsFlow().first().any { it.id == songId }) {
            return "favorites"
        }
        if (repository.getDownloadedSongsFlow().first().any { it.id == songId }) {
            return "downloads"
        }
        val playlists = repository.getAllPlaylistsFlow().first()
        for (playlist in playlists) {
            val withSongs = repository.getPlaylistWithSongsFlow(playlist.playlistId).first()
            if (withSongs?.songs?.any { it.id == songId } == true) {
                return "playlist_${playlist.playlistId}"
            }
        }
        return null
    }

    private fun resolvePlayUri(song: SongEntity): String {
        return if (song.isDownloaded && song.localUri != null) {
            repository.log("PlaybackService: Playing downloaded local file: ${song.localUri}")
            if (song.localUri.startsWith("/")) {
                "file://${song.localUri}"
            } else {
                song.localUri
            }
        } else if (song.id.startsWith("yt_")) {
            "youtube://${song.id}"
        } else if (song.audioUrl.isNotEmpty()) {
            song.audioUrl
        } else {
            ""
        }
    }

    private fun songToMediaItem(song: SongEntity): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(song.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artistName)
                    .setArtworkUri(song.albumImageUrl?.let { Uri.parse(it) })
                    .setFolderType(MediaMetadata.FOLDER_TYPE_NONE)
                    .setIsPlayable(true)
                    .build()
            )
        val playUri = resolvePlayUri(song)
        if (playUri.isNotEmpty()) {
            builder.setUri(Uri.parse(playUri))
        }
        return builder.build()
    }

    private fun resolveMediaItemsList(mediaItems: List<MediaItem>): MutableList<MediaItem> {
        val list = mutableListOf<MediaItem>()
        for (item in mediaItems) {
            val uri = item.localConfiguration?.uri?.toString()
            if (!uri.isNullOrEmpty()) {
                list.add(item)
                continue
            }
            val songId = item.mediaId
            val dbSong = repository.getSongByIdSync(songId)
            if (dbSong != null) {
                list.add(songToMediaItem(dbSong))
            } else {
                val playUri = if (songId.startsWith("yt_")) "youtube://$songId" else ""
                if (playUri.isNotEmpty()) {
                    list.add(item.buildUpon().setUri(Uri.parse(playUri)).build())
                } else {
                    list.add(item)
                }
            }
        }
        return list
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

        val defaultDataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(getCache(this))
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val resolvingDataSourceFactory = ResolvingDataSourceFactory(repository, cacheDataSourceFactory)
            
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(resolvingDataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        // Advertise skip next/previous to car / notification controllers even when
        // ExoPlayer would temporarily hide them (e.g. single-item or end-of-queue).
        player = AlwaysSkipEnabledPlayer(exoPlayer)

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
                // Load remote artwork so Android Auto / car now-playing shows cover art.
                .setBitmapLoader(CacheBitmapLoader(SimpleBitmapLoader()))
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

/**
 * Keeps ACTION_SKIP_TO_NEXT / ACTION_SKIP_TO_PREVIOUS visible to Android Auto and
 * other MediaSession controllers. ExoPlayer only exposes those commands when a
 * neighboring queue item exists; car UIs then hide the buttons. This wrapper
 * always advertises them and routes to the real queue skip (or restart).
 */
@OptIn(UnstableApi::class)
private class AlwaysSkipEnabledPlayer(
    private val exoPlayer: ExoPlayer
) : ForwardingPlayer(exoPlayer) {

    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands()
            .buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(@Player.Command command: Int): Boolean {
        return availableCommands.contains(command)
    }

    override fun seekToNext() {
        if (hasNextMediaItem()) {
            seekToNextMediaItem()
        }
    }

    override fun seekToPrevious() {
        if (isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) &&
            currentPosition > PREV_RESTART_THRESHOLD_MS
        ) {
            seekTo(0)
        } else if (hasPreviousMediaItem()) {
            seekToPreviousMediaItem()
        } else if (isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            seekTo(0)
        }
    }

    override fun seekToNextMediaItem() {
        if (hasNextMediaItem()) {
            exoPlayer.seekToNextMediaItem()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (hasPreviousMediaItem()) {
            exoPlayer.seekToPreviousMediaItem()
        }
    }

    private companion object {
        const val PREV_RESTART_THRESHOLD_MS = 3000L
    }
}

@UnstableApi
class ResolvingDataSource(
    private val repository: com.example.novaplayer.data.repository.MusicRepository,
    private val delegate: DataSource
) : DataSource {

    companion object {
        // YouTube currently rejects open-ended and multi-megabyte ranges for many direct
        // googlevideo URLs. Keep each request comfortably below the observed 1 MiB limit.
        private const val YOUTUBE_CHUNK_SIZE = 512L * 1024L
    }

    private var chunkedYoutube = false
    private var delegateOpen = false
    private var baseDataSpec: DataSpec? = null
    private var resolvedUri: Uri? = null
    private var currentPosition = 0L
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var currentChunkRemaining = 0L
    private var sabrDataSource: com.example.novaplayer.data.repository.YoutubeSabrDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        sabrDataSource?.close()
        sabrDataSource = null
        closeDelegate()
        chunkedYoutube = false
        baseDataSpec = null
        resolvedUri = null
        currentPosition = 0L
        totalLength = C.LENGTH_UNSET.toLong()
        currentChunkRemaining = 0L

        val uri = dataSpec.uri
        val scheme = uri.scheme
        if (scheme != "youtube") {
            delegateOpen = true
            return delegate.open(dataSpec)
        }

        val songId = uri.host ?: uri.lastPathSegment ?: ""
        var directFallbackUrl: String? = null
        try {
            val sabrInfo = kotlinx.coroutines.runBlocking {
                repository.resolveYoutubeSabrStream(songId)
            }
            if (sabrInfo != null) {
                directFallbackUrl = sabrInfo.directUrl
                val source = com.example.novaplayer.data.repository.YoutubeSabrDataSource(
                    repository.okHttpClient,
                    sabrInfo,
                    repository::log
                )
                try {
                    val length = source.open(dataSpec)
                    sabrDataSource = source
                    repository.log("ResolvingDataSource: YouTube SABR audio stream opened for $songId")
                    return length
                } catch (e: Exception) {
                    source.close()
                    repository.log("ResolvingDataSource: SABR open failed, using direct fallback: ${e.message}")
                }
            }
        } catch (e: Exception) {
            repository.log("ResolvingDataSource: SABR resolution failed, using direct fallback: ${e.message}")
        }

        run {
            repository.log("ResolvingDataSource: Intercepted placeholder URI: $uri, songId: $songId")

            // Resolve the stream URL synchronously on ExoPlayer's loading background thread
            val resolvedUrl = directFallbackUrl ?: kotlinx.coroutines.runBlocking {
                repository.resolveStreamUrl(songId)
            }
            repository.log("ResolvingDataSource: Resolved stream URL: ${repository.redactUrlForLog(resolvedUrl)}")

            if (resolvedUrl.isEmpty()) {
                repository.log("ResolvingDataSource: Failed to resolve stream URL for $songId")
                throw java.io.IOException("Unable to resolve YouTube stream for $songId")
            }

            val uri = Uri.parse(resolvedUrl)
            chunkedYoutube = true
            baseDataSpec = dataSpec
            resolvedUri = uri
            currentPosition = dataSpec.position
            totalLength = uri.getQueryParameter("clen")?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: C.LENGTH_UNSET.toLong()
            dataSpec.buildUpon().setUri(uri).build()
        }

        // Open the first finite range now so ExoPlayer receives a real stream length and
        // failures are reported from open(), as expected by Media3.
        if (!openNextChunk()) {
            throw java.io.IOException("Unable to open YouTube stream")
        }

        val remaining = if (totalLength != C.LENGTH_UNSET.toLong()) {
            (totalLength - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
        repository.log(
            "ResolvingDataSource: Using finite YouTube ranges, " +
                "position=${dataSpec.position}, length=$remaining"
        )
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        sabrDataSource?.let { return it.read(buffer, offset, length) }
        if (!chunkedYoutube) {
            return delegate.read(buffer, offset, length)
        }

        while (true) {
            if (currentChunkRemaining <= 0L) {
                if (!openNextChunk()) {
                    return C.RESULT_END_OF_INPUT
                }
            }

            val readLength = minOf(length.toLong(), currentChunkRemaining).toInt()
            val bytesRead = delegate.read(buffer, offset, readLength)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                closeDelegate()
                currentChunkRemaining = 0L
                return C.RESULT_END_OF_INPUT
            }
            if (bytesRead > 0) {
                currentPosition += bytesRead
                currentChunkRemaining -= bytesRead
            }
            return bytesRead
        }
    }

    override fun getUri(): Uri? {
        return sabrDataSource?.getUri() ?: delegate.getUri()
    }

    override fun close() {
        sabrDataSource?.close()
        sabrDataSource = null
        closeDelegate()
        chunkedYoutube = false
        baseDataSpec = null
        resolvedUri = null
        currentChunkRemaining = 0L
    }

    private fun openNextChunk(): Boolean {
        if (!chunkedYoutube) return false
        if (totalLength != C.LENGTH_UNSET.toLong() && currentPosition >= totalLength) return false

        closeDelegate()
        val template = baseDataSpec ?: return false
        val uri = resolvedUri ?: return false
        val remaining = if (totalLength != C.LENGTH_UNSET.toLong()) {
            totalLength - currentPosition
        } else {
            YOUTUBE_CHUNK_SIZE
        }
        val chunkLength = minOf(YOUTUBE_CHUNK_SIZE, remaining)
        if (chunkLength <= 0L) return false

        val chunkSpec = template.buildUpon()
            .setUri(uri)
            .setPosition(currentPosition)
            .setLength(chunkLength)
            .build()
        val openedLength = delegate.open(chunkSpec)
        delegateOpen = true
        currentChunkRemaining = if (openedLength != C.LENGTH_UNSET.toLong()) {
            openedLength
        } else {
            chunkLength
        }
        // Some progressive URLs omit `clen` from their query. A short final
        // ranged response is still enough to learn the total length and avoids
        // issuing one extra range request that would otherwise return HTTP 416.
        if (totalLength == C.LENGTH_UNSET.toLong() &&
            openedLength != C.LENGTH_UNSET.toLong() &&
            openedLength < chunkLength
        ) {
            totalLength = currentPosition + openedLength
        }
        if (currentChunkRemaining <= 0L) {
            closeDelegate()
            return false
        }
        repository.log(
            "ResolvingDataSource: Opened range " +
                "$currentPosition-${currentPosition + chunkLength - 1} " +
                "(reported=$currentChunkRemaining)"
        )
        return currentChunkRemaining > 0L
    }

    private fun closeDelegate() {
        // Media3 may retry an open after a failed load without giving the wrapper a
        // successful open callback. Always close the delegate so DefaultDataSource and
        // CacheDataSource cannot retain an earlier open state across retries/chunks.
        try {
            delegate.close()
        } finally {
            delegateOpen = false
        }
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
