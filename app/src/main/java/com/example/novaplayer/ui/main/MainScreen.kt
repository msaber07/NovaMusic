package com.example.novaplayer.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import coil.compose.AsyncImage
import com.example.novaplayer.R
import com.example.novaplayer.theme.*
import com.example.novaplayer.ui.components.PlayerSheet
import com.example.novaplayer.ui.screens.DriveModeScreen
import com.example.novaplayer.ui.screens.HomeScreen
import com.example.novaplayer.ui.screens.LibraryScreen
import com.example.novaplayer.ui.screens.SearchScreen
import com.example.novaplayer.ui.viewmodel.MusicViewModel

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val viewModel: MusicViewModel = viewModel { MusicViewModel(context) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Home, 1 = Search, 2 = Library
    var isPlayerVisible by remember { mutableStateOf(false) }

    val currentSong by viewModel.currentSong.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.playbackPosition.collectAsState()
    val duration by viewModel.trackDuration.collectAsState()
    val isDriveMode by viewModel.isDriveMode.collectAsState()

    // Large / automotive pane: show Drive Mode now-playing + controls.
    // Use pane size from LocalConfiguration (already the multi-window pane size), so a
    // narrow map+app split stays compact while a large car pane still enters Drive Mode.
    // Blocking on isInMultiWindow alone hid now-playing on many dual-screen car setups.
    val configuration = LocalConfiguration.current
    val isLargeFullscreenPane =
        (configuration.smallestScreenWidthDp >= 600 && configuration.screenWidthDp >= 840) ||
            (configuration.screenWidthDp >= 1000 && configuration.screenHeightDp >= 560)
    // Allow dismissing Drive Mode on a large pane without it immediately reopening.
    var userDismissedLargeDriveMode by remember { mutableStateOf(false) }
    LaunchedEffect(isLargeFullscreenPane, currentSong?.id) {
        if (isLargeFullscreenPane) {
            // Re-enter when car/phone starts playback onto this large pane.
            if (!userDismissedLargeDriveMode) {
                viewModel.setDriveMode(true)
            }
        } else {
            userDismissedLargeDriveMode = false
            viewModel.setDriveMode(false)
        }
    }

    Scaffold(
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CyberBlack)
            ) {
                // Mini Player
                AnimatedVisibility(
                    visible = currentSong != null,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    currentSong?.let { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .shadow(elevation = 8.dp, shape = RoundedCornerShape(14.dp))
                                .border(1.dp, NeonCyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                                .background(CyberDarkBlue, RoundedCornerShape(14.dp))
                                .clickable { isPlayerVisible = true }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Album Art
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
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
                                        tint = NeonCyan,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .align(Alignment.Center)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Metadata
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
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Play/Pause button
                            IconButton(
                                onClick = { viewModel.togglePlayPause() },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(CyberSurface, CircleShape)
                                    .border(1.dp, NeonCyan.copy(alpha = 0.3f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.play_pause_desc),
                                    tint = NeonCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Close/Dismiss playing queue
                            IconButton(onClick = { viewModel.seekTo(0); viewModel.togglePlayPause() }) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = stringResource(R.string.next_desc),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Bottom Navigation
                NavigationBar(
                    containerColor = CyberDarkBlue,
                    tonalElevation = 8.dp,
                    modifier = Modifier.clip(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.discover_tab_desc)) },
                        label = { Text(stringResource(R.string.discover_tab), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = CyberSurface
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search_tab_desc)) },
                        label = { Text(stringResource(R.string.search_tab), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = CyberSurface
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = stringResource(R.string.library_tab_desc)) },
                        label = { Text(stringResource(R.string.library_tab), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NeonCyan,
                            selectedTextColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = CyberSurface
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CyberBlack)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    viewModel = viewModel,
                    onNavigateToSearch = { selectedTab = 1 },
                    onLanguageSelected = onLanguageSelected
                )
                1 -> SearchScreen(viewModel = viewModel)
                2 -> LibraryScreen(viewModel = viewModel)
            }
        }
    }

    // Full Player Screen Sheet Overlay
    PlayerSheet(
        viewModel = viewModel,
        isVisible = isPlayerVisible,
        onDismiss = { isPlayerVisible = false }
    )

    if (isDriveMode) {
        DriveModeScreen(
            viewModel = viewModel,
            onDismiss = {
                if (isLargeFullscreenPane) {
                    userDismissedLargeDriveMode = true
                }
                viewModel.setDriveMode(false)
            }
        )
    }
}
