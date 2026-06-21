package com.example.novaplayer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val description: String?,
    val isSystemCategory: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
