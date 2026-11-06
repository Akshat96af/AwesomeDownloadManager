package com.abdownloadmanager.desktop.utils.media

import com.abdownloadmanager.desktop.repository.AppRepository
import com.abdownloadmanager.desktop.utils.ExternalTools
import com.abdownloadmanager.shared.downloaderinui.BasicDownloadItem
import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.pagemanager.NotificationSender
import com.abdownloadmanager.shared.ui.widget.NotificationType
import com.abdownloadmanager.shared.util.DownloadSystem
import com.abdownloadmanager.shared.util.FilenameFixer
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.downloaditem.DownloadStatus
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.downloaditem.IDownloadCredentials
import ir.amirab.downloader.utils.OnDuplicateStrategy
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

@Serializable
private data class MergeJob(val videoId: Long, val audioId: Long)

/**
 * Downloads a video stream and an audio stream as two normal downloads, then merges them with ffmpeg.
 * Also starts single-stream media (audio only) straight away, so all picks from the browser behave the same.
 *
 * The video download carries the final file name, so it is the entry the user sees in the list.
 * When both are complete the merged file replaces the video file and the audio download is removed.
 * Pending pairs are kept in [jobsFile] and only removed after a successful merge, so a crash, an exit
 * or a missing ffmpeg never loses a merge: [resumePendingJobs] retries them on the next start.
 */
