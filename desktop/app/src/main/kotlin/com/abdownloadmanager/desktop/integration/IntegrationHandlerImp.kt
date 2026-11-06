package com.abdownloadmanager.desktop.integration

import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.desktop.repository.AppRepository
import com.abdownloadmanager.desktop.utils.media.MediaMergeService
import com.abdownloadmanager.desktop.utils.media.ResolvedMedia
import com.abdownloadmanager.desktop.utils.youtube.StoredYouTubeFormat
import com.abdownloadmanager.desktop.utils.youtube.StoredYouTubeSession
import com.abdownloadmanager.desktop.utils.youtube.YouTubeSabrInterceptor
import com.abdownloadmanager.desktop.utils.youtube.YouTubeSessionStore
import com.abdownloadmanager.integration.IntegrationHandler
import com.abdownloadmanager.integration.model.*
import com.abdownloadmanager.shared.downloaderinui.BasicDownloadItem
import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.pages.adddownload.AddDownloadCredentialsInUiProps
import com.abdownloadmanager.shared.pages.adddownload.ImportOptions
import com.abdownloadmanager.shared.pages.adddownload.SilentImportOptions
import com.abdownloadmanager.shared.util.DownloadSystem
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.downloaditem.hls.HLSDownloadCredentials
import ir.amirab.downloader.downloaditem.http.HttpDownloadCredentials
import ir.amirab.downloader.queue.QueueManager
import ir.amirab.downloader.utils.OnDuplicateStrategy
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class IntegrationHandlerImp : IntegrationHandler, KoinComponent {
    val appComponent by inject<AppComponent>()
    val downloadSystem by inject<DownloadSystem>()
    val queueManager by inject<QueueManager>()
    val appSettings by inject<AppRepository>()
    private val downloaderInUiRegistry by inject<DownloaderInUiRegistry>()
    private val mediaMergeService by inject<MediaMergeService>()
    private val youTubeSessions by inject<YouTubeSessionStore>()

    override suspend fun addDownloadByGui(
        request: AddDownloadsFromIntegration
    ) {
        val (videos, list) = request.items.partition { it is YouTubeDownloadCredentialsFromIntegration }
        videos.forEach { addYouTube(it as YouTubeDownloadCredentialsFromIntegration) }
        if (list.isEmpty()) return
        val options = request.options
        appComponent.externalCredentialComingIntoApp(
            list.map {
                convertToDownloadSystemCredentials(it)
            },
            options = ImportOptions(
                silentImport = if (options.silentAdd) {
                    SilentImportOptions(
                        silentDownload = options.silentStart
                    )
                } else null
            )
        )
    }

    /**
     * A quality picked in the browser's YouTube menu. The captured player session is stored, and each track
     * becomes a normal download of a URL that YouTubeSabrInterceptor serves by streaming that track,
     * so video and audio download side by side and are merged by ffmpeg like any other pair.
     */
    private suspend fun addYouTube(item: YouTubeDownloadCredentialsFromIntegration) {
        fun format(f: YouTubeFormatFromIntegration) = StoredYouTubeFormat(f.itag, f.lastModified, f.xtags, f.mimeType)
        require(item.container in setOf("mp4", "mkv", "m4a", "webm")) { "Unknown container ${item.container}" }
        val key = youTubeSessions.save(
            StoredYouTubeSession(
                videoId = item.videoId,
                streamingUrl = item.streamingUrl,
                requestBody = item.requestBody,
                ustreamerConfig = item.ustreamerConfig,
                userAgent = item.userAgent,
                video = item.video?.let(::format),
                audio = format(item.audio),
            )
        )
        fun track(name: String) = HttpDownloadCredentials(
            link = YouTubeSabrInterceptor.trackUrl(key, name, item.videoId),
            downloadPage = item.downloadPage ?: item.link,
        )
        val fileName = "${item.suggestedName ?: item.videoId}.${item.container}"
        if (item.video != null) {
            val audioExtension = if (item.audio.mimeType.startsWith("audio/mp4")) "m4a" else "webm"
            mediaMergeService.start(ResolvedMedia.Merge(track("video"), track("audio"), audioExtension, fileName))
        } else {
            mediaMergeService.start(ResolvedMedia.Single(track("audio"), fileName))
        }
    }

    override fun listQueues(): List<ApiQueueModel> {
        return queueManager.getAll().map { downloadQueue ->
            val queueModel = downloadQueue.getQueueModel()
            ApiQueueModel(id = queueModel.id, name = queueModel.name)
        }
    }

    override suspend fun addDownload(task: NewDownloadTask): Long {
        val addDownloaderInUiProps = convertToDownloadSystemCredentials(task.downloadSource)
        val downloaderInUi = downloaderInUiRegistry.getDownloaderOf(
            addDownloaderInUiProps.credentials
        ) ?: error("Downloader for ${addDownloaderInUiProps.credentials::class.qualifiedName} not found")
        val downloadItem = downloaderInUi.createBareDownloadItem(
            addDownloaderInUiProps.credentials,
            basicDownloadItem = BasicDownloadItem(
                folder = task.folder ?: appSettings.saveLocation.value,
                name = task.name ?: addDownloaderInUiProps.extraConfig.suggestedName
                ?: task.downloadSource.link.substringAfterLast("/"),
            ),
        )
        val id = downloadSystem.addDownload(
            newDownload = NewDownloadItemProps(
                downloadItem = downloadItem,
                onDuplicateStrategy = OnDuplicateStrategy.default(),
                extraConfig = null,
                context = EmptyContext,
            ),
            queueId = task.queueId,
            categoryId = task.categoryId,
        )
        val queueId = task.queueId
        // either start the queue or manually start the download
        // both of them at the same time is not good idea
        if (task.startQueue && queueId != null) {
            val queue = queueManager.getQueue(queueId)
            queue.start()
        } else {
            if (task.startDownload) {
                downloadSystem.userManualResume(id)
            }
        }
        return id
    }

    companion object {
        private fun convertToDownloadSystemCredentials(it: IDownloadCredentialsFromIntegration): AddDownloadCredentialsInUiProps {
            val credentials = when (it) {
                is HttpDownloadCredentialsFromIntegration -> {
                    HttpDownloadCredentials(
                        link = it.link,
                        headers = it.headers,
                        downloadPage = it.downloadPage,
                    )
                }

                is HLSDownloadCredentialsFromIntegration -> {
                    HLSDownloadCredentials(
                        link = it.link,
                        headers = it.headers,
                        downloadPage = it.downloadPage,
                    )
                }

                is YouTubeDownloadCredentialsFromIntegration ->
                    error("A YouTube video is added through the browser's YouTube menu")
            }
            return AddDownloadCredentialsInUiProps(
                credentials = credentials,
                extraConfig = AddDownloadCredentialsInUiProps.Configs(
                    suggestedName = it.suggestedName,
                )
            )
        }
    }
}
