package com.abdownloadmanager.desktop.utils.ytdlp

import com.abdownloadmanager.desktop.utils.ExternalTools
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

sealed interface ResolvedMedia {
    /** Final file name including the extension of the container it will end up in. */
    val fileName: String

    /** One stream that already contains audio and video. */
    class Single(val credentials: IDownloadCredentials, override val fileName: String) : ResolvedMedia

    /** Separate video and audio streams that must be downloaded and then merged with ffmpeg. */
    class Merge(
        val video: IDownloadCredentials,
        val audio: IDownloadCredentials,
        val audioExtension: String,
        override val fileName: String,
    ) : ResolvedMedia
}

/**
 * Turns a web page URL (YouTube, Vimeo, ...) into direct media URLs by asking the external
 * yt-dlp executable. The result is fed into the normal HTTP/HLS download flow, so segmentation,
 * queues and resume all keep working. yt-dlp is only ever run as a separate process.
 *
 * It prefers the tallest separate video + audio pair when that beats the best single stream,
 * because sites like YouTube only serve high resolutions that way.
 */
object YtDlpResolver {
    private const val TIMEOUT_MS = 60_000L
    private val PAGE_EXTENSIONS = setOf("html", "htm", "php", "asp", "aspx", "jsp")
    private val SUPPORTED_PROTOCOLS = setOf("https", "http", "m3u8", "m3u8_native")
    private val json = Json { ignoreUnknownKeys = true }

    /** Cheap pre-filter so plain file links (zip, exe, ...) never pay for a yt-dlp round trip. */
    fun looksLikePage(link: String): Boolean {
        val path = runCatching { URI(link).path }.getOrNull() ?: return false
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return extension.isEmpty() || extension in PAGE_EXTENSIONS
    }

    /** Returns null when yt-dlp is missing, the site is unsupported, or no usable format exists. */
    suspend fun resolve(pageUrl: String): ResolvedMedia? {
        val executable = ExternalTools.find("yt-dlp.exe", "yt-dlp.yt-dlp")?.absolutePath ?: return null
        val output = runYtDlp(executable, pageUrl) ?: return null
        return runCatching { parse(output, pageUrl) }.getOrNull()
    }

    private suspend fun runYtDlp(executable: String, pageUrl: String): String? = withContext(Dispatchers.IO) {
        val process = runCatching {
            ProcessBuilder(
                executable, "-J", "--no-playlist", "--flat-playlist", "--no-warnings", "--encoding", "utf-8",
                "--use-extractors", "default,-generic", "--", pageUrl,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return@withContext null
        coroutineScope {
            val reader = async { process.inputStream.readBytes() }
            try {
                val bytes = withTimeoutOrNull(TIMEOUT_MS) { reader.await() }
                if (bytes == null || process.waitFor() != 0) null else bytes.decodeToString()
            } finally {
                // On timeout or when the dialog is closed (cancellation). yt-dlp.exe is a PyInstaller build that
                // runs as a parent plus a child; the child holds stdout open, so kill the whole tree to unblock the read.
                if (process.isAlive || !reader.isCompleted) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly()
                }
            }
        }
    }

    internal fun parse(output: String, pageUrl: String): ResolvedMedia? {
        val info = json.parseToJsonElement(output).jsonObject
        // a playlist page has entries instead of formats; batch download is a separate feature
        if (info["_type"]?.jsonPrimitive?.contentOrNull == "playlist") return null
        val title = info["title"]?.jsonPrimitive?.contentOrNull ?: "video"
        val formats = info["formats"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            .filter { it.protocol() in SUPPORTED_PROTOCOLS }

        val combined = formats.filter { it.hasVideo() && it.hasAudio() }
            .maxByOrNull { it.height() * 10_000 + it.bitrate() }
        // HLS variants are not paired: only plain http(s) streams can be merged safely
        val plain = formats.filter { !it.protocol().startsWith("m3u8") }
        val video = plain.filter { it.hasVideo() && !it.hasAudio() }
            .maxWithOrNull(compareBy({ it.height() }, { it.codecRank() }, { it.bitrate() }))
        val audio = video?.let { pickAudio(plain, it) }

        if (video != null && audio != null && (combined == null || video.height() > combined.height())) {
            val container = containerFor(video.ext(), audio.ext())
            return ResolvedMedia.Merge(
                video = credentialsOf(video, pageUrl),
                audio = credentialsOf(audio, pageUrl),
                audioExtension = audio.ext(),
                fileName = "$title.$container",
            )
        }
        combined ?: return null
        val isHls = combined.protocol().startsWith("m3u8")
        return ResolvedMedia.Single(credentialsOf(combined, pageUrl), "$title.${if (isHls) "mp4" else combined.ext()}")
    }

    /** Best audio stream, preferring one that fits the video's container so ffmpeg can copy it. */
    private fun pickAudio(plain: List<JsonObject>, video: JsonObject): JsonObject? {
        val audioOnly = plain.filter { !it.hasVideo() && it.hasAudio() }
        val preferredExt = when (video.ext()) {
            "mp4" -> "m4a"
            "webm" -> "webm"
            else -> null
        }
        return audioOnly.filter { it.ext() == preferredExt }.ifEmpty { audioOnly }
            .maxByOrNull { it["abr"]?.jsonPrimitive?.doubleOrNull ?: it.bitrate() }
    }

    /** mp4 and webm only accept their own codecs; mkv takes anything when streams are copied. */
    internal fun containerFor(videoExt: String, audioExt: String): String = when {
        videoExt == "mp4" && audioExt in setOf("m4a", "mp4") -> "mp4"
        videoExt == "webm" && audioExt == "webm" -> "webm"
        else -> "mkv"
    }

    private fun credentialsOf(format: JsonObject, pageUrl: String): IDownloadCredentials {
        val url = requireNotNull(format["url"]?.jsonPrimitive?.contentOrNull) { "format has no url" }
        val headers = format["http_headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val otherHeaders = headers.filterKeys { !it.equals("User-Agent", true) }.ifEmpty { null }
        return if (format.protocol().startsWith("m3u8")) {
            HLSDownloadCredentials(link = url, headers = otherHeaders, userAgent = userAgent, downloadPage = pageUrl)
        } else {
            HttpDownloadCredentials(link = url, headers = otherHeaders, userAgent = userAgent, downloadPage = pageUrl)
        }
    }

    private fun JsonObject.protocol() = this["protocol"]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.ext() = this["ext"]?.jsonPrimitive?.contentOrNull ?: "mp4"
    private fun JsonObject.height() = this["height"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    private fun JsonObject.bitrate() = this["tbr"]?.jsonPrimitive?.doubleOrNull ?: 0.0
    private fun JsonObject.hasVideo() = this["vcodec"].present()
    private fun JsonObject.hasAudio() = this["acodec"].present()
    // yt-dlp writes "none" for a missing track; a missing field only means the extractor did not say
    private fun JsonElement?.present() = this?.jsonPrimitive?.contentOrNull != "none"

    /** At equal height prefer the codec most players can decode: h264, then vp9, then av1. */
    private fun JsonObject.codecRank(): Int {
        val codec = this["vcodec"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when {
            codec.startsWith("avc1") -> 3
            codec.startsWith("vp9") || codec.startsWith("vp09") -> 2
            codec.startsWith("av01") -> 1
            else -> 0
        }
    }
}
