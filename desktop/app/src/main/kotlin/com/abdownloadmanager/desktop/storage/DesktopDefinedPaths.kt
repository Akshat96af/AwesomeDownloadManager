package com.abdownloadmanager.desktop.storage

import com.abdownloadmanager.shared.util.DefinedPaths
import okio.Path
import java.io.File

class DesktopDefinedPaths(
    dataDir: Path
) : DefinedPaths(
    dataDir
) {
    val pageStatesStorageFile: Path = configDir.resolve("pageStatesStorage.json")
    val renderApiFile: Path = optionsDir.resolve("renderApi.txt")

    // optional read only properties file
    val appPropertiesFile = configDir.resolve("app.properties")

    // cached binaries or dlls should be saved here
    val nativeCacheDir: Path = systemDir.resolve("native-cache")

    // video + audio download pairs waiting to be merged by ffmpeg
    val mergeJobsFile: Path = systemDir.resolve("merge-jobs.json")

    // YouTube player sessions captured by the browser extension, one file per picked quality
    val youtubeSessionsDir: Path = systemDir.resolve("youtube-sessions")
}
