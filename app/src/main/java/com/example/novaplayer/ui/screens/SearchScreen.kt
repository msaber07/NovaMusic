package com.example.novaplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.novaplayer.data.local.SongEntity
import androidx.compose.ui.res.stringResource
import com.example.novaplayer.R
import com.example.novaplayer.theme.*
import com.example.novaplayer.ui.viewmodel.MusicViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: MusicViewModel) {
    var query by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val downloadingProgress by viewModel.downloadingProgress.collectAsState()
    val failedSongs by viewModel.failedSongs.collectAsState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.search_title),
            color = NeonCyan,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Futuristic Search Text Field
        TextField(
            value = query,
            onValueChange = {
                query = it
                viewModel.search(it)
            },
            placeholder = { Text(stringResource(R.string.search_placeholder), color = TextSecondary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        viewModel.search("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = NeonPink)
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = CyberDarkBlue,
                unfocusedContainerColor = CyberDarkBlue,
                focusedIndicatorColor = NeonCyan,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedPlaceholderColor = TextSecondary,
                unfocusedPlaceholderColor = TextSecondary,
                cursorColor = NeonCyan
            ),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NeonCyan)
            }
        } else if (searchResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextDim,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (query.isEmpty()) stringResource(R.string.search_start_prompt) else stringResource(R.string.search_no_results),
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp) // Leave space for mini player
            ) {
                items(searchResults) { song ->
                    val isFailed = failedSongs.contains(song.id)
                    SearchSongItem(
                        song = song,
                        isFailed = isFailed,
                        isDownloading = downloadingProgress.containsKey(song.id),
                        downloadProgress = downloadingProgress[song.id] ?: 0f,
                        onPlayClick = { viewModel.play(song, listOf(song)) },
                        onFavoriteClick = { viewModel.toggleFavorite(song) },
                        onDownloadClick = {
                            if (song.isDownloaded) {
                                viewModel.deleteDownloadedSong(song)
                            } else {
                                viewModel.downloadSong(song)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SearchSongItem(
    song: SongEntity,
    isFailed: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isFailed) 0.4f else 1.0f)
            .background(CyberDarkBlue, RoundedCornerShape(12.dp))
            .border(
                0.5.dp,
                if (isFailed) Color.Gray.copy(alpha = 0.3f) else NeonPurple.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            )
            .clickable {
                if (isFailed) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.song_unplayable_toast),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    onPlayClick()
                }
            }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Image
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(8.dp))
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
                    tint = NeonCyan.copy(alpha = 0.5f),
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = song.artistName,
                color = NeonCyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Action Buttons (Favorite / Download)
        IconButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (song.isFavorite) NeonPink else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onDownloadClick) {
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(20.dp),
                    color = NeonGreen,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = if (song.isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    contentDescription = "Download",
                    tint = if (song.isDownloaded) NeonGreen else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
