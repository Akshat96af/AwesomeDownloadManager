package com.abdownloadmanager.desktop.utils.ytdlp

import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpResolverTest {
    private fun format(
        id: String, protocol: String, v: String, a: String, height: Int, ext: String = "mp4", abr: Int = 0,
    ) = """{"format_id":"$id","url":"https://cdn.example/$id","protocol":"$protocol","vcodec":"$v","acodec":"$a",
            "height":$height,"tbr":$height,"abr":$abr,"ext":"$ext",
            "http_headers":{"User-Agent":"UA/1","Accept":"*/*"}}"""

    private fun info(vararg formats: String) = """{"title":"My Clip","formats":[${formats.joinToString(",")}]}"""

    private fun resolve(vararg formats: String) = YtDlpResolver.parse(info(*formats), "https://site.example/p")

    @Test
    fun picksTallestCombinedFormatWhenNoTallerSeparateVideoExists() {
        val media = resolve(
            format("low", "https", "avc1", "mp4a", 240),
            format("mid", "https", "avc1", "mp4a", 360),
            format("video-only-360", "https", "avc1", "none", 360),
            format("audio-only", "https", "none", "mp4a", 0, ext = "m4a", abr = 128),
        )!!
        val single = assertIs<ResolvedMedia.Single>(media)
        val credentials = assertIs<HttpDownloadCredentials>(single.credentials)
        assertEquals("https://cdn.example/mid", credentials.link)
        assertEquals("My Clip.mp4", media.fileName)
        // User-Agent travels in its own field, everything else stays a header
        assertEquals("UA/1", credentials.userAgent)
        assertEquals(mapOf("Accept" to "*/*"), credentials.headers)
    }

    @Test
    fun mergesTallerSeparateStreamsAndKeepsContainerCompatible() {
        val media = resolve(
            format("combined-360", "https", "avc1", "mp4a", 360),
            format("v-1080-h264", "https", "avc1", "none", 1080),
            format("v-1080-vp9", "https", "vp09", "none", 1080, ext = "webm"),
            format("a-webm", "https", "none", "opus", 0, ext = "webm", abr = 160),
            format("a-m4a", "https", "none", "mp4a", 0, ext = "m4a", abr = 128),
        )!!
        val merge = assertIs<ResolvedMedia.Merge>(media)
        // same height: h264 beats vp9, and the m4a audio is chosen so ffmpeg can copy into mp4
        assertEquals("https://cdn.example/v-1080-h264", (merge.video as HttpDownloadCredentials).link)
        assertEquals("https://cdn.example/a-m4a", (merge.audio as HttpDownloadCredentials).link)
        assertEquals("m4a", merge.audioExtension)
        assertEquals("My Clip.mp4", merge.fileName)
    }

    @Test
    fun webmVideoWithWebmAudioMergesIntoWebm() {
        val media = resolve(
            format("v", "https", "vp09", "none", 2160, ext = "webm"),
            format("a", "https", "none", "opus", 0, ext = "webm", abr = 160),
        )!!
        assertEquals("My Clip.webm", assertIs<ResolvedMedia.Merge>(media).fileName)
    }

    @Test
    fun mismatchedCodecsFallBackToMkv() {
        assertEquals("mkv", YtDlpResolver.containerFor("webm", "m4a"))
        assertEquals("mkv", YtDlpResolver.containerFor("mp4", "webm"))
        assertEquals("mp4", YtDlpResolver.containerFor("mp4", "m4a"))
    }

    @Test
    fun hlsFormatBecomesHlsCredentialsWithMp4Name() {
        val media = resolve(format("hls-720", "m3u8_native", "avc1", "mp4a", 720))!!
        assertIs<HLSDownloadCredentials>(assertIs<ResolvedMedia.Single>(media).credentials)
        assertEquals("My Clip.mp4", media.fileName)
    }

    @Test
    fun hlsVariantsAreNeverPairedForMerging() {
        assertNull(
            resolve(
                format("v", "m3u8_native", "avc1", "none", 1080),
                format("a", "m3u8_native", "none", "mp4a", 0, ext = "m4a"),
            )
        )
    }

    @Test
    fun returnsNullWhenThereIsNoVideoAtAll() {
        assertNull(resolve(format("a", "https", "none", "mp4a", 0, ext = "m4a")))
    }

    @Test
    fun formatsWithoutCodecInfoAreTreatedAsCompleteFiles() {
        // many extractors give only url and ext; a missing codec field means "unknown", not "no track"
        val media = YtDlpResolver.parse(
            """{"title":"Clip","formats":[{"url":"https://cdn.example/f.mp4","protocol":"https","ext":"mp4"}]}""",
            "https://site.example/p",
        )!!
        assertEquals("https://cdn.example/f.mp4", ((media as ResolvedMedia.Single).credentials as HttpDownloadCredentials).link)
    }

    @Test
    fun playlistPagesFallBackToTheNormalFlow() {
        assertNull(YtDlpResolver.parse("""{"_type":"playlist","title":"Mix","entries":[]}""", "https://site.example/list"))
    }

    @Test
    fun onlyPageLikeLinksAreProbed() {
        assertTrue(YtDlpResolver.looksLikePage("https://www.youtube.com/watch?v=abc"))
        assertTrue(YtDlpResolver.looksLikePage("https://site.example/video/123.html"))
        assertFalse(YtDlpResolver.looksLikePage("https://site.example/files/setup.exe"))
        assertFalse(YtDlpResolver.looksLikePage("https://site.example/a/movie.mp4"))
    }
}
