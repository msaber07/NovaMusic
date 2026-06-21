package com.example.novaplayer.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {

    // Song Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSong(song: SongEntity)

    @Update
    fun updateSong(song: SongEntity)

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongById(songId: String): SongEntity?

    @Query("SELECT * FROM songs ORDER BY addedAt DESC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isFavorite = 1 ORDER BY addedAt DESC")
    fun getFavoriteSongsFlow(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE isDownloaded = 1 ORDER BY addedAt DESC")
    fun getDownloadedSongsFlow(): Flow<List<SongEntity>>

    @Query("DELETE FROM songs WHERE id = :songId")
    fun deleteSongById(songId: String)

    // Playlist Operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylist(playlist: PlaylistEntity)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistById(playlistId: String): PlaylistEntity?

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    fun deletePlaylistById(playlistId: String)

    // Playlist-Song relation operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertPlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)

    @Delete
    fun deletePlaylistSongCrossRef(crossRef: PlaylistSongCrossRef)

    @Transaction
    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    fun getPlaylistWithSongsFlow(playlistId: String): Flow<PlaylistWithSongs?>

    @Transaction
    @Query("SELECT * FROM playlists")
    fun getAllPlaylistsWithSongsFlow(): Flow<List<PlaylistWithSongs>>
}
