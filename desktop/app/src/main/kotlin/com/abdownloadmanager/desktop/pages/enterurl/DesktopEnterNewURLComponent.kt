package com.abdownloadmanager.desktop.pages.enterurl

import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.pages.enterurl.BaseEnterNewURLComponent
import com.arkivanov.decompose.ComponentContext
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import com.abdownloadmanager.shared.pages.enterurl.DownloaderSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URI

class DesktopEnterNewURLComponent(
    ctx: ComponentContext,
    config: Config,
    downloaderInUiRegistry: DownloaderInUiRegistry,
    onCloseRequest: () -> Unit,
    onRequestFinished: (IDownloadCredentials) -> Unit,
    private val onYouTubePageLink: () -> Unit,
) : BaseEnterNewURLComponent(
    ctx = ctx,
    config = config,
    downloaderInUiRegistry = downloaderInUiRegistry,
    onCloseRequest = onCloseRequest,
    onRequestFinished = onRequestFinished,
) {
    /** Kept for the dialog's "busy" state; nothing resolves page links in the app any more. */
    val resolving: StateFlow<Boolean> = MutableStateFlow(false)

    override fun newDownloadEntered() {
        // a YouTube page is not a file: its streams need the session of the browser tab playing it
        if (downloaderSelection.value == DownloaderSelection.Auto && isYouTubePage(url.value)) {
            onYouTubePageLink()
            return
        }
        super.newDownloadEntered()
    }

    private fun isYouTubePage(link: String): Boolean {
        val host = runCatching { URI(link.trim()).host }.getOrNull()?.lowercase() ?: return false
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    sealed interface Effects : BaseEnterNewURLComponent.Effects.PlatformEffects {
        data object BringToFront : Effects
    }

    data object Config : BaseEnterNewURLComponent.Config

    fun bringToFront() {
        sendEffect(Effects.BringToFront)
    }
}

