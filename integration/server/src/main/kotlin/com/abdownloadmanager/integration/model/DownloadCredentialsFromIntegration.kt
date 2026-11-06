package com.abdownloadmanager.integration.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
sealed interface IDownloadCredentialsFromIntegration {
    val link: String
    val downloadPage: String?

    val suggestedName: String?
}

@SerialName("http")
@Serializable
data class HttpDownloadCredentialsFromIntegration(
    override val link: String,
    val headers: Map<String, String>? = null,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
) : IDownloadCredentialsFromIntegration

@SerialName("hls")
@Serializable
data class HLSDownloadCredentialsFromIntegration(
    override val link: String,
    val headers: Map<String, String>? = null,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
) : IDownloadCredentialsFromIntegration

/** One stream of a YouTube video, as the page's player lists it. */
@Serializable
data class YouTubeFormatFromIntegration(
    val itag: Int,
    val lastModified: String,
    val xtags: String? = null,
    val mimeType: String,
)

/**
 * A YouTube video in one quality. The browser extension captures the page player's own streaming
 * session (the SABR URL as the player requested it, plus one request body carrying the player's
 * client info and proof-of-origin token), so the app can stream the chosen formats itself.
 */
@SerialName("youtube")
@Serializable
data class YouTubeDownloadCredentialsFromIntegration(
    override val link: String,
    override val downloadPage: String? = null,
    override val suggestedName: String? = null,
    val videoId: String,
    val streamingUrl: String,
    /** base64 */
    val requestBody: String,
    /** base64url, from the player's media ustreamer config */
    val ustreamerConfig: String,
    val userAgent: String? = null,
    /** null for audio only */
    val video: YouTubeFormatFromIntegration? = null,
    val audio: YouTubeFormatFromIntegration,
    /** "mp4", "mkv", "m4a" or "webm" */
    val container: String,
) : IDownloadCredentialsFromIntegration

@Serializable
data class AddDownloadOptionsFromIntegration(
    val silentAdd: Boolean = false,
    val silentStart: Boolean = false,
)

@Serializable
data class AddDownloadsFromIntegration(
    val items: List<IDownloadCredentialsFromIntegration>,
    val options: AddDownloadOptionsFromIntegration = AddDownloadOptionsFromIntegration()
) {
    companion object {
        fun createFromRequest(json: Json, jsonData: String): AddDownloadsFromIntegration {
            return try {
                json.decodeFromString<AddDownloadsFromIntegration>(jsonData)
            } catch (_: SerializationException) {
                // TODO Remove this after a while
                AddDownloadsFromIntegration(
                    items = json.decodeFromString<List<HttpDownloadCredentialsFromIntegration>>(jsonData),
                    AddDownloadOptionsFromIntegration(
                        silentAdd = false,
                        silentStart = false,
                    )
                )
            }
        }
    }
}
