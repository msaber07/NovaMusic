package com.example.novaplayer.data.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface InvidiousService {

    @GET("api/v1/search")
    suspend fun searchVideos(
        @Query("q") query: String,
        @Query("type") type: String = "video"
    ): List<InvidiousVideoDto>

    @GET("api/v1/videos/{videoId}")
    suspend fun getVideoDetails(
        @Path("videoId") videoId: String
    ): InvidiousDetailsDto

    companion object {
        const val BASE_URL = "https://inv.thepixora.com/"
    }
}
