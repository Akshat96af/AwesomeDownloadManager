package com.abdownloadmanager.desktop.pages.enterurl

import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.pages.enterurl.BaseEnterNewURLComponent
import com.abdownloadmanager.desktop.utils.ytdlp.ResolvedMedia
import com.abdownloadmanager.desktop.utils.ytdlp.YtDlpResolver
import com.arkivanov.decompose.ComponentContext
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import com.abdownloadmanager.shared.pages.enterurl.DownloaderSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DesktopEnterNewURLComponent(
    ctx: ComponentContext,
    config: Config,
    downloaderInUiRegistry: DownloaderInUiRegistry,
    onCloseRequest: () -> Unit,
    onRequestFinished: (IDownloadCredentials) -> Unit,
    private val onMediaResolved: (ResolvedMedia) -> Unit,
) : BaseEnterNewURLComponent(
    ctx = ctx,
    config = config,
    downloaderInUiRegistry = downloaderInUiRegistry,
    onCloseRequest = onCloseRequest,
    onRequestFinished = onRequestFinished,
) {
    /**
     * Page links (YouTube, ...) are first resolved to a direct media URL with yt-dlp.
     * Anything yt-dlp cannot handle falls back to the normal flow.
     */
    private val _resolving = MutableStateFlow(false)

    /** True while yt-dlp is looking at the page; the dialog shows progress and ignores repeated OKs. */
    val resolving = _resolving.asStateFlow()

    override fun newDownloadEntered() {
        if (_resolving.value) return
        val link = url.value
        // a downloader picked by hand is respected; only Auto goes through yt-dlp
        if (downloaderSelection.value != DownloaderSelection.Auto || !YtDlpResolver.looksLikePage(link)) {
            return super.newDownloadEntered()
        }
        _resolving.value = true
        scope.launch {
            try {
                val media = YtDlpResolver.resolve(link)
                if (media == null) {
                    // the user may have edited the field while waiting; fall back with the link that was resolved
                    setURL(link)
                    super.newDownloadEntered()
                } else {
                    onMediaResolved(media)
                    close()
                }
            } finally {
                _resolving.value = false
            }
        }
    }

    sealed interface Effects : BaseEnterNewURLComponent.Effects.PlatformEffects {
        data object BringToFront : Effects
    }

    data object Config : BaseEnterNewURLComponent.Config

    fun bringToFront() {
        sendEffect(Effects.BringToFront)
    }
}

