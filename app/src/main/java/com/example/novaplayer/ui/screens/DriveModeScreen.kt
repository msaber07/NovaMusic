package com.example.novaplayer.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.novaplayer.data.local.SongEntity
import androidx.compose.ui.res.stringResource
import com.example.novaplayer.R
import com.example.novaplayer.theme.*
import com.example.novaplayer.ui.viewmodel.MusicViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveModeScreen(
    viewModel: MusicViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val favoriteSongs by viewModel.favoriteSongs.collectAsState()
    val downloadedSongs by viewModel.downloadedSongs.collectAsState()
    val queue by viewModel.currentQueue.collectAsState()
    val position by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.trackDuration.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // Intercept back navigation to exit Drive Mode
    BackHandler {
        onDismiss()
    }

    val voiceSearchLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = matches?.firstOrNull() ?: ""
            if (spokenText.isNotEmpty()) {
                searchQuery = spokenText
                isSearching = true
                viewModel.search(spokenText)
                
                android.widget.Toast.makeText(context, context.getString(R.string.voice_searching_and_playing, spokenText), android.widget.Toast.LENGTH_SHORT).show()
                coroutineScope.launch {
                    try {
                        withTimeout(5000) {
                            val results = viewModel.searchResults.first { it.isNotEmpty() }
                            if (results.isNotEmpty()) {
                                viewModel.play(results.first(), listOf(results.first()))
                                isSearching = false
                                searchQuery = ""
                                focusManager.clearFocus()
                            }
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(context, context.getString(R.string.not_found_param, spokenText), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    var dragAmount by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAmount > 100f) {
                            viewModel.skipToPrevious()
                        } else if (dragAmount < -100f) {
                            viewModel.skipToNext()
                        }
                        dragAmount = 0f
                    },
                    onHorizontalDrag = { _, dragAmountValue ->
                        dragAmount += dragAmountValue
                    }
                )
            }
            .systemBarsPadding()
    ) {
        // Futuristic dashboard background glowing node
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(350.dp)
                .background(NeonOrange.copy(alpha = 0.05f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = NeonOrange,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.drive_mode_title),
                        color = NeonOrange,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .background(CyberSurface, CircleShape)
                        .border(1.dp, NeonOrange.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.exit_desc),
                        tint = NeonOrange
                    )
                }
            }

            // Central Area (Swaps between Search Results or Player View)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isSearching || searchQuery.isNotEmpty()) {
                    // Search Results in large list view
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.search_results),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = {
                                searchQuery = ""
                                isSearching = false
                                focusManager.clearFocus()
                            }) {
                                Text(stringResource(R.string.cancel), color = NeonPink, fontSize = 14.sp)
                            }
                        }

                        if (searchResults.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.drive_no_results),
                                    color = TextSecondary,
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 12.dp)
                            ) {
                                items(searchResults) { song ->
                                    // Giant row item for easy tapping
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(72.dp)
                                            .background(CyberDarkBlue, RoundedCornerShape(14.dp))
                                            .border(1.dp, NeonOrange.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                            .clickable {
                                                viewModel.play(song, listOf(song))
                                                searchQuery = ""
                                                isSearching = false
                                                focusManager.clearFocus()
                                            }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
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
                                                    tint = NeonOrange.copy(alpha = 0.6f),
                                                    modifier = Modifier.size(24.dp).align(Alignment.Center)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.title,
                                                color = TextPrimary,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = song.artistName,
                                                color = NeonOrange,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = NeonOrange,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (showQueue) {
                        // Playback Queue list view in Drive Mode
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.playback_queue),
                                    color = NeonOrange,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 2.sp
                                )
                                TextButton(onClick = { showQueue = false }) {
                                    Text(stringResource(R.string.cancel), color = NeonPink, fontSize = 14.sp)
                                }
                            }

                            if (queue.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = stringResource(R.string.no_songs_in_queue),
                                        color = TextSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    contentPadding = PaddingValues(bottom = 12.dp)
                                ) {
                                    itemsIndexed(queue) { index, item ->
                                        val isCurrent = currentSong?.id == item.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(72.dp)
                                                .background(if (isCurrent) CyberDarkBlue else CyberDarkBlue.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                                .border(1.dp, if (isCurrent) NeonOrange else NeonOrange.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
                                                .clickable {
                                                    viewModel.playQueueIndex(index)
                                                    showQueue = false
                                                }
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(52.dp)
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
                                                        tint = NeonOrange.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(24.dp).align(Alignment.Center)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(16.dp))

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.title,
                                                    color = if (isCurrent) NeonOrange else TextPrimary,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = item.artistName,
                                                    color = TextSecondary,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            
                                            Icon(
                                                imageVector = if (isCurrent) Icons.Default.VolumeUp else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                tint = NeonOrange,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Standard Large Player View
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Massive Album Cover Card
                            Box(
                                modifier = Modifier
                                    .size(170.dp)
                                    .shadow(12.dp, RoundedCornerShape(24.dp))
                                    .border(1.dp, NeonOrange.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(CyberSurface)
                            ) {
                                if (currentSong != null && !currentSong!!.albumImageUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = currentSong!!.albumImageUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = NeonOrange.copy(alpha = 0.4f),
                                        modifier = Modifier
                                            .size(64.dp)
                                            .align(Alignment.Center)
                                        )
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))

                            // Large metadata texts with Favorite and Queue button flanking it
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Favorite Button
                                IconButton(
                                    onClick = { currentSong?.let { viewModel.toggleFavorite(it) } },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(CyberSurface, CircleShape)
                                        .border(
                                            1.dp,
                                            if (currentSong?.isFavorite == true) NeonPink.copy(alpha = 0.6f) else NeonOrange.copy(alpha = 0.3f),
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = if (currentSong?.isFavorite == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = stringResource(R.string.favorite_desc),
                                        tint = if (currentSong?.isFavorite == true) NeonPink else NeonOrange,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Column(
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = currentSong?.title ?: stringResource(R.string.no_music_playing),
                                        color = TextPrimary,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = currentSong?.artistName ?: stringResource(R.string.drive_mode_instruction),
                                        color = NeonOrange,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                // Queue Button
                                IconButton(
                                    onClick = { showQueue = true },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(CyberSurface, CircleShape)
                                        .border(1.dp, NeonOrange.copy(alpha = 0.3f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = stringResource(R.string.queue_desc),
                                        tint = NeonOrange,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Seek bar in Drive Mode
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
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
                                        activeTrackColor = NeonOrange,
                                        inactiveTrackColor = CyberSurface,
                                        thumbColor = NeonOrange,
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
                        }
                    }
                }
            }

            // Big Playback Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Giant Skip Previous
                IconButton(
                    onClick = { viewModel.skipToPrevious() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(CyberSurface, CircleShape)
                        .border(1.dp, TextSecondary.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.previous_desc),
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Super Giant circular Play/Pause button
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .shadow(20.dp, CircleShape)
                        .border(2.dp, NeonOrange, CircleShape)
                        .clip(CircleShape)
                        .background(CyberSurface)
                        .clickable { viewModel.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.play_pause_desc),
                        tint = NeonOrange,
                        modifier = Modifier.size(54.dp)
                    )
                }

                // Giant Skip Next
                IconButton(
                    onClick = { viewModel.skipToNext() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(CyberSurface, CircleShape)
                        .border(1.dp, TextSecondary.copy(alpha = 0.3f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.next_desc),
                        tint = TextPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            // Bottom Section (Shortcut Buttons & Voice/Search input)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Search row with Microphone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            isSearching = it.isNotEmpty()
                            viewModel.search(it)
                        },
                        placeholder = { Text(stringResource(R.string.drive_search_placeholder), color = TextSecondary, fontSize = 16.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NeonOrange) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    isSearching = false
                                    viewModel.search("")
                                    focusManager.clearFocus()
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
                            focusedIndicatorColor = NeonOrange,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = NeonOrange
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(60.dp)
                            .border(1.dp, NeonOrange.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                    )

                    // Large Voice Search Button
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .shadow(8.dp, CircleShape)
                            .border(2.dp, NeonOrange, CircleShape)
                            .clip(CircleShape)
                            .background(CyberSurface)
                            .clickable {
                                try {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                        putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_search_prompt))
                                    }
                                    voiceSearchLauncher.launch(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.voice_search_not_supported), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = stringResource(R.string.voice_search_desc),
                            tint = NeonOrange,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                // Favorites & Downloads shortcut rows
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Giant Favorites Play Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(68.dp)
                            .shadow(4.dp, RoundedCornerShape(16.dp))
                            .border(1.dp, NeonPink.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(CyberDarkBlue, CyberSurface)
                                )
                            )
                            .clickable {
                                if (favoriteSongs.isNotEmpty()) {
                                    viewModel.play(favoriteSongs.first(), favoriteSongs)
                                    android.widget.Toast.makeText(context, context.getString(R.string.playing_favorites), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.no_favorites_found), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = null,
                                tint = NeonPink,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.play_favorites_btn),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Giant Downloads Play Button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(68.dp)
                            .shadow(4.dp, RoundedCornerShape(16.dp))
                            .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(CyberDarkBlue, CyberSurface)
                                )
                            )
                            .clickable {
                                if (downloadedSongs.isNotEmpty()) {
                                    viewModel.play(downloadedSongs.first(), downloadedSongs)
                                    android.widget.Toast.makeText(context, context.getString(R.string.playing_downloads), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, context.getString(R.string.no_downloads_found), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.OfflineBolt,
                                contentDescription = null,
                                tint = NeonGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.play_downloads_btn),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
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
