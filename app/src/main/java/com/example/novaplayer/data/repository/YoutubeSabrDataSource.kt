package com.example.novaplayer.data.repository

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque

/** The player information needed to start YouTube's Server ABR (SABR/UMP) stream. */
data class YoutubeSabrStreamInfo(
    val videoId: String,
    val directUrl: String,
    val serverAbrStreamingUrl: String,
    val videoPlaybackUstreamerConfig: String,
    val formatId: Int,
    val videoFormatId: Int = -1,
    val formatLastModified: Long = 0L,
    val videoFormatLastModified: Long = 0L,
    val contentLength: Long,
    val durationMs: Long,
    val streamingPoToken: String? = null,
    val clientName: Int = 3,
    val clientVersion: String = "20.10.38",
    val userAgent: String =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip",
    val visitorData: String? = null,
    val cookies: String? = null
)

/**
 * Media3 DataSource for YouTube's UMP/SABR audio stream.
 *
 * SABR is time based and returns framed protobuf messages instead of byte ranges.
 * This adapter turns the returned init/media parts into the continuous byte stream
 * expected by Media3. It intentionally requests audio only, so video parts never
 * need to be buffered or decoded.
 */
class YoutubeSabrDataSource(
    private val client: OkHttpClient,
    private val streamInfo: YoutubeSabrStreamInfo,
    private val logger: (String) -> Unit = {}
) : DataSource {

    private companion object {
        const val MEDIA_HEADER_PART = 20
        const val MEDIA_PART = 21
        const val MEDIA_END_PART = 22
        const val FORMAT_INITIALIZATION_PART = 42
        const val SABR_ERROR_PART = 44
        const val DEFAULT_SEGMENT_DURATION_MS = 10_000L
    }

    private data class TimeRange(
        val startTicks: Long = 0L,
        val durationTicks: Long = 0L,
        val timescale: Int = 1
    )

    private data class MediaHeader(
        val isInitSegment: Boolean,
        val sequenceNumber: Int,
        val startMs: Long,
        val durationMs: Long,
        val contentLength: Long
    )

    private class Cursor(var index: Int = 0)

    private data class ProtoField(
        val number: Int,
        val wireType: Int,
        val varint: Long = 0L,
        val bytes: ByteArray = ByteArray(0)
    )

    private var currentUri: Uri? = null
    private var opened = false
    private var finished = false
    private var initQueued = false
    private var selectedFormatSent = false
    private var requestNumber = 0
    private var nextTimeMs = 0L
    private var skipBytes = 0L
    private var remainingBytes = C.LENGTH_UNSET.toLong()
    private var currentBuffer = ByteArray(0)
    private var currentOffset = 0
    private val pendingBuffers = ArrayDeque<ByteArray>()

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        close()
        opened = true
        finished = false
        currentUri = Uri.parse(streamInfo.serverAbrStreamingUrl)
        skipBytes = dataSpec.position
        remainingBytes = dataSpec.length

        requestNextResponse()

        val available = if (streamInfo.contentLength > 0L) {
            (streamInfo.contentLength - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, available.takeIf { it != C.LENGTH_UNSET.toLong() } ?: dataSpec.length)
        } else {
            available
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!opened || length == 0) return 0
        if (remainingBytes == 0L) return C.RESULT_END_OF_INPUT

        while (true) {
            if (currentOffset >= currentBuffer.size) {
                if (pendingBuffers.isNotEmpty()) {
                    currentBuffer = pendingBuffers.removeFirst()
                    currentOffset = 0
                } else {
                    if (finished) return C.RESULT_END_OF_INPUT
                    requestNextResponse()
                    continue
                }
            }

            if (skipBytes > 0L) {
                val skipped = minOf(skipBytes, (currentBuffer.size - currentOffset).toLong()).toInt()
                currentOffset += skipped
                skipBytes -= skipped
                continue
            }

            val available = currentBuffer.size - currentOffset
            val readLength = minOf(
                length.toLong(),
                available.toLong(),
                if (remainingBytes == C.LENGTH_UNSET.toLong()) Long.MAX_VALUE else remainingBytes
            ).toInt()
            if (readLength <= 0) return C.RESULT_END_OF_INPUT

            System.arraycopy(currentBuffer, currentOffset, buffer, offset, readLength)
            currentOffset += readLength
            if (remainingBytes != C.LENGTH_UNSET.toLong()) {
                remainingBytes -= readLength
            }
            return readLength
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        opened = false
        currentUri = null
        finished = false
        initQueued = false
        selectedFormatSent = false
        requestNumber = 0
        nextTimeMs = 0L
        skipBytes = 0L
        remainingBytes = C.LENGTH_UNSET.toLong()
        currentBuffer = ByteArray(0)
        currentOffset = 0
        pendingBuffers.clear()
    }

    private fun requestNextResponse() {
        if (finished) return
        if (streamInfo.durationMs > 0L && nextTimeMs >= streamInfo.durationMs) {
            finished = true
            return
        }

        val requestBody = buildRequestBody(nextTimeMs)
        logger(
            "YouTube SABR request: bytes=${requestBody.size}, " +
                "selected=$selectedFormatSent, timeMs=$nextTimeMs, format=${streamInfo.formatId}"
        )
        val requestUrl = if (streamInfo.serverAbrStreamingUrl.contains('?')) {
            "${streamInfo.serverAbrStreamingUrl}&rn=$requestNumber"
        } else {
            "${streamInfo.serverAbrStreamingUrl}?rn=$requestNumber"
        }
        requestNumber++

        val requestBuilder = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", streamInfo.userAgent)
            .header("Accept", "application/vnd.yt-ump")
            .header("Accept-Encoding", "identity")
            .header("Content-Type", "application/x-protobuf")
            .post(okhttp3.RequestBody.create("application/x-protobuf".toMediaType(), requestBody))
        if (!streamInfo.visitorData.isNullOrBlank()) {
            requestBuilder.header("X-Goog-Visitor-Id", streamInfo.visitorData)
        }
        if (!streamInfo.cookies.isNullOrBlank()) {
            requestBuilder.header("Cookie", streamInfo.cookies)
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("YouTube SABR request failed: HTTP ${response.code}")
            }
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.startsWith("application/vnd.yt-ump")) {
                throw IOException("Unexpected YouTube SABR content type: $contentType")
            }
            val body = response.body?.bytes() ?: throw IOException("Empty YouTube SABR response")
            parseUmpResponse(body)
        }

        selectedFormatSent = true
        if (pendingBuffers.isEmpty() && !finished) {
            throw IOException("YouTube SABR returned no audio media")
        }
    }

    private fun buildRequestBody(playerTimeMs: Long): ByteArray {
        val formatIdFields = mutableListOf(fieldVarint(1, streamInfo.formatId.toLong()))
        if (streamInfo.formatLastModified > 0L) {
            formatIdFields.add(fieldVarint(2, streamInfo.formatLastModified))
        }
        val formatId = fieldBytes(1, concat(*formatIdFields.toTypedArray()))
        val videoFormatId = if (streamInfo.videoFormatId > 0) {
            val videoFormatFields = mutableListOf(fieldVarint(1, streamInfo.videoFormatId.toLong()))
            if (streamInfo.videoFormatLastModified > 0L) {
                videoFormatFields.add(fieldVarint(2, streamInfo.videoFormatLastModified))
            }
            fieldBytes(1, concat(*videoFormatFields.toTypedArray()))
        } else {
            null
        }
        val clientInfo = concat(
            fieldVarint(16, streamInfo.clientName.toLong()),
            fieldBytes(17, streamInfo.clientVersion.toByteArray(Charsets.UTF_8)),
            fieldBytes(18, "Android".toByteArray(Charsets.UTF_8)),
            fieldBytes(19, "14".toByteArray(Charsets.UTF_8)),
            fieldVarint(64, 34L)
        )
        val streamerContextFields = mutableListOf(fieldBytes(1, clientInfo))
        streamInfo.streamingPoToken?.let { token ->
            streamerContextFields.add(fieldBytes(2, decodeBase64Url(token)))
        }
        val streamerContext = fieldBytes(19, concat(*streamerContextFields.toTypedArray()))
        val abrState = concat(
            fieldVarint(21, 360L),
            fieldVarint(28, playerTimeMs),
            // Match the current googlevideo SabrStream client ABR state. The
            // Android endpoint rejects an audio-only request when visibility
            // and playback rate are omitted, even though their defaults are
            // logically equivalent.
            fieldVarint(34, 1L),
            fieldFixed32(35, 1.0f),
            fieldVarint(40, 1L)
        )
        val config = decodeBase64Url(streamInfo.videoPlaybackUstreamerConfig)

        val parts = mutableListOf(
            fieldBytes(1, abrState),
            fieldBytes(5, config),
            fieldBytes(16, formatId),
            streamerContext
        )
        if (videoFormatId != null) {
            parts.add(fieldBytes(17, videoFormatId))

            // The reference SABR client marks the video format as fully
            // buffered when audio-only playback is requested. This is how it
            // discards video while still selecting an audio track server-side.
            parts.add(fieldBytes(3, buildDiscardedVideoRange(videoFormatId)))

            // On the first request only the discarded video format is listed
            // as selected. Once format initialization has been received, the
            // reference client lists both selected formats.
            parts.add(fieldBytes(2, videoFormatId))
        }
        if (selectedFormatSent) {
            parts.add(fieldBytes(2, formatId))
        }
        return concat(*parts.toTypedArray())
    }

    private fun buildDiscardedVideoRange(formatId: ByteArray): ByteArray {
        val timeRange = concat(
            fieldVarint(1, 2_147_483_647L),
            fieldVarint(2, 0L),
            fieldVarint(3, 1000L)
        )
        return concat(
            fieldBytes(1, formatId),
            fieldVarint(2, 0L),
            fieldVarint(3, 2_147_483_647L),
            fieldVarint(4, 2_147_483_647L),
            fieldVarint(5, 2_147_483_647L),
            fieldBytes(6, timeRange)
        )
    }

    private fun parseUmpResponse(body: ByteArray) {
        val cursor = Cursor()
        var currentHeader: MediaHeader? = null
        var currentMedia = ByteArrayOutputStream()
        var mediaAdded = false
        var responseEndTimeMs = nextTimeMs

        while (cursor.index < body.size) {
            val partType = readUmpVarInt(body, cursor)
            val partSize = readUmpVarInt(body, cursor)
            if (partSize < 0 || partSize > body.size - cursor.index) {
                throw IOException("Malformed YouTube UMP frame")
            }
            val part = body.copyOfRange(cursor.index, cursor.index + partSize)
            cursor.index += partSize

            when (partType) {
                FORMAT_INITIALIZATION_PART -> {
                    val duration = parseFormatDurationMs(part)
                    if (duration > 0L && streamInfo.durationMs <= 0L) {
                        responseEndTimeMs = maxOf(responseEndTimeMs, duration)
                    }
                }
                MEDIA_HEADER_PART -> {
                    currentHeader = parseMediaHeader(part)
                    currentMedia = ByteArrayOutputStream()
                }
                MEDIA_PART -> {
                    if (currentHeader != null) currentMedia.write(part)
                }
                MEDIA_END_PART -> {
                    val header = currentHeader
                    if (header != null) {
                        val media = currentMedia.toByteArray()
                        if (media.isNotEmpty()) {
                            if (header.isInitSegment) {
                                if (!initQueued) {
                                    pendingBuffers.addLast(media)
                                    initQueued = true
                                }
                            } else {
                                pendingBuffers.addLast(media)
                                mediaAdded = true
                                val duration = header.durationMs.takeIf { it > 0L }
                                    ?: DEFAULT_SEGMENT_DURATION_MS
                                responseEndTimeMs = maxOf(responseEndTimeMs, header.startMs + duration)
                            }
                        }
                    }
                    currentHeader = null
                    currentMedia = ByteArrayOutputStream()
                }
                SABR_ERROR_PART -> {
                    val details = describeProto(part)
                    logger("YouTube SABR error payload: $details")
                    throw IOException("YouTube returned a SABR error: $details")
                }
            }
        }

        if (mediaAdded) {
            nextTimeMs = responseEndTimeMs
            if (streamInfo.durationMs > 0L && nextTimeMs >= streamInfo.durationMs) {
                finished = true
            }
            logger("YouTube SABR: queued audio at ${nextTimeMs}ms")
        }
    }

    private fun parseFormatDurationMs(data: ByteArray): Long {
        var endTimeMs = -1L
        val reader = ProtoReader(data)
        while (true) {
            val field = reader.next() ?: break
            if (field.number == 3) endTimeMs = field.varint
        }
        return endTimeMs
    }

    private fun parseMediaHeader(data: ByteArray): MediaHeader {
        var isInit = false
        var sequence = 0
        var startMs = -1L
        var durationMs = -1L
        var contentLength = -1L
        var timeRange = TimeRange()
        val reader = ProtoReader(data)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                8 -> isInit = field.varint != 0L
                9 -> sequence = field.varint.toInt()
                11 -> startMs = field.varint
                12 -> durationMs = field.varint
                14 -> contentLength = field.varint
                15 -> timeRange = parseTimeRange(field.bytes)
            }
        }
        if (startMs < 0L) {
            startMs = (timeRange.startTicks * 1000L) / timeRange.timescale
        }
        if (durationMs <= 0L && timeRange.durationTicks > 0L) {
            durationMs = (timeRange.durationTicks * 1000L) / timeRange.timescale
        }
        return MediaHeader(isInit, sequence, startMs.coerceAtLeast(0L), durationMs, contentLength)
    }

    private fun parseTimeRange(data: ByteArray): TimeRange {
        var startTicks = 0L
        var durationTicks = 0L
        var timescale = 1
        val reader = ProtoReader(data)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> startTicks = field.varint
                2 -> durationTicks = field.varint
                3 -> timescale = field.varint.toInt().coerceAtLeast(1)
            }
        }
        return TimeRange(startTicks, durationTicks, timescale)
    }

    private fun describeProto(data: ByteArray): String {
        val fields = mutableListOf<String>()
        val reader = ProtoReader(data)
        while (true) {
            val field = reader.next() ?: break
            if (field.wireType == 0) {
                fields += "f${field.number}=${field.varint}"
            } else if (field.wireType == 2) {
                val text = field.bytes.toString(Charsets.UTF_8)
                    .filter { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
                fields += if (text.isNotBlank()) {
                    "f${field.number}=$text"
                } else {
                    "f${field.number}=bytes(${field.bytes.size})"
                }
            }
        }
        return fields.joinToString(",")
    }

    private inner class ProtoReader(private val data: ByteArray) {
        private val cursor = Cursor()

        fun next(): ProtoField? {
            if (cursor.index >= data.size) return null
            val tag = readProtoVarInt(data, cursor)
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 7L).toInt()
            return when (wireType) {
                0 -> ProtoField(number, wireType, varint = readProtoVarInt(data, cursor))
                1 -> {
                    cursor.index += 8
                    ProtoField(number, wireType)
                }
                2 -> {
                    val length = readProtoVarInt(data, cursor).toInt()
                    val end = (cursor.index + length).coerceAtMost(data.size)
                    val bytes = data.copyOfRange(cursor.index, end)
                    cursor.index = end
                    ProtoField(number, wireType, bytes = bytes)
                }
                5 -> {
                    cursor.index += 4
                    ProtoField(number, wireType)
                }
                else -> null
            }
        }
    }

    private fun readUmpVarInt(data: ByteArray, cursor: Cursor): Int {
        if (cursor.index >= data.size) throw IOException("Unexpected end of UMP response")
        val first = data[cursor.index++].toInt() and 0xff
        return when {
            first < 128 -> first
            first < 192 -> {
                val second = data[cursor.index++].toInt() and 0xff
                (first and 0x3f) + 64 * second
            }
            first < 224 -> {
                val second = data[cursor.index++].toInt() and 0xff
                val third = data[cursor.index++].toInt() and 0xff
                (first and 0x1f) + 32 * (second + 256 * third)
            }
            first < 240 -> {
                val second = data[cursor.index++].toInt() and 0xff
                val third = data[cursor.index++].toInt() and 0xff
                val fourth = data[cursor.index++].toInt() and 0xff
                (first and 0x0f) + 16 * (second + 256 * (third + 256 * fourth))
            }
            else -> {
                cursor.index++
                val bytes = data.copyOfRange(cursor.index, cursor.index + 4)
                cursor.index += 4
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            }
        }
    }

    private fun fieldVarint(number: Int, value: Long): ByteArray =
        concat(writeProtoVarInt((number shl 3).toLong()), writeProtoVarInt(value))

    private fun fieldBytes(number: Int, value: ByteArray): ByteArray =
        concat(
            writeProtoVarInt(((number shl 3) or 2).toLong()),
            writeProtoVarInt(value.size.toLong()),
            value
        )

    private fun fieldFixed32(number: Int, value: Float): ByteArray =
        concat(
            writeProtoVarInt(((number shl 3) or 5).toLong()),
            java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(value)
                .array()
        )

    private fun decodeBase64Url(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        return android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
    }

    private fun writeProtoVarInt(value: Long): ByteArray {
        var remaining = value
        val output = ByteArrayOutputStream()
        do {
            var next = (remaining and 0x7f).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) next = next or 0x80
            output.write(next)
        } while (remaining != 0L)
        return output.toByteArray()
    }

    private fun readProtoVarInt(data: ByteArray, cursor: Cursor): Long {
        var result = 0L
        var shift = 0
        while (cursor.index < data.size) {
            val next = data[cursor.index++].toInt() and 0xff
            result = result or ((next and 0x7f).toLong() shl shift)
            if ((next and 0x80) == 0) return result
            shift += 7
            if (shift > 63) throw IOException("Malformed protobuf varint")
        }
        throw IOException("Unexpected end of protobuf message")
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        arrays.forEach(output::write)
        return output.toByteArray()
    }
}

private fun String.toMediaType(): okhttp3.MediaType =
    toMediaTypeOrNull()!!
