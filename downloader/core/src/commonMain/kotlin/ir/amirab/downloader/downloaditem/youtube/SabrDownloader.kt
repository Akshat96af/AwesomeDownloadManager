package ir.amirab.downloader.downloaditem.youtube

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.TreeMap

/** One YouTube stream format, as listed in the player's `adaptiveFormats`. */
data class SabrFormat(val itag: Int, val lastModified: Long, val xtags: String? = null) {
    fun toProto(): ProtoWriter = ProtoWriter().int(1, itag).varint(2, lastModified).string(3, xtags)
}

/**
 * What a SABR download needs from the YouTube page the user has open:
 * - [streamingUrl]: the `videoplayback?sabr=1` URL exactly as the page's player requested it
 *   (the player rewrites its `n` parameter; the unmodified one from the player data gets HTTP 403),
 * - [ustreamerConfig]: `playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig`,
 * - [playerRequestBody]: one request body the player sent, for its client info and proof-of-origin token.
 */
class SabrSession(
    val videoId: String,
    val streamingUrl: String,
    val ustreamerConfig: ByteArray,
    playerRequestBody: ByteArray?,
    fallbackClientVersion: String = "2.20261030.02.00",
) {
    /** ClientInfo as the browser sent it; re-sent unchanged so the server sees the same client. */
    val clientInfo: ByteArray
    val poToken: ByteArray?

    init {
        val context = playerRequestBody?.let { ProtoMessage.parse(it).message(19) }
        clientInfo = context?.bytes(1)
            ?: ProtoWriter().int(16, 1).string(17, fallbackClientVersion).toByteArray() // 1 = WEB
        poToken = context?.bytes(2)?.takeIf { it.isNotEmpty() }
    }
}

class SabrException(message: String) : IOException(message)

/** Where one track (video or audio) stands; enough to resume a download where it stopped. */
class SabrTrackState(
    var initWritten: Boolean = false,
    /** Highest segment number written; segments are written strictly in order, 1, 2, 3, ... */
    var lastSegment: Int = 0,
    var bufferedEndMs: Long = 0,
    /** Total segments, once the server says. */
    var endSegment: Int? = null,
    var bytesWritten: Long = 0,
) {
    val done get() = endSegment?.let { lastSegment >= it } == true
}

/**
 * Downloads one video's audio (and optionally video) track over YouTube's SABR protocol: the client keeps
 * POSTing what it has buffered, and the server answers with the next media segments in UMP framing.
 * Segments are written to the outputs in order (init segment first), giving plain fragmented MP4 / WebM
 * files that ffmpeg can merge.
 *
 * Message and field numbers follow the protocol definitions in LuanRT/GoogleVideo (MIT).
 * Not handled: live streams, server-stitched ads (their context updates are echoed back but not interpreted),
 * minting a new proof-of-origin token (a rejected token ends the download with an error).
 */