class MediaMergeService(
    private val downloadSystem: DownloadSystem,
    private val downloaderInUiRegistry: DownloaderInUiRegistry,
    private val appRepository: AppRepository,
    private val jobsFile: File,
) : KoinComponent {
    private val notificationSender: NotificationSender by inject()

    /** Guards [jobsFile] and [merging]; never held while ffmpeg runs. */
    private val lock = Mutex()
    private val merging = mutableSetOf<MergeJob>()
    private val jobsSerializer = ListSerializer(MergeJob.serializer())

    suspend fun start(media: ResolvedMedia.Merge) {
        val folder = appRepository.saveLocation.value
        val videoName = FilenameFixer.fix(media.fileName)
        val audioName = "${videoName.substringBeforeLast('.')}.audio.${media.audioExtension}"
        val videoId = addDownload(media.video, folder, videoName)
        val audioId = try {
            addDownload(media.audio, folder, audioName)
        } catch (e: Exception) {
            // without its audio the video entry would end up silent, so undo it
            downloadSystem.removeDownload(videoId, alsoRemoveFile = true, context = EmptyContext)
            throw e
        }
        lock.withLock { saveJobs(loadJobs() + MergeJob(videoId, audioId)) }
        downloadSystem.userManualResume(videoId)
        downloadSystem.userManualResume(audioId)
        notificationSender.sendNotification(
            tag = "merge-$videoId",
            title = "Downloading video and audio".asStringSource(),
            description = "$videoName will be merged automatically when both parts finish.".asStringSource(),
            type = NotificationType.Info,
        )
    }

    suspend fun start(media: ResolvedMedia.Single) {
        val id = addDownload(media.credentials, appRepository.saveLocation.value, FilenameFixer.fix(media.fileName))
        downloadSystem.userManualResume(id)
    }

    /** Called for every finished download; does nothing unless the download belongs to a pending pair. */
    suspend fun onDownloadCompleted(downloadId: Long) {
        val job = lock.withLock {
            loadJobs().firstOrNull { it.videoId == downloadId || it.audioId == downloadId }
        } ?: return
        tryMerge(job, justCompletedId = downloadId)
    }

    /** Retries pairs whose merge never happened, e.g. the app exited mid-merge or ffmpeg was missing. */
    suspend fun resumePendingJobs() {
        val jobs = lock.withLock { loadJobs() }
        for (job in jobs) tryMerge(job, justCompletedId = null)
    }

    private suspend fun tryMerge(job: MergeJob, justCompletedId: Long?) {
        val video = downloadSystem.getDownloadItemById(job.videoId)
        val audio = downloadSystem.getDownloadItemById(job.audioId)
        if (video == null || audio == null) {
            // the user deleted one of the parts, so there is nothing left to merge
            lock.withLock { saveJobs(loadJobs() - job) }
            return
        }
        // the event for this download may arrive before its status is stored as Completed
        fun done(id: Long, status: DownloadStatus) = id == justCompletedId || status == DownloadStatus.Completed
        if (!done(job.videoId, video.status) || !done(job.audioId, audio.status)) return

        // both completion events can arrive close together; only one of them may run ffmpeg
        val claimed = lock.withLock { merging.add(job) }
        if (!claimed) return
        try {
            val videoFile = downloadSystem.getFilePathById(job.videoId) ?: return
            val audioFile = downloadSystem.getFilePathById(job.audioId) ?: return
            val error = merge(videoFile, audioFile)
            if (error == null) {
                lock.withLock { saveJobs(loadJobs() - job) }
                downloadSystem.removeDownload(job.audioId, alsoRemoveFile = true, context = EmptyContext)
            } else {
                notificationSender.sendNotification(
                    tag = "merge-failed-${job.videoId}",
                    title = "Could not merge video and audio".asStringSource(),
                    description = "$error The parts were kept and the merge will be retried on next start.".asStringSource(),
                    type = NotificationType.Error,
                )
            }
        } finally {
            lock.withLock { merging.remove(job) }
        }
    }

    private suspend fun addDownload(credentials: IDownloadCredentials, folder: String, name: String): Long {
        val downloaderInUi = downloaderInUiRegistry.getDownloaderOf(credentials)
            ?: error("No downloader for ${credentials::class.qualifiedName}")
        val item = downloaderInUi.createBareDownloadItem(
            credentials,
            basicDownloadItem = BasicDownloadItem(folder = folder, name = name),
        )
        return downloadSystem.addDownload(
            newDownload = NewDownloadItemProps(
                downloadItem = item,
                onDuplicateStrategy = OnDuplicateStrategy.AddNumbered,
                extraConfig = null,
                context = EmptyContext,
            ),
            queueId = null,
            categoryId = null,
        )
    }

    /**
     * Copies both streams into a temp file, then replaces the video file with it.
     * Returns null on success, or a short user-facing reason on failure.
     */
    private suspend fun merge(video: File, audio: File): String? = withContext(Dispatchers.IO) {
        val ffmpeg = ExternalTools.find("ffmpeg.exe", "Gyan.FFmpeg")
            ?: return@withContext "ffmpeg was not found."
        val merged = File(video.parentFile, "${video.nameWithoutExtension}.merging.${video.extension}")
        val process = ProcessBuilder(
            ffmpeg.absolutePath, "-y", "-loglevel", "error", "-nostdin",
            "-i", video.absolutePath, "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", merged.absolutePath,
        ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // stream copy is disk-bound; even multi-GB files finish well inside this
        val finished = process.waitFor(MERGE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        if (!finished || process.exitValue() != 0 || merged.length() == 0L) {
            merged.delete()
            return@withContext if (finished) "ffmpeg failed." else "ffmpeg took too long."
        }
        try {
            Files.move(merged.toPath(), video.toPath(), StandardCopyOption.REPLACE_EXISTING)
            null
        } catch (e: Exception) {
            merged.delete()
            "The video file is in use by another program."
        }
    }

    private fun loadJobs(): List<MergeJob> = runCatching {
        Json.decodeFromString(jobsSerializer, jobsFile.readText())
    }.getOrDefault(emptyList())

    private fun saveJobs(jobs: List<MergeJob>) {
        jobsFile.parentFile?.mkdirs()
        jobsFile.writeText(Json.encodeToString(jobsSerializer, jobs))
    }

    private companion object {
        const val MERGE_TIMEOUT_MINUTES = 30L
    }
}
