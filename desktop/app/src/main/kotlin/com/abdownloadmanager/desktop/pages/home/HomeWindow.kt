package com.abdownloadmanager.desktop.pages.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.v2.WindowBoundsProvider
import androidx.compose.ui.window.v2.WindowPositionProvider
import androidx.compose.ui.window.v2.WindowSizeProvider
import androidx.compose.ui.window.v2.rememberWindowState
import com.abdownloadmanager.desktop.AppComponent
import com.abdownloadmanager.desktop.utils.AppInfo
import com.abdownloadmanager.desktop.window.custom.CustomWindow
import com.abdownloadmanager.desktop.window.custom.isMinimizedOrNull
import com.abdownloadmanager.desktop.window.custom.placementOrNull
import com.abdownloadmanager.desktop.window.custom.rememberWindowController
import com.abdownloadmanager.desktop.window.custom.sizeOrNull
import com.abdownloadmanager.shared.util.LocalShortCutManager
import com.abdownloadmanager.shared.util.mvi.HandleEffects
import com.abdownloadmanager.shared.util.rememberChild
import com.abdownloadmanager.shared.util.ui.icon.MyIcons
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.GraphicsEnvironment

/** Matches the default in [HomePageStateToPersist]; a stored size equal to it was never chosen by the user. */
private val LegacyDefaultSize = DpSize(1000.dp, 500.dp)

/**
 * Opens at 16:10 and about 62% of the usable screen width, so the window looks proportioned
 * on a 1366px laptop and on a 4K desk alike.
 */
private fun premiumDefaultSize(): DpSize {
    val usable = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrNull() ?: return DpSize(1180.dp, 738.dp)
    var width = (usable.width * 0.62f).coerceIn(980f, 1480f)
    var height = width / 1.6f
    val maxHeight = usable.height * 0.82f
    if (height > maxHeight) {
        height = maxHeight
        width = height * 1.6f
    }
    return DpSize(width.dp, height.dp)
}

@Composable
fun HomeWindow(
    appComponent: AppComponent,
) {
    appComponent.showHomeSlot.rememberChild()?.let {
        HomeWindow(it, appComponent::closeHome)
    }
}

@Composable
private fun HomeWindow(
    homeComponent: HomeComponent,
    onCLoseRequest: () -> Unit,
) {
    val size by homeComponent.windowSize.collectAsState()
    val isMaximized by homeComponent.isMaximized.collectAsState()
    // the stored size is only the user's choice once they resized; the old default was a 2:1 letterbox
    val initialSize = remember { if (size == LegacyDefaultSize) premiumDefaultSize() else size }
    val windowState = rememberWindowState(
        initialBoundsProvider = WindowBoundsProvider(
            sizeProvider = WindowSizeProvider.Fixed(
                size = initialSize,
            ),
            positionProvider = WindowPositionProvider.CenteredOnScreen
        ),
        initialPlacement = if (isMaximized) {
            WindowPlacement.Maximized
        } else {
            WindowPlacement.Floating
        }
    )
    val windowIcon = MyIcons.appIcon
    val windowController = rememberWindowController(
        AppInfo.displayName,
        windowIcon.rememberPainter(),
    )

    CompositionLocalProvider(
        LocalShortCutManager provides homeComponent.shortcutManager
    ) {
        CustomWindow(
            state = windowState,
            onCloseRequest = onCLoseRequest,
            windowController = windowController,
            onKeyEvent = {
                homeComponent.shortcutManager.handle(it)
            },
            // below this the sidebar and the columns crush each other
            minSize = DpSize(760.dp, 480.dp),
        ) {
            LaunchedEffect(windowState.sizeOrNull) {
                if (windowState.isMinimizedOrNull == false && windowState.placementOrNull == WindowPlacement.Floating) {
                    windowState.sizeOrNull?.let {
                        homeComponent.setWindowSize(it)
                    }
                }
            }
            LaunchedEffect(windowState) {
                snapshotFlow { windowState.placementOrNull }
                    .filterNotNull()
                    .onEach {
                        homeComponent.setIsMaximized(it == WindowPlacement.Maximized)
                    }.launchIn(this)
            }
            HandleEffects(homeComponent) {
                when (it) {
                    HomeComponent.Effects.BringToFront -> {
                        windowState.requestMinimized(false)
                        window.toFront()
                    }

                    else -> {}
                }
            }
            BoxWithConstraints {
                HomePage(homeComponent)
            }
        }
    }
}
