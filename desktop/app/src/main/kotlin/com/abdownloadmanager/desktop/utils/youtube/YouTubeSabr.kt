package com.abdownloadmanager.desktop.utils.youtube

import ir.amirab.downloader.downloaditem.youtube.SabrDownloader
import ir.amirab.downloader.downloaditem.youtube.SabrFormat
import ir.amirab.downloader.downloaditem.youtube.SabrSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@Serializable
data class StoredYouTubeFormat(val itag: Int, val lastModified: String, val xtags: String? = null, val mimeType: String) {
    fun toSabr() = SabrFormat(itag, lastModified.toLongOrNull() ?: 0, xtags)
}

/** What the browser captured for one YouTube video; see SabrSession. */
@Serializable
data class StoredYouTubeSession(
    val videoId: String,
    val streamingUrl: String,
    val requestBody: String,
    val ustreamerConfig: String,
    val userAgent: String? = null,
    val video: StoredYouTubeFormat? = null,
    val audio: StoredYouTubeFormat,
) {
    fun toSabr() = SabrSession(
        videoId = videoId,
        streamingUrl = streamingUrl,
        // the ustreamer config is base64url, the captured body plain base64; accept either for both
        ustreamerConfig = decodeBase64(ustreamerConfig),
        playerRequestBody = decodeBase64(requestBody),
    )

    private fun decodeBase64(value: String): ByteArray =
        Base64.getUrlDecoder().decode(value.trim().replace('+', '-').replace('/', '_').trimEnd('='))
}

/** One small JSON file per captured session, named by a random key that goes into the download's URL. */
class YouTubeSessionStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(session: StoredYouTubeSession): String {
        dir.mkdirs()
        // sessions expire within hours on YouTube's side; a week covers paused or queued downloads
        val stale = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        dir.listFiles { f -> f.extension == "json" && f.lastModified() < stale }?.forEach { it.delete() }
        val key = UUID.randomUUID().toString().replace("-", "")
        File(dir, "$key.json").writeText(json.encodeToString(StoredYouTubeSession.serializer(), session))
        return key
    }

    fun load(key: String): StoredYouTubeSession? {
        if (!KEY.matches(key)) return null
        return runCatching {
            json.decodeFromString(StoredYouTubeSession.serializer(), File(dir, "$key.json").readText())
        }.getOrNull()
    }

    private companion object {
        val KEY = Regex("[0-9a-f]{32}")
    }
}

/**
 * Serves YouTube tracks to the normal HTTP download engine without a server: a download whose URL is
 * `https://youtube-sabr.adm.invalid/<session key>/<video|audio>` is answered in-process by streaming that
 * track over SABR. So YouTube downloads get the usual list entry, progress, queues and the ffmpeg merge.
 *
 * The answer has no length and no range support, so the engine downloads each track as one stream.
 * A reconnect asking for `bytes=N-` gets the track regenerated with its first N bytes skipped.
 */
class YouTubeSabrInterceptor(
    private val store: YouTubeSessionStore,
    private val sabrClient: OkHttpClient,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != HOST) return chain.proceed(request)
        val (key, track) = request.url.pathSegments.let { it.getOrNull(0).orEmpty() to it.getOrNull(1) }
        val session = store.load(key)
        val format = when (track) {
            "video" -> session?.video
            "audio" -> session?.audio
            else -> null
        }
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (session == null || format == null) {
            return builder.code(410).message("Gone")
                .body("This YouTube download can no longer be resumed; start it again from the browser.".toResponseBody())
                .build()
        }
        val skip = rangeStart(request.header("Range"))
        val downloader = SabrDownloader(
            client = sabrClient,
            session = session.toSabr(),
            audio = session.audio.toSabr(),
            // SABR serves video only together with audio, so the video track asks for both and drops the audio
            video = session.video?.takeIf { track == "video" }?.toSabr(),
            headers = SabrDownloader.DEFAULT_HEADERS + listOfNotNull(session.userAgent?.let { "User-Agent" to it }),
        )
        val body = SabrTrackBody(format.mimeType.substringBefore(';').toMediaTypeOrNull(), skip) { out, cancelled ->
            if (track == "video") {
                downloader.download(OutputStream.nullOutputStream(), out, isCancelled = cancelled)
            } else {
                downloader.download(out, null, isCancelled = cancelled)
            }
        }
        return builder.code(if (skip > 0) 206 else 200).message(if (skip > 0) "Partial Content" else "OK")
            .header("Content-Type", format.mimeType.substringBefore(';'))
            .apply { if (skip > 0) header("Content-Range", "bytes $skip-${Long.MAX_VALUE - 1}/*") }
            .body(body)
            .build()
    }

    companion object {
        const val HOST = "youtube-sabr.adm.invalid"

        fun trackUrl(key: String, track: String, videoId: String) = "https://$HOST/$key/$track?v=$videoId"

        internal fun rangeStart(range: String?): Long =
            range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0
    }
}

/**
 * A response body fed by a producer thread through a pipe. The producer starts on the first read, so the
 * engine's probe request (which only looks at headers) costs nothing. Closing the body stops the producer;
 * a producer failure surfaces as an IOException to the reader instead of a silent, truncated end.
 */
private class SabrTrackBody(
    private val mediaType: MediaType?,
    private val skip: Long,
    private val produce: (OutputStream, () -> Boolean) -> Unit,
) : ResponseBody() {
    @Volatile
    private var closed = false

    @Volatile
    private var failure: Throwable? = null
    private var started: BufferedSource? = null

    override fun contentType(): MediaType? = mediaType
    override fun contentLength(): Long = -1

    @Synchronized
    override fun source(): BufferedSource = started ?: start().also { started = it }

    @Synchronized
    override fun close() {
        closed = true
        started?.close()
    }

    private fun start(): BufferedSource {
        val input = PipedInputStream(1 shl 20)
        val output = PipedOutputStream(input)
        thread(isDaemon = true, name = "youtube-sabr") {
            try {
                produce(SkippingOutputStream(output, skip)) { closed }
                if (closed) throw IOException("cancelled")
            } catch (e: Throwable) {
                failure = e // set before closing, so the reader sees the error instead of a clean end
            } finally {
                runCatching { output.close() }
            }
        }
        return object : InputStream() {
            override fun read(): Int = check(input.read())
            override fun read(b: ByteArray, off: Int, len: Int): Int = check(input.read(b, off, len))
            override fun close() {
                closed = true
                input.close()
            }

            private fun check(n: Int): Int {
                if (n == -1) failure?.let { throw IOException(it.message ?: "YouTube stream failed", it) }
                return n
            }
        }.source().buffer()
    }
}

/** Drops the first [skip] bytes written; used to continue a track from where the file stopped. */
private class SkippingOutputStream(out: OutputStream, private var skip: Long) : FilterOutputStream(out) {
    override fun write(b: Int) {
        if (skip > 0) skip-- else out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val dropped = minOf(skip, len.toLong()).toInt()
        skip -= dropped
        if (len > dropped) out.write(b, off + dropped, len - dropped)
    }
}
