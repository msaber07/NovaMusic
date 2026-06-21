package com.example.novaplayer.data.local

import androidx.room.Entity

@Entity(tableName = "playlist_song_cross_ref", primaryKeys = ["playlistId", "id"])
data class PlaylistSongCrossRef(
    val playlistId: String,
    val id: String // Song ID
)
