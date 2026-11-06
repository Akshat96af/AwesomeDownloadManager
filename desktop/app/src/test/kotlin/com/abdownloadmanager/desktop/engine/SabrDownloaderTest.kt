package com.abdownloadmanager.desktop.engine

import com.abdownloadmanager.desktop.utils.youtube.StoredYouTubeFormat
import com.abdownloadmanager.desktop.utils.youtube.StoredYouTubeSession
import com.abdownloadmanager.desktop.utils.youtube.YouTubeSabrInterceptor
import com.abdownloadmanager.desktop.utils.youtube.YouTubeSessionStore
import com.sun.net.httpserver.HttpServer
import ir.amirab.downloader.downloaditem.youtube.ProtoMessage
import ir.amirab.downloader.downloaditem.youtube.ProtoWriter
import ir.amirab.downloader.downloaditem.youtube.SabrDownloader
import ir.amirab.downloader.downloaditem.youtube.SabrException
import ir.amirab.downloader.downloaditem.youtube.SabrFormat
import ir.amirab.downloader.downloaditem.youtube.SabrSession
import ir.amirab.downloader.downloaditem.youtube.SabrTrackState
import ir.amirab.downloader.downloaditem.youtube.readUmpPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SabrDownloaderTest {
    private val audio = SabrFormat(itag = 140, lastModified = 1719185020722767)

    private fun umpVarint(v: Int): ByteArray = if (v < 128) byteArrayOf(v.toByte())
    else byteArrayOf(0xF0.toByte(), v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun part(type: Int, payload: ByteArray) = umpVarint(type) + umpVarint(payload.size) + payload

    private fun formatInit(format: SabrFormat, endSegment: Int) = part(
        SabrDownloader.FORMAT_INITIALIZATION_METADATA,
        ProtoWriter().string(1, "vid").message(2, format.toProto()).int(4, endSegment).toByteArray(),
    )

    /** A whole segment: header, its bytes in two MEDIA parts, then MEDIA_END. A null number is the init segment. */
    private fun segment(headerId: Int, itag: Int, number: Int?, bytes: String, startMs: Long = 0, durationMs: Long = 5000): ByteArray {
        val header = ProtoWriter().int(1, headerId).string(2, "vid").int(3, itag)
            .bool(8, number == null).int(9, number).varint(11, startMs).varint(12, durationMs).toByteArray()
        val data = bytes.encodeToByteArray()
        val half = data.size / 2
        return part(SabrDownloader.MEDIA_HEADER, header) +
                part(SabrDownloader.MEDIA, byteArrayOf(headerId.toByte()) + data.copyOfRange(0, half)) +
                part(SabrDownloader.MEDIA, byteArrayOf(headerId.toByte()) + data.copyOfRange(half, data.size)) +
                part(SabrDownloader.MEDIA_END, byteArrayOf(headerId.toByte()))
    }

    /** Serves [responses] in order (repeating the last) and keeps every request body it received. */
    private fun fakeYouTube(responses: List<ByteArray>, bodies: MutableList<ByteArray>): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/videoplayback") { exchange ->
                bodies += exchange.requestBody.readAllBytes()
                val response = responses[(bodies.size - 1).coerceAtMost(responses.size - 1)]
                exchange.responseHeaders.add("Content-Type", "application/vnd.yt-ump")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

    private fun session(server: HttpServer) = SabrSession(
        videoId = "vid",
        streamingUrl = "http://127.0.0.1:${server.address.port}/videoplayback?sabr=1&n=x",
        ustreamerConfig = byteArrayOf(1, 2, 3),
        playerRequestBody = ProtoWriter().message(19, ProtoWriter().bytes(1, byteArrayOf(9)).bytes(2, byteArrayOf(7, 7))).toByteArray(),
    )

    @Test
    fun umpPartsUseTheirOwnVarint() {
        val input = ByteArrayInputStream(part(21, ByteArray(300) { 1 }) + part(22, byteArrayOf(5)))
        assertEquals(300, readUmpPart(input)!!.second.size)
        assertEquals(listOf<Byte>(5), readUmpPart(input)!!.second.toList())
        assertEquals(null, readUmpPart(input))
        // two-byte form: low 6 bits in the first byte, the rest in the second
        val twoByte = byteArrayOf((0x80 or (300 and 0x3F)).toByte(), (300 shr 6).toByte(), 0)
        assertEquals(300, readUmpPart(ByteArrayInputStream(twoByte))!!.first)
    }

    @Test
    fun segmentsAreWrittenInOrderAfterTheInitSegmentAndTheRequestReportsWhatIsBuffered() {
        val bodies = mutableListOf<ByteArray>()
        val first = formatInit(audio, endSegment = 3) +
                segment(1, 140, number = 2, bytes = "seg2", startMs = 5000) + // before init and out of order
                segment(0, 140, number = null, bytes = "INIT") +
                segment(2, 140, number = 1, bytes = "seg1") +
                part(SabrDownloader.NEXT_REQUEST_POLICY, ProtoWriter().bytes(7, byteArrayOf(42)).toByteArray())
        val second = segment(0, 140, number = 3, bytes = "seg3", startMs = 10_000)
        val server = fakeYouTube(listOf(first, second), bodies)
        try {
            val out = ByteArrayOutputStream()
            val state = SabrTrackState()
            SabrDownloader(OkHttpClient(), session(server), audio, video = null, sleep = {}).download(out, null, audioState = state)
            assertEquals("INITseg1seg2seg3", out.toString())
            assertTrue(state.done)
            assertEquals(2, bodies.size)

            val request = ProtoMessage.parse(bodies[1])
            assertEquals(10_000L, request.message(1)!!.long(28)) // player time = end of segment 2
            assertEquals(1, request.message(1)!!.int(40)) // audio only
            val buffered = request.message(3)!!
            assertEquals(140, buffered.message(1)!!.int(1))
            assertEquals(2, buffered.int(5)) // last segment held
            val context = request.message(19)!!
            assertContentEquals(byteArrayOf(9), context.bytes(1)) // the browser's client info, unchanged
            assertContentEquals(byteArrayOf(7, 7), context.bytes(2)) // its proof-of-origin token
            assertContentEquals(byteArrayOf(42), context.bytes(3)) // the playback cookie the server handed out
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aRejectedProofOfOriginStopsWithAClearError() {
        val server = fakeYouTube(
            listOf(part(SabrDownloader.STREAM_PROTECTION_STATUS, ProtoWriter().int(1, 3).toByteArray())),
            mutableListOf(),
        )
        try {
            val error = assertFailsWith<SabrException> {
                SabrDownloader(OkHttpClient(), session(server), audio, video = null, sleep = {}).download(ByteArrayOutputStream(), null)
            }
            assertTrue("proof of origin" in error.message!!)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theInterceptorServesATrackAsAPlainHttpDownloadAndCanContinueIt() {
        val bodies = mutableListOf<ByteArray>()
        val response = formatInit(audio, endSegment = 2) +
                segment(0, 140, number = null, bytes = "INIT") +
                segment(1, 140, number = 1, bytes = "seg1") +
                segment(2, 140, number = 2, bytes = "seg2", startMs = 5000)
        val server = fakeYouTube(listOf(response), bodies)
        val dir = kotlin.io.path.createTempDirectory("yt-sessions").toFile()
        try {
            val store = YouTubeSessionStore(dir)
            val key = store.save(
                StoredYouTubeSession(
                    videoId = "vid",
                    streamingUrl = "http://127.0.0.1:${server.address.port}/videoplayback?sabr=1",
                    requestBody = Base64.getEncoder().encodeToString(ProtoWriter().message(19, ProtoWriter().bytes(2, byteArrayOf(7))).toByteArray()),
                    ustreamerConfig = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3)),
                    audio = StoredYouTubeFormat(140, "1719185020722767", mimeType = "audio/mp4; codecs=\"mp4a.40.2\""),
                )
            )
            val client = OkHttpClient.Builder().addInterceptor(YouTubeSabrInterceptor(store, OkHttpClient())).build()
            fun get(url: String, range: String? = null) = client.newCall(
                Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()
            ).execute()
            val audioUrl = YouTubeSabrInterceptor.trackUrl(key, "audio", "vid")

            // the engine's probe only reads headers: no stream may start
            get(audioUrl, "bytes=0-255").use {
                assertEquals(200, it.code)
                assertEquals(null, it.header("Content-Length"))
            }
            assertEquals(0, bodies.size)

            get(audioUrl, "bytes=0-").use { assertEquals("INITseg1seg2", it.body.string()) }
            get(audioUrl, "bytes=6-").use {
                assertEquals(206, it.code)
                assertTrue(it.header("Content-Range")!!.startsWith("bytes 6-"))
                assertEquals("g1seg2", it.body.string())
            }
            // this session has no video track, and an unknown session cannot be served
            get(YouTubeSabrInterceptor.trackUrl(key, "video", "vid")).use { assertEquals(410, it.code) }
            get("https://${YouTubeSabrInterceptor.HOST}/${"0".repeat(32)}/audio").use { assertEquals(410, it.code) }
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFailingTrackEndsWithAnErrorNotAShortFile() {
        val server = fakeYouTube(
            listOf(
                formatInit(audio, endSegment = 2) + segment(0, 140, null, "INIT") +
                        part(SabrDownloader.SABR_ERROR, ProtoWriter().string(1, "boom").toByteArray())
            ),
            mutableListOf(),
        )
        val dir = kotlin.io.path.createTempDirectory("yt-sessions").toFile()
        try {
            val store = YouTubeSessionStore(dir)
            val key = store.save(
                StoredYouTubeSession(
                    videoId = "vid",
                    streamingUrl = "http://127.0.0.1:${server.address.port}/videoplayback?sabr=1",
                    requestBody = "",
                    ustreamerConfig = "AQID",
                    audio = StoredYouTubeFormat(140, "1719185020722767", mimeType = "audio/mp4"),
                )
            )
            val client = OkHttpClient.Builder().addInterceptor(YouTubeSabrInterceptor(store, OkHttpClient())).build()
            val error = assertFailsWith<java.io.IOException> {
                client.newCall(Request.Builder().url(YouTubeSabrInterceptor.trackUrl(key, "audio", "vid")).build())
                    .execute().use { it.body.string() }
            }
            assertTrue("boom" in error.message.orEmpty())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    /**
     * Live check, off by default: ADM_SABR_SESSION=<json file> with videoId, url (as the page's player requested it),
     * ustreamer (base64url), video {itag, lmt}, audio {itag, lmt}, optional requestBody (base64).
     * Downloads both tracks into the temp folder and prints their sizes.
     */
    @Test
    fun liveDownloadFromACapturedSession() {
        val path = System.getenv("ADM_SABR_SESSION") ?: return
        val json = Json.parseToJsonElement(File(path).readText()).jsonObject
        fun format(key: String) = json.getValue(key).jsonObject.let {
            SabrFormat(it.getValue("itag").jsonPrimitive.int, it.getValue("lmt").jsonPrimitive.content.toLong())
        }
        val session = SabrSession(
            videoId = json.getValue("videoId").jsonPrimitive.content,
            streamingUrl = json.getValue("url").jsonPrimitive.content,
            ustreamerConfig = Base64.getUrlDecoder().decode(json.getValue("ustreamer").jsonPrimitive.content.trimEnd('=')),
            playerRequestBody = json["requestBody"]?.jsonPrimitive?.content?.let { Base64.getDecoder().decode(it) },
        )
        val audioFile = File.createTempFile("sabr-audio", ".m4a")
        val videoFile = File.createTempFile("sabr-video", ".mp4")
        val audioState = SabrTrackState()
        val videoState = SabrTrackState()
        val started = System.nanoTime()
        var total = 0L
        try {
            audioFile.outputStream().buffered().use { a ->
                videoFile.outputStream().buffered().use { v ->
                    SabrDownloader(OkHttpClient(), session, format("audio"), format("video"))
                        .download(a, v, audioState, videoState, onBytes = { total += it })
                }
            }
        } finally {
            val seconds = (System.nanoTime() - started) / 1e9
            println("LIVE SABR audio: ${audioFile.length()} B, segments ${audioState.lastSegment}/${audioState.endSegment} -> $audioFile")
            println("LIVE SABR video: ${videoFile.length()} B, segments ${videoState.lastSegment}/${videoState.endSegment} -> $videoFile")
            println("LIVE SABR: %.1f s, %.2f MB/s".format(seconds, total / 1e6 / seconds))
        }
        assertTrue(audioState.done && videoState.done)
    }
}
