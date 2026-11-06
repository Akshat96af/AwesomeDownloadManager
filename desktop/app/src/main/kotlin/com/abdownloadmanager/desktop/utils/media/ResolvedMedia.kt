package com.abdownloadmanager.desktop.utils.media

import ir.amirab.downloader.downloaditem.IDownloadCredentials

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
