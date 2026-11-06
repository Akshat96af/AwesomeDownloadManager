package com.abdownloadmanager.desktop.utils

import java.io.File

/** Locates external executables (ffmpeg): first on PATH, then in winget's per-package folders. */
object ExternalTools {
    fun find(exeName: String, wingetPackagePrefix: String): File? {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, exeName) }
            .firstOrNull { it.isFile }
        if (onPath != null) return onPath
        // winget keeps each package in its own folder that a running process may not have on PATH yet
        val packages = System.getenv("LOCALAPPDATA")?.let { File(it, "Microsoft/WinGet/Packages") } ?: return null
        return packages.listFiles { f -> f.isDirectory && f.name.startsWith(wingetPackagePrefix) }.orEmpty()
            .firstNotNullOfOrNull { dir ->
                dir.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name == exeName }
            }
    }
}
