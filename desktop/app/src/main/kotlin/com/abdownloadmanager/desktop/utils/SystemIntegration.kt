package com.abdownloadmanager.desktop.utils

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import com.sun.management.HotSpotDiagnosticMXBean
import ir.amirab.util.platform.Platform
import ir.amirab.util.platform.asDesktop
import java.lang.management.ManagementFactory

/**
 * Keeps the app light while it only sits in the tray.
 * Measured before this existed: ~240 MB working set in the tray, barely less than with the window open.
 */
object BackgroundFootprint {
    /**
     * Lets the heap shrink back after use. These flags are "manageable", so they can be changed at runtime
     * without touching the launcher's JVM arguments. Each is set on its own because a flag that does not
     * exist for the active collector throws.
     */
    fun configure() {
        val bean = runCatching {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
        }.getOrNull() ?: return
        // Min first: Max may never be set below the current Min
        runCatching { bean.setVMOption("MinHeapFreeRatio", "10") }
        runCatching { bean.setVMOption("MaxHeapFreeRatio", "30") }
        // G1 returns unused heap to the OS on this period even when nothing allocates
        runCatching { bean.setVMOption("G1PeriodicGCInterval", "60000") }
    }

    /** Call once the main window is gone: hands heap and resident pages back to Windows. */
    fun trim() {
        System.gc()
        if ((Platform.asDesktop() == Platform.Desktop.Windows)) {
            // pages are paged back in on demand, so this costs nothing until the window is shown again
            runCatching { Psapi.INSTANCE.EmptyWorkingSet(Kernel32.INSTANCE.GetCurrentProcess()) }
        }
    }

    private interface Psapi : Library {
        fun EmptyWorkingSet(process: WinNT.HANDLE): Boolean

        companion object {
            val INSTANCE: Psapi by lazy { Native.load("psapi", Psapi::class.java) }
        }
    }
}

object SystemMotionPreference {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    /** Windows "Animation effects" switch (Settings > Accessibility > Visual effects). Read once at start. */
    fun isReduced(): Boolean {
        if (!(Platform.asDesktop() == Platform.Desktop.Windows)) return false
        return runCatching {
            val enabled = IntByReference()
            User32Ext.INSTANCE.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) && enabled.value == 0
        }.getOrDefault(false)
    }

    private interface User32Ext : Library {
        fun SystemParametersInfoW(uiAction: Int, uiParam: Int, pvParam: IntByReference, fWinIni: Int): Boolean

        companion object {
            val INSTANCE: User32Ext by lazy { Native.load("user32", User32Ext::class.java) }
        }
    }
}
