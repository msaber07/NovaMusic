package com.example.novaplayer.data.network

import com.google.gson.annotations.SerializedName

data class InvidiousVideoDto(
    @SerializedName("title") val title: String,
    @SerializedName("videoId") val videoId: String,
    @SerializedName("author") val author: String,
    @SerializedName("videoThumbnails") val videoThumbnails: List<InvidiousThumbnailDto>?,
    @SerializedName("lengthSeconds") val lengthSeconds: Int
)

data class InvidiousThumbnailDto(
    @SerializedName("quality") val quality: String,
    @SerializedName("url") val url: String,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int
)

data class InvidiousDetailsDto(
    @SerializedName("title") val title: String,
    @SerializedName("videoId") val videoId: String,
    @SerializedName("author") val author: String,
    @SerializedName("lengthSeconds") val lengthSeconds: Int,
    @SerializedName("adaptiveFormats") val adaptiveFormats: List<InvidiousFormatDto>
)

data class InvidiousFormatDto(
    @SerializedName("type") val type: String,
    @SerializedName("url") val url: String
)
