package com.abdownloadmanager.desktop.actions.onevennts

import com.abdownloadmanager.desktop.PowerActionManager
import com.abdownloadmanager.desktop.storage.AppSettingsStorage
import com.abdownloadmanager.desktop.utils.ytdlp.MediaMergeService
import com.abdownloadmanager.shared.pagemanager.NotificationSender
import com.abdownloadmanager.shared.ui.widget.NotificationType
import ir.amirab.util.compose.asStringSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import ir.amirab.util.desktop.poweraction.PowerActionConfig
import com.abdownloadmanager.desktop.pages.poweractionalert.PowerActionComponent
import com.abdownloadmanager.desktop.storage.DesktopExtraDownloadItemSettings
import com.abdownloadmanager.shared.storage.ExtraDownloadSettingsStorage
import com.abdownloadmanager.shared.util.ondownloadcompletion.OnDownloadCompletionAction
import com.abdownloadmanager.shared.util.ondownloadcompletion.OnDownloadCompletionActionProvider
import ir.amirab.downloader.downloaditem.IDownloadItem
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.getValue

class DesktopOnDownloadCompletionActionProvider(
    private val extraDownloadSettingsStorage: ExtraDownloadSettingsStorage<DesktopExtraDownloadItemSettings>,
    private val appSettings: AppSettingsStorage,
) : OnDownloadCompletionActionProvider, KoinComponent {
    private val notificationSender: NotificationSender by inject()
    private val mediaMergeService: MediaMergeService by inject()

    // TODO: BUG
    // at the moment if I move this to constructor the DI halts
    // probably due to Circular Dependency Exception
    // I need to redesign the dependency graph to prevent these sorts of issues!
    private val powerActionManager: PowerActionManager by inject()

    override suspend fun getOnDownloadCompletionAction(downloadItem: IDownloadItem): List<OnDownloadCompletionAction> {
        val downloadId = downloadItem.id
        val extraDownloadItemSettings = extraDownloadSettingsStorage.getExtraDownloadItemSettings(downloadId)
        // order matters: actions run one after another, and a shutdown/sleep countdown must not
        // start while a merge or scan is still writing files
        return buildList {
            add(MergeOnDownloadFinish(mediaMergeService))
            if (appSettings.scanDownloadsWithDefender.value) {
                add(DefenderScanOnDownloadFinish(notificationSender))
            }
            extraDownloadItemSettings.getPowerActionConfigOnFinish()?.let {
                add(PowerActionOnDownloadFinish(powerActionManager, it))
            }
            add(
                CleanExtraSettingsOnDownloadFinish(extraDownloadSettingsStorage)
            )
        }
    }
}

/**
 * Scans the finished file with Microsoft Defender's command line scanner.
 * Exit code 2 means a threat was found. Detection only: the file is left in place.
 * Silently does nothing when Defender's scanner is not installed (e.g. a third-party AV is in use).
 */
class DefenderScanOnDownloadFinish(
    private val notificationSender: NotificationSender,
) : OnDownloadCompletionAction {
    override suspend fun onDownloadCompleted(downloadItem: IDownloadItem) {
        val scanner = findScanner() ?: return
        val file = File(downloadItem.folder, downloadItem.name)
        if (!file.isFile) return
        val exitCode = withContext(Dispatchers.IO) {
            val process = ProcessBuilder(
                scanner.absolutePath, "-Scan", "-ScanType", "3", "-File", file.absolutePath, "-DisableRemediation",
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            if (process.waitFor(SCAN_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.exitValue()
            } else {
                process.destroyForcibly()
                null
            }
        }
        if (exitCode == THREAT_FOUND_EXIT_CODE) {
            notificationSender.sendNotification(
                tag = "defender-${downloadItem.id}",
                title = "Threat detected".asStringSource(),
                description = "Microsoft Defender flagged ${downloadItem.name}. Do not open it.".asStringSource(),
                type = NotificationType.Error,
            )
        }
    }

    private fun findScanner(): File? {
        val programFiles = System.getenv("ProgramFiles") ?: return null
        val programData = System.getenv("ProgramData")
        val platformDir = programData?.let { File(it, "Microsoft/Windows Defender/Platform") }
        // The platform folder holds one directory per Defender version; the newest sorts last.
        val newest = platformDir?.listFiles { f -> f.isDirectory }?.maxByOrNull { it.name }
            ?.let { File(it, "MpCmdRun.exe") }
        return listOfNotNull(newest, File(programFiles, "Windows Defender/MpCmdRun.exe"))
            .firstOrNull { it.isFile }
    }

    private companion object {
        const val THREAT_FOUND_EXIT_CODE = 2
        const val SCAN_TIMEOUT_MINUTES = 10L
    }
}

/** Merges a downloaded video and audio stream once both parts of a yt-dlp pair have finished. */
class MergeOnDownloadFinish(
    private val mediaMergeService: MediaMergeService,
) : OnDownloadCompletionAction {
    override suspend fun onDownloadCompleted(downloadItem: IDownloadItem) {
        mediaMergeService.onDownloadCompleted(downloadItem.id)
    }
}

class PowerActionOnDownloadFinish(
    val powerActionManager: PowerActionManager,
    val powerActionConfig: PowerActionConfig,
) : OnDownloadCompletionAction {
    override suspend fun onDownloadCompleted(downloadItem: IDownloadItem) {
        powerActionManager.initiatePowerAction(
            powerActionConfig,
            PowerActionComponent.PowerActionReason.DownloadFinished,
        )
    }
}