class SabrDownloader(
    private val client: OkHttpClient,
    private val session: SabrSession,
    private val audio: SabrFormat,
    private val video: SabrFormat?,
    private val headers: Map<String, String> = DEFAULT_HEADERS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    private class Track(val format: SabrFormat, val out: OutputStream, val state: SabrTrackState) {
        /** The FormatId as the server sent it in FORMAT_INITIALIZATION_METADATA. */
        var serverFormatId: ByteArray? = null
        val waiting = TreeMap<Int, Pair<ByteArray, Long>>()

        fun write(bytes: ByteArray, onBytes: (Long) -> Unit) {
            out.write(bytes)
            state.bytesWritten += bytes.size
            onBytes(bytes.size.toLong())
        }

        fun accept(segment: Segment, onBytes: (Long) -> Unit) {
            if (segment.isInit) {
                if (!state.initWritten) {
                    write(segment.data.toByteArray(), onBytes)
                    state.initWritten = true
                }
            } else {
                val number = segment.number ?: (state.lastSegment + 1)
                if (number > state.lastSegment) waiting[number] = segment.data.toByteArray() to segment.endMs
            }
            // media segments wait for the init segment, and for any earlier segment still missing
            while (state.initWritten) {
                val (bytes, endMs) = waiting.remove(state.lastSegment + 1) ?: break
                write(bytes, onBytes)
                state.lastSegment++
                state.bufferedEndMs = endMs
            }
        }
    }

    private class Segment(val itag: Int, val isInit: Boolean, val number: Int?, val endMs: Long) {
        val data = ByteArrayOutputStream()
    }

    private var streamingUrl = session.streamingUrl
    private var requestNumber = 0
    private var playbackCookie: ByteArray? = null
    private var backoffMs = 0L
    private val contexts = HashMap<Int, Pair<ByteArray, Boolean>>() // type -> (value, send it)

    /**
     * Blocks until every selected track is complete. [onBytes] gets the size of each piece of media written;
     * [isCancelled] is checked between requests. Throws [SabrException] when YouTube refuses or the stream stalls.
     */
    fun download(
        audioOut: OutputStream,
        videoOut: OutputStream?,
        audioState: SabrTrackState = SabrTrackState(),
        videoState: SabrTrackState = SabrTrackState(),
        onBytes: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        require((video == null) == (videoOut == null)) { "video format and video output go together" }
        val tracks = listOfNotNull(
            Track(audio, audioOut, audioState),
            video?.let { Track(it, videoOut!!, videoState) },
        )
        val startedAt = now()
        var requestsWithoutProgress = 0
        while (!tracks.all { it.state.done }) {
            if (isCancelled()) return
            if (backoffMs > 0) sleep(backoffMs)
            val before = tracks.sumOf { it.state.lastSegment }
            val playerTimeMs = tracks.filter { !it.state.done }.minOf { it.state.bufferedEndMs }
            fetch(buildRequest(tracks, playerTimeMs, now() - startedAt), tracks, onBytes)
            if (tracks.sumOf { it.state.lastSegment } > before) {
                requestsWithoutProgress = 0
            } else if (++requestsWithoutProgress >= MAX_REQUESTS_WITHOUT_PROGRESS) {
                throw SabrException("YouTube stopped sending this video")
            }
        }
        tracks.forEach { it.out.flush() }
    }

    private fun buildRequest(tracks: List<Track>, playerTimeMs: Long, elapsedMs: Long): ByteArray {
        val abrState = ProtoWriter()
            .bool(22, false) // client_viewport_is_flexible
            .varint(28, playerTimeMs)
            .varint(29, elapsedMs) // time_since_last_seek
            .int(34, 2) // visibility: fullscreen
            .varint(36, elapsedMs) // elapsed_wall_time_ms
            .varint(39, elapsedMs) // time_since_last_action_ms
            .int(40, if (video == null) 1 else 0) // enabled tracks: audio only / audio + video
            .bool(46, false) // drc
            .bool(71, true) // enable_sabr_host_fallback
        val request = ProtoWriter().message(1, abrState)
        for (track in tracks) {
            val id = track.serverFormatId ?: continue
            request.bytes(2, id) // initialization_format_ids
            if (track.state.lastSegment > 0) {
                request.message(
                    3, ProtoWriter() // buffered_ranges
                        .bytes(1, id)
                        .varint(2, 0)
                        .varint(3, track.state.bufferedEndMs)
                        .int(4, 1)
                        .int(5, track.state.lastSegment)
                        .message(6, ProtoWriter().varint(1, 0).varint(2, track.state.bufferedEndMs).int(3, 1000))
                )
            }
        }
        request.bytes(5, session.ustreamerConfig)
        request.message(16, audio.toProto())
        video?.let { request.message(17, it.toProto()) }
        val context = ProtoWriter()
            .bytes(1, session.clientInfo)
            .bytes(2, session.poToken)
            .bytes(3, playbackCookie)
        for ((type, entry) in contexts) {
            if (entry.second) context.message(5, ProtoWriter().int(1, type).bytes(2, entry.first))
            else context.int(6, type)
        }
        return request.message(19, context).toByteArray()
    }

    private fun fetch(body: ByteArray, tracks: List<Track>, onBytes: (Long) -> Unit) {
        val url = streamingUrl.toHttpUrl().newBuilder().setQueryParameter("rn", (requestNumber++).toString()).build()
        val request = Request.Builder().url(url)
            .post(body.toRequestBody("application/x-protobuf".toMediaType()))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "application/vnd.yt-ump")
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SabrException(
                    if (response.code == 403) "YouTube refused the stream (HTTP 403); reopen the video page and try again"
                    else "YouTube answered HTTP ${response.code}"
                )
            }
            val segments = HashMap<Int, Segment>()
            val input = response.body.byteStream()
            while (true) {
                val (type, part) = readUmpPart(input) ?: break
                handlePart(type, part, segments, tracks, onBytes)
            }
        }
    }

    private fun handlePart(type: Int, part: ByteArray, segments: HashMap<Int, Segment>, tracks: List<Track>, onBytes: (Long) -> Unit) {
        when (type) {
            MEDIA_HEADER -> {
                val header = ProtoMessage.parse(part)
                val itag = header.int(3) ?: header.message(13)?.int(1) ?: return
                val startMs = header.long(11) ?: header.message(15)?.ticksMs(1) ?: 0
                val durationMs = header.long(12) ?: header.message(15)?.ticksMs(2) ?: 0
                segments[header.int(1) ?: 0] = Segment(itag, header.bool(8), header.int(9), startMs + durationMs)
            }

            MEDIA -> segments[part[0].toInt() and 0xFF]?.data?.write(part, 1, part.size - 1)
            MEDIA_END -> segments.remove(part[0].toInt() and 0xFF)?.let { segment ->
                tracks.firstOrNull { it.format.itag == segment.itag }?.accept(segment, onBytes)
            }

            FORMAT_INITIALIZATION_METADATA -> {
                val metadata = ProtoMessage.parse(part)
                val formatId = metadata.bytes(2) ?: return
                val itag = ProtoMessage.parse(formatId).int(1)
                tracks.firstOrNull { it.format.itag == itag }?.let { track ->
                    track.serverFormatId = formatId
                    metadata.int(4)?.takeIf { it > 0 }?.let { track.state.endSegment = it }
                }
            }

            NEXT_REQUEST_POLICY -> {
                val policy = ProtoMessage.parse(part)
                backoffMs = (policy.long(4) ?: 0).coerceIn(0, 60_000)
                policy.bytes(7)?.let { playbackCookie = it }
            }

            SABR_REDIRECT -> ProtoMessage.parse(part).string(1)?.let { streamingUrl = it }
            SABR_ERROR -> {
                val error = ProtoMessage.parse(part)
                throw SabrException("YouTube stream error: ${error.string(1).orEmpty()} ${error.int(2) ?: ""}".trim())
            }

            RELOAD_PLAYER_RESPONSE ->
                throw SabrException("YouTube asked to reload the video; reopen the video page and try again")

            STREAM_PROTECTION_STATUS -> if (ProtoMessage.parse(part).int(1) == 3) {
                throw SabrException("YouTube rejected the browser's proof of origin; play the video in the browser and try again")
            }

            SABR_CONTEXT_UPDATE -> {
                val update = ProtoMessage.parse(part)
                val contextType = update.int(1) ?: return
                val value = update.bytes(3)?.takeIf { it.isNotEmpty() } ?: return
                if (update.int(5) == KEEP_EXISTING && contextType in contexts) return
                contexts[contextType] = value to (update.bool(4) || contexts[contextType]?.second == true)
            }

            SABR_CONTEXT_SENDING_POLICY -> {
                val policy = ProtoMessage.parse(part)
                policy.ints(1).forEach { t -> contexts[t]?.let { contexts[t] = it.first to true } }
                policy.ints(2).forEach { t -> contexts[t]?.let { contexts[t] = it.first to false } }
                policy.ints(3).forEach { contexts.remove(it) }
            }
        }
    }

    private fun ProtoMessage.ticksMs(field: Int): Long? {
        val ticks = long(field) ?: return null
        val timescale = long(3)?.takeIf { it > 0 } ?: 1000
        return ticks * 1000 / timescale
    }

    companion object {
        const val MEDIA_HEADER = 20
        const val MEDIA = 21
        const val MEDIA_END = 22
        const val NEXT_REQUEST_POLICY = 35
        const val FORMAT_INITIALIZATION_METADATA = 42
        const val SABR_REDIRECT = 43
        const val SABR_ERROR = 44
        const val RELOAD_PLAYER_RESPONSE = 46
        const val SABR_CONTEXT_UPDATE = 57
        const val STREAM_PROTECTION_STATUS = 58
        const val SABR_CONTEXT_SENDING_POLICY = 59
        private const val KEEP_EXISTING = 2
        private const val MAX_REQUESTS_WITHOUT_PROGRESS = 10

        val DEFAULT_HEADERS = mapOf(
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        )
    }
}
