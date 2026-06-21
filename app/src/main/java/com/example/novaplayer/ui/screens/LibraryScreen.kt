package com.example.novaplayer.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.novaplayer.data.local.PlaylistEntity
import com.example.novaplayer.data.local.PlaylistWithSongs
import com.example.novaplayer.data.local.SongEntity
import com.example.novaplayer.theme.*
import com.example.novaplayer.ui.viewmodel.MusicViewModel

@Composable
fun LibraryScreen(viewModel: MusicViewModel) {
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    var selectedTabIndex by remember { mutableStateOf(0) } // 0 = Downloads, 1 = Playlists
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var selectedPlaylistForDetail by remember { mutableStateOf<PlaylistEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "KİTAPLIĞIM",
                color = NeonCyan,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )

            if (selectedTabIndex == 1) {
                IconButton(
                    onClick = { showCreatePlaylistDialog = true },
                    modifier = Modifier
                        .background(CyberSurface, CircleShape)
                        .border(1.dp, NeonPurple.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Oynatma Listesi Ekle",
                        tint = NeonPurple
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cyberpunk style Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CyberDarkBlue, RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            TabButton(
                text = "Cihazımdakiler",
                isSelected = selectedTabIndex == 0,
                modifier = Modifier.weight(1f)
            ) {
                selectedTabIndex = 0
            }
            TabButton(
                text = "Listelerim",
                isSelected = selectedTabIndex == 1,
                modifier = Modifier.weight(1f)
            ) {
                selectedTabIndex = 1
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTabIndex) {
            0 -> {
                // Downloads list
                if (downloadedSongs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = TextDim,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "İndirilmiş müzik bulunamadı.",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Arama ekranındaki müzikleri indirip\nçevrimdışı (reklamsız) dinleyebilirsiniz.",
                                color = TextDim,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(downloadedSongs) { song ->
                            DownloadedSongRow(
                                song = song,
                                onPlayClick = { viewModel.play(song, downloadedSongs) },
                                onDeleteClick = { viewModel.deleteDownloadedSong(song) }
                            )
                        }
                    }
                }
            }
            1 -> {
                // Playlists list
                if (playlists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = null,
                                tint = TextDim,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Henüz oynatma listeniz yok.",
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { showCreatePlaylistDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = CyberSurface),
                                border = BorderStroke(1.dp, NeonPurple)
                            ) {
                                Text("Şimdi Oluştur", color = NeonPurple)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(playlists) { playlist ->
                            PlaylistRow(
                                playlist = playlist,
                                onClick = { selectedPlaylistForDetail = playlist },
                                onDeleteClick = { viewModel.deletePlaylist(playlist.playlistId) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create Playlist Dialog
    if (showCreatePlaylistDialog) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }

        Dialog(onDismissRequest = { showCreatePlaylistDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = CyberDarkBlue,
                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Yeni Liste Oluştur",
                        color = NeonCyan,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Oynatma Listesi Adı", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberSurface,
                            focusedLabelColor = NeonCyan,
                            cursorColor = NeonCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Açıklama (Opsiyonel)", color = TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = CyberSurface,
                            focusedLabelColor = NeonCyan,
                            cursorColor = NeonCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showCreatePlaylistDialog = false }) {
                            Text("İptal", color = TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (name.isNotBlank()) {
                                    viewModel.createPlaylist(name, description)
                                    showCreatePlaylistDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                        ) {
                            Text("Oluştur", color = CyberBlack)
                        }
                    }
                }
            }
        }
    }

    // Playlist Details Dialog
    selectedPlaylistForDetail?.let { playlist ->
        val playlistWithSongsState by viewModel.getPlaylistWithSongs(playlist.playlistId)
            .collectAsState(initial = null)

        Dialog(onDismissRequest = { selectedPlaylistForDetail = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .fillMaxHeight(0.8f),
                shape = RoundedCornerShape(24.dp),
                color = CyberDarkBlue,
                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                color = NeonCyan,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!playlist.description.isNullOrBlank()) {
                                Text(
                                    text = playlist.description,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        val songsList = playlistWithSongsState?.songs ?: emptyList()
                        if (songsList.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    viewModel.play(songsList.first(), songsList)
                                    selectedPlaylistForDetail = null
                                },
                                modifier = Modifier
                                    .background(NeonPurple, CircleShape)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Hepsini Çal",
                                    tint = TextPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val songs = playlistWithSongsState?.songs ?: emptyList()

                    if (songs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Bu listede henüz şarkı yok.\nŞarkı detayından ekleyebilirsiniz.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(songs) { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CyberSurface, RoundedCornerShape(8.dp))
                                        .clickable {
                                            viewModel.play(song, songs)
                                            selectedPlaylistForDetail = null
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CyberBlack)
                                    ) {
                                        if (!song.albumImageUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = song.albumImageUrl,
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = NeonCyan,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .align(Alignment.Center)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = song.title,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.artistName,
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            viewModel.removeSongFromPlaylist(song.id, playlist.playlistId)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Listeden Çıkar",
                                            tint = NeonPink,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TextButton(
                        onClick = { selectedPlaylistForDetail = null },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(text = "Kapat", color = NeonCyan)
                    }
                }
            }
        }
    }
}

@Composable
fun TabButton(
    text: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) NeonCyan else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) CyberBlack else TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun DownloadedSongRow(
    song: SongEntity,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberDarkBlue, RoundedCornerShape(12.dp))
            .border(0.5.dp, NeonGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable { onPlayClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CyberSurface)
        ) {
            if (!song.albumImageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = song.albumImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = NeonGreen,
                    modifier = Modifier
                        .size(20.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.artistName,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "İndirmeyi Sil",
                tint = NeonPink,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun PlaylistRow(
    playlist: PlaylistEntity,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CyberDarkBlue, RoundedCornerShape(12.dp))
            .border(0.5.dp, NeonPurple.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(CyberSurface, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.QueueMusic,
                contentDescription = null,
                tint = NeonPurple,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = playlist.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = playlist.description ?: "Açıklama yok",
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Listeyi Sil",
                tint = NeonPink,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
