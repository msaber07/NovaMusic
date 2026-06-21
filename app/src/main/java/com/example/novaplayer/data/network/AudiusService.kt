package com.example.novaplayer.data.network

import retrofit2.http.GET
import retrofit2.http.Query

interface AudiusService {

    @GET("v1/tracks/search")
    suspend fun searchTracks(
        @Query("query") query: String,
        @Query("app_name") appName: String = "novaplayer"
    ): TrackResponse

    @GET("v1/tracks/trending")
    suspend fun getTrendingTracks(
        @Query("limit") limit: Int = 20,
        @Query("app_name") appName: String = "novaplayer"
    ): TrackResponse

    companion object {
        const val BASE_URL = "https://api.audius.co/"
    }
}
