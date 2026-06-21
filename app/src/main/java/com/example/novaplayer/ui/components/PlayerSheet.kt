package com.example.novaplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.novaplayer.data.local.PlaylistEntity
import com.example.novaplayer.data.local.SongEntity
import com.example.novaplayer.theme.*
import com.example.novaplayer.ui.viewmodel.MusicViewModel
import androidx.activity.compose.BackHandler

@Composable
fun PlayerSheet(
    viewModel: MusicViewModel,
    isVisible: Boolean,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = isVisible) {
        onDismiss()
    }
    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.trackDuration.collectAsState()
    val downloadingProgress by viewModel.downloadingProgress.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val queue by viewModel.currentQueue.collectAsState()
    val statusText by viewModel.playerStatusText.collectAsState()

    var showPlaylistDialog by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        if (currentSong == null) return@AnimatedVisibility

        val song = currentSong!!
        val isDownloading = downloadingProgress.containsKey(song.id)
        val downloadProgressValue = downloadingProgress[song.id] ?: 0f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            CyberDarkBlue,
                            CyberBlack
                        )
                    )
                )
        ) {
            // Glowing cyan & purple ambient nodes
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .offset(x = (-40).dp, y = (-40).dp)
                    .background(NeonPurple.copy(alpha = 0.15f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 40.dp, y = 40.dp)
                    .background(NeonCyan.copy(alpha = 0.12f), CircleShape)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(CyberSurface, CircleShape)
                            .border(1.dp, NeonCyan.copy(alpha = 0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Geri",
                            tint = NeonCyan
                        )
                    }

                    Text(
                        text = "NEO STREAM",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.toggleFavorite(song) },
                            modifier = Modifier
                                .background(CyberSurface, CircleShape)
                                .border(
                                    1.dp,
                                    if (song.isFavorite) NeonPink.copy(alpha = 0.6f) else Color.Transparent,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Favori",
                                tint = if (song.isFavorite) NeonPink else TextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { showQueue = !showQueue },
                            modifier = Modifier
                                .background(CyberSurface, CircleShape)
                                .border(
                                    1.dp,
                                    if (showQueue) NeonCyan.copy(alpha = 0.6f) else Color.Transparent,
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "Sıra",
                                tint = if (showQueue) NeonCyan else TextSecondary
                            )
                        }
                    }
                }

                if (showQueue) {
                    // Queue Panel
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(top = 20.dp)
                    ) {
                        Text(
                            text = "OYNATMA SIRASI",
                            color = NeonCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (queue.isEmpty()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("Sırada şarkı yok", color = TextSecondary, fontSize = 14.sp)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(queue) { index, item ->
                                    val isCurrent = item.id == song.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isCurrent) CyberDarkBlue else Color.Transparent)
                                            .border(
                                                width = 1.dp,
                                                color = if (isCurrent) NeonCyan.copy(alpha = 0.4f) else Color.Transparent,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                viewModel.playQueueIndex(index)
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(CyberSurface)
                                        ) {
                                            if (!item.albumImageUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = item.albumImageUrl,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.MusicNote,
                                                    contentDescription = null,
                                                    tint = NeonCyan.copy(alpha = 0.6f),
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = item.title,
                                                color = if (isCurrent) NeonCyan else TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = item.artistName,
                                                color = TextSecondary,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        if (isCurrent) {
                                            Icon(
                                                imageVector = Icons.Default.VolumeUp,
                                                contentDescription = "Çalıyor",
                                                tint = NeonCyan,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = { showQueue = false },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberSurface),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp)
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Oynatıcıya Dön", color = NeonCyan, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Glowing album cover
                    Box(
                        modifier = Modifier
                            .padding(vertical = 24.dp)
                            .size(280.dp)
                            .border(1.dp, NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                            .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .background(CyberSurface)
                    ) {
                        if (!song.albumImageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = song.albumImageUrl,
                                contentDescription = "Albüm Görseli",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = NeonCyan.copy(alpha = 0.6f),
                                    modifier = Modifier.size(72.dp)
                                )
                            }
                        }
                    }

                    // Song Title & Artist
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = song.title,
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = song.artistName,
                            color = NeonCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp
                        )
                        if (statusText.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = statusText,
                                color = NeonGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }

                    // Seek bar
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val sliderPosition = if (duration > 0) position.toFloat() / duration else 0f
                        var isSeeking by remember { mutableStateOf(false) }
                        var localSliderValue by remember { mutableStateOf(0f) }

                        Slider(
                            value = if (isSeeking) localSliderValue else sliderPosition,
                            onValueChange = {
                                isSeeking = true
                                localSliderValue = it
                            },
                            onValueChangeFinished = {
                                isSeeking = false
                                viewModel.seekTo((localSliderValue * duration).toLong())
                            },
                            colors = SliderDefaults.colors(
                                activeTrackColor = NeonCyan,
                                inactiveTrackColor = CyberSurface,
                                thumbColor = NeonCyan,
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(position),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = formatTime(duration),
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Control panel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Download icon
                        IconButton(
                            onClick = {
                                if (song.isDownloaded) {
                                    viewModel.deleteDownloadedSong(song)
                                } else if (!isDownloading) {
                                    viewModel.downloadSong(song)
                                }
                            }
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    progress = { downloadProgressValue },
                                    modifier = Modifier.size(24.dp),
                                    color = NeonGreen,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = if (song.isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                    contentDescription = "İndir",
                                    tint = if (song.isDownloaded) NeonGreen else TextSecondary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        // Skip Previous
                        IconButton(onClick = { viewModel.skipToPrevious() }) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Önceki",
                                tint = TextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Play/Pause circular glowing button
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .border(1.dp, NeonCyan, CircleShape)
                                .shadow(elevation = 16.dp, shape = CircleShape)
                                .clip(CircleShape)
                                .background(CyberSurface)
                                .clickable { viewModel.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Oynat/Durdur",
                                tint = NeonCyan,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        // Skip Next
                        IconButton(onClick = { viewModel.skipToNext() }) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Sonraki",
                                tint = TextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Add to Playlist
                        IconButton(onClick = { showPlaylistDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.PlaylistAdd,
                                contentDescription = "Listeye Ekle",
                                tint = TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Playlist Selection Dialog
    if (showPlaylistDialog) {
        Dialog(onDismissRequest = { showPlaylistDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = CyberDarkBlue,
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Oynatma Listesine Ekle",
                        color = NeonCyan,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (playlists.isEmpty()) {
                        Text(
                            text = "Henüz oynatma listeniz yok. Kitaplık ekranından oluşturabilirsiniz.",
                            color = TextSecondary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .heightIn(max = 250.dp)
                                .fillMaxWidth()
                        ) {
                            items(playlists) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentSong?.let { song ->
                                                viewModel.addSongToPlaylist(song, playlist.playlistId)
                                            }
                                            showPlaylistDialog = false
                                        }
                                        .padding(vertical = 12.dp, horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = null,
                                        tint = NeonPurple,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                    Text(
                                        text = playlist.name,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                HorizontalDivider(color = CyberSurface)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { showPlaylistDialog = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = "Kapat", color = NeonPink)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
