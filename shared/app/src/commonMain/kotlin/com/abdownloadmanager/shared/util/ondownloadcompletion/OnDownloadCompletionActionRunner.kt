package com.abdownloadmanager.shared.util.ondownloadcompletion

import ir.amirab.downloader.DownloadManagerEvents
import ir.amirab.downloader.DownloadManagerMinimalControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class OnDownloadCompletionActionRunner(
    private val downloadManagerMinimalControl: DownloadManagerMinimalControl,
    private val scope: CoroutineScope,
    private val onDownloadCompletionActionProvider: OnDownloadCompletionActionProvider,
) {
    private var job: Job? = null

    /**
     * Starts listening to download completion events and executes the corresponding actions.
     */
    @Synchronized
    fun startListening() {
        job?.cancel()
        job = downloadManagerMinimalControl.listOfJobsEvents
            .filterIsInstance<DownloadManagerEvents.OnJobCompleted>()
            .onEach {
                val downloadItem = it.downloadItem
                // Actions may be slow (ffmpeg merge, antivirus scan). Running them inline would stall this
                // collector, and the shared event flow drops events once a subscriber is 64 events behind.
                // Actions of one download still run in order; different downloads run independently.
                scope.launch {
                    onDownloadCompletionActionProvider
                        .getOnDownloadCompletionAction(downloadItem)
                        .forEach { completionAction ->
                            runCatching {
                                completionAction.onDownloadCompleted(downloadItem)
                            }.onFailure { e ->
                                e.printStackTrace()
                            }
                        }
                }
            }
            .launchIn(scope)
    }

    fun stopListening() {
        job?.cancel()
        job = null
    }
}


