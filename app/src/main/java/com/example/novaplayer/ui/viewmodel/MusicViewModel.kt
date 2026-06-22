package com.example.novaplayer.ui.viewmodel

import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.novaplayer.data.local.PlaylistEntity
import com.example.novaplayer.data.local.SongEntity
import com.example.novaplayer.data.repository.MusicRepository
import com.example.novaplayer.service.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.example.novaplayer.R

class MusicViewModel(context: Context) : ViewModel() {

    private val repository = MusicRepository(context)
    private val appContext = context.applicationContext
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // UI States
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isDriveMode = MutableStateFlow(false)
    val isDriveMode: StateFlow<Boolean> = _isDriveMode.asStateFlow()

    private val _currentSong = MutableStateFlow<SongEntity?>(null)
    val currentSong: StateFlow<SongEntity?> = _currentSong.asStateFlow()

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition.asStateFlow()

    private val _trackDuration = MutableStateFlow(0L)
    val trackDuration: StateFlow<Long> = _trackDuration.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SongEntity>>(emptyList())
    val searchResults: StateFlow<List<SongEntity>> = _searchResults.asStateFlow()

    private val _trendingSongs = MutableStateFlow<List<SongEntity>>(emptyList())
    val trendingSongs: StateFlow<List<SongEntity>> = _trendingSongs.asStateFlow()

