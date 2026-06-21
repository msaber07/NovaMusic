package com.example.novaplayer.data.network

import com.google.gson.annotations.SerializedName

data class TrackResponse(
    @SerializedName("data") val data: List<TrackDto>
)

data class TrackDto(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("duration") val duration: Int,
    @SerializedName("artwork") val artwork: ArtworkDto?,
    @SerializedName("user") val user: UserDto?
)

data class ArtworkDto(
    @SerializedName("150x150") val image150: String?,
    @SerializedName("480x480") val image480: String?,
    @SerializedName("1000x1000") val image1000: String?
)

data class UserDto(
    @SerializedName("name") val name: String
)
