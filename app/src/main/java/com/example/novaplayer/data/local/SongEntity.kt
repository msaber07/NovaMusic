package com.example.novaplayer.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artistName: String,
    val audioUrl: String,
    val durationSeconds: Int,
    val albumImageUrl: String?,
    val isDownloaded: Boolean = false,
    val localUri: String? = null,
    val isFavorite: Boolean = false,
    val addedAt: Long = System.currentTimeMillis()
)
