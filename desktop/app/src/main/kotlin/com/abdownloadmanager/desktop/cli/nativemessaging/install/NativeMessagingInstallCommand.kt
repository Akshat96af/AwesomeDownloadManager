package com.abdownloadmanager.desktop.cli.nativemessaging.install

import com.abdownloadmanager.desktop.nativemessaging.NativeMessaging
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import kotlinx.serialization.json.Json

class NativeMessagingInstallCommand : SuspendingCliktCommand(
    "install"
) {
    override fun help(context: Context): String = "Installs the native messaging host manifest file"

    override suspend fun run() {
        NativeMessaging.getDefault(Json).installManifests()
    }
}