    private val _failedSongs = MutableStateFlow<Set<String>>(emptySet())
    val failedSongs: StateFlow<Set<String>> = _failedSongs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _downloadingProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadingProgress: StateFlow<Map<String, Float>> = _downloadingProgress.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<SongEntity>>(emptyList())
    val currentQueue: StateFlow<List<SongEntity>> = _currentQueue.asStateFlow()

    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    // Combined player loading/status text overlay
    val playerStatusText: StateFlow<String> = combine(
        MusicRepository.resolutionStatus,
        _playbackState
    ) { status, state ->
        if (status.isNotEmpty()) {
            status
        } else if (state == Player.STATE_BUFFERING) {
            appContext.getString(R.string.status_loading)
        } else {
            ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Local DB flows
    val favoriteSongs: StateFlow<List<SongEntity>> = repository.getFavoriteSongsFlow()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val downloadedSongs: StateFlow<List<SongEntity>> = repository.getDownloadedSongsFlow()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<PlaylistEntity>> = repository.getAllPlaylistsFlow()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private var progressJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var autoplayJob: Job? = null

    init {
        initializeController(context)
        loadTrending()
    }

    private fun initializeController(context: Context) {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupControllerListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupControllerListener() {
        val controller = mediaController ?: return
        
        _isPlaying.value = controller.isPlaying
        updateCurrentMediaItem(controller.currentMediaItem)
        updateCurrentQueue()
        _playbackPosition.value = controller.currentPosition
        _trackDuration.value = controller.duration.coerceAtLeast(0L)
        _playbackState.value = controller.playbackState

        controller.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                updateCurrentQueue()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressUpdate()
                } else {
                    stopProgressUpdate()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCurrentMediaItem(mediaItem)
                updateCurrentQueue()
                _trackDuration.value = controller.duration.coerceAtLeast(0L)

                val currentIndex = controller.currentMediaItemIndex
                val itemCount = controller.mediaItemCount
                if (currentIndex >= 0 && currentIndex == itemCount - 1 && mediaItem != null) {
                    appendAutoplayRecommendations(mediaItem.mediaId)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _trackDuration.value = controller.duration.coerceAtLeast(0L)
                _playbackState.value = playbackState
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val currentItem = controller.currentMediaItem
                if (currentItem != null) {
                    val songId = currentItem.mediaId
                    _failedSongs.value = _failedSongs.value + songId
                    repository.log("MusicViewModel: Player error for songId: $songId -> ${error.message} (errorCode: ${error.errorCode}, name: ${error.errorCodeName})")
                    viewModelScope.launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            appContext,
                            appContext.getString(R.string.song_unplayable_toast),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    if (controller.hasNextMediaItem()) {
                        controller.seekToNext()
                        controller.prepare()
                        controller.play()
                    }
                }
            }
        })

        if (controller.isPlaying) {
            startProgressUpdate()
        }
    }

    private fun updateCurrentMediaItem(mediaItem: MediaItem?) {
        if (mediaItem == null) {
            _currentSong.value = null
            return
        }
        val songId = mediaItem.mediaId
        viewModelScope.launch {
            val song = repository.getFavoriteSongsFlow().first().find { it.id == songId }
                ?: repository.getDownloadedSongsFlow().first().find { it.id == songId }
                ?: _searchResults.value.find { it.id == songId }
                ?: _trendingSongs.value.find { it.id == songId }
                ?: SongEntity(
                    id = songId,
                    title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                    artistName = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown",
                    audioUrl = mediaItem.localConfiguration?.uri?.toString() ?: mediaItem.requestMetadata.mediaUri?.toString() ?: "",
                    durationSeconds = 0,
                    albumImageUrl = mediaItem.mediaMetadata.artworkUri?.toString()
                )
            _currentSong.value = song
        }
    }

    private fun updateCurrentQueue() {
        val controller = mediaController ?: return
        val count = controller.mediaItemCount
        val items = ArrayList<MediaItem>()
        for (i in 0 until count) {
            val item = try { controller.getMediaItemAt(i) } catch (e: Exception) { null }
            if (item != null) {
                items.add(item)
            }
        }
        viewModelScope.launch {
            val favorites = repository.getFavoriteSongsFlow().first()
            val downloads = repository.getDownloadedSongsFlow().first()
            
            val mapped = items.map { mediaItem ->
                val songId = mediaItem.mediaId
                favorites.find { it.id == songId }
                    ?: downloads.find { it.id == songId }
                    ?: _searchResults.value.find { it.id == songId }
                    ?: _trendingSongs.value.find { it.id == songId }
                    ?: SongEntity(
                        id = songId,
                        title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
                        artistName = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown",
                        audioUrl = mediaItem.localConfiguration?.uri?.toString() ?: mediaItem.requestMetadata.mediaUri?.toString() ?: "",
                        durationSeconds = 0,
                        albumImageUrl = mediaItem.mediaMetadata.artworkUri?.toString()
                    )
            }
            _currentQueue.value = mapped
        }
    }

    fun playQueueIndex(index: Int) {
        val controller = mediaController ?: return
        if (index >= 0 && index < controller.mediaItemCount) {
            controller.seekTo(index, 0L)
            controller.prepare()
            controller.play()
        }
    }

    private fun appendAutoplayRecommendations(songId: String) {
        val controller = mediaController ?: return
        autoplayJob?.cancel()
        autoplayJob = viewModelScope.launch {
            try {
                val recommended = repository.getRecommendedVideos(songId)
                if (recommended.isNotEmpty()) {
                    val mediaItems = recommended.map { item ->
                        val playUri = if (item.isDownloaded && item.localUri != null) {
                            if (item.localUri.startsWith("/")) "file://${item.localUri}" else item.localUri
                        } else item.audioUrl
                        val uriObj = if (!playUri.isNullOrEmpty()) android.net.Uri.parse(playUri) else null
                        
                        MediaItem.Builder()
                            .setMediaId(item.id)
                            .setUri(uriObj)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(item.title)
                                    .setArtist(item.artistName)
                                    .setArtworkUri(item.albumImageUrl?.let { android.net.Uri.parse(it) })
                                    .build()
                            )
                            .build()
                    }
                    controller.addMediaItems(mediaItems)
                    recommended.forEach { repository.insertSong(it) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                mediaController?.let { controller ->
                    _playbackPosition.value = controller.currentPosition
                    _trackDuration.value = controller.duration.coerceAtLeast(0L)
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    fun play(song: SongEntity, queue: List<SongEntity>) {
        val controller = mediaController ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _isLoading.value = true

            val mediaItems = queue.map { item ->
                val playUri = if (item.isDownloaded && item.localUri != null) {
                    if (item.localUri.startsWith("/")) "file://${item.localUri}" else item.localUri
                } else item.audioUrl
                val uriObj = if (!playUri.isNullOrEmpty()) android.net.Uri.parse(playUri) else null
                
                MediaItem.Builder()
                    .setMediaId(item.id)
                    .setUri(uriObj)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artistName)
                            .setArtworkUri(item.albumImageUrl?.let { android.net.Uri.parse(it) })
                            .build()
                    )
                    .build()
            }

            controller.setMediaItems(mediaItems)
            val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            controller.seekTo(index, 0L)
            controller.prepare()
            controller.play()

            repository.insertSong(song)
            
            _isLoading.value = false
        }
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            controller.pause()
        } else {
            controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
        _playbackPosition.value = positionMs
    }

    fun skipToNext() {
        mediaController?.seekToNext()
    }

    fun skipToPrevious() {
        mediaController?.seekToPrevious()
    }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(500)
            _isLoading.value = true
            repository.searchTracks(query).collect { songs ->
                _searchResults.value = songs
                _isLoading.value = false
            }
        }
    }

    private fun loadTrending() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.getTrendingTracks().collect { songs ->
                _trendingSongs.value = songs
                _isLoading.value = false
            }
        }
    }

    fun toggleFavorite(song: SongEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(song)
            _searchResults.value = _searchResults.value.map {
                if (it.id == song.id) it.copy(isFavorite = !it.isFavorite) else it
            }
            _trendingSongs.value = _trendingSongs.value.map {
                if (it.id == song.id) it.copy(isFavorite = !it.isFavorite) else it
            }
            if (_currentSong.value?.id == song.id) {
                _currentSong.value = _currentSong.value?.copy(isFavorite = !song.isFavorite)
            }
            _currentQueue.value = _currentQueue.value.map {
                if (it.id == song.id) it.copy(isFavorite = !it.isFavorite) else it
            }
        }
    }

    fun setDriveMode(enabled: Boolean) {
        _isDriveMode.value = enabled
    }

    fun createPlaylist(name: String, description: String? = null) {
        viewModelScope.launch {
            repository.createPlaylist(name, description)
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
        }
    }

    fun addSongToPlaylist(song: SongEntity, playlistId: String) {
        viewModelScope.launch {
            repository.addSongToPlaylist(song, playlistId)
        }
    }

    fun removeSongFromPlaylist(songId: String, playlistId: String) {
        viewModelScope.launch {
            repository.removeSongFromPlaylist(songId, playlistId)
        }
    }

    fun getPlaylistWithSongs(playlistId: String): kotlinx.coroutines.flow.Flow<com.example.novaplayer.data.local.PlaylistWithSongs?> {
        return repository.getPlaylistWithSongsFlow(playlistId)
    }

    fun downloadSong(song: SongEntity) {
        viewModelScope.launch {
            _downloadingProgress.value = _downloadingProgress.value + (song.id to 0.0f)
            val success = repository.downloadSong(song) { progress ->
                _downloadingProgress.value = _downloadingProgress.value + (song.id to progress)
            }
            _downloadingProgress.value = _downloadingProgress.value - song.id
            if (success) {
                _searchResults.value = _searchResults.value.map {
                    if (it.id == song.id) it.copy(isDownloaded = true) else it
                }
                _trendingSongs.value = _trendingSongs.value.map {
                    if (it.id == song.id) it.copy(isDownloaded = true) else it
                }
                if (_currentSong.value?.id == song.id) {
                    _currentSong.value = _currentSong.value?.copy(isDownloaded = true)
                }
                _currentQueue.value = _currentQueue.value.map {
                    if (it.id == song.id) it.copy(isDownloaded = true) else it
                }
            }
        }
    }

    fun deleteDownloadedSong(song: SongEntity) {
        viewModelScope.launch {
            repository.deleteDownloadedSong(song)
            _searchResults.value = _searchResults.value.map {
                if (it.id == song.id) it.copy(isDownloaded = false) else it
            }
            _trendingSongs.value = _trendingSongs.value.map {
                if (it.id == song.id) it.copy(isDownloaded = false) else it
            }
            if (_currentSong.value?.id == song.id) {
                _currentSong.value = _currentSong.value?.copy(isDownloaded = false)
            }
            _currentQueue.value = _currentQueue.value.map {
                if (it.id == song.id) it.copy(isDownloaded = false) else it
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        searchJob?.cancel()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}
