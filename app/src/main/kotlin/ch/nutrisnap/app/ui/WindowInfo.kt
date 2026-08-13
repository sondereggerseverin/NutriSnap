package ch.nutrisnap.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material-ähnliche Breakpoints für Phone vs. Tablet. */
enum class WidthSizeClass {
    Compact,  // Phone < 600dp
    Medium,   // Tablet Portrait / kleine Tablets 600–839dp
    Expanded  // Tablet Landscape / große Tablets ≥ 840dp
}

data class WindowInfo(
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
    val widthSize: WidthSizeClass
) {
    val isTablet: Boolean get() = widthSize != WidthSizeClass.Compact

    /** Max. Inhaltsbreite: Phone = volle Breite, Tablet = zentriert begrenzt. */
    val contentMaxWidth: Dp
        get() = when (widthSize) {
            WidthSizeClass.Compact -> Dp.Unspecified
            WidthSizeClass.Medium -> 720.dp
            WidthSizeClass.Expanded -> 960.dp
        }

    /** Spalten für die Rezept-Grid-Übersicht. */
    fun recipeGridColumns(classicList: Boolean): Int {
        if (classicList) return 1
        return when (widthSize) {
            WidthSizeClass.Compact -> 2
            WidthSizeClass.Medium -> 3
            WidthSizeClass.Expanded -> 4
        }
    }

    /** Spalten für den Mehr/Settings-Hub. */
    val settingsHubColumns: Int
        get() = when (widthSize) {
            WidthSizeClass.Compact -> 2
            WidthSizeClass.Medium -> 3
            WidthSizeClass.Expanded -> 3
        }
}

@Composable
fun rememberWindowInfo(): WindowInfo {
    val config = LocalConfiguration.current
    return remember(config.screenWidthDp, config.screenHeightDp) {
        val w = config.screenWidthDp
        val h = config.screenHeightDp
        WindowInfo(
            widthDp = w,
            heightDp = h,
            isLandscape = w > h,
            widthSize = when {
                w >= 840 -> WidthSizeClass.Expanded
                w >= 600 -> WidthSizeClass.Medium
                else -> WidthSizeClass.Compact
            }
        )
    }
}

/**
 * Zentriert Inhalt und begrenzt die Breite auf Tablets.
 * Auf dem Phone unverändert (volle Breite).
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    window: WindowInfo = rememberWindowInfo(),
    content: @Composable BoxScope.() -> Unit
) {
    val maxW = window.contentMaxWidth
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = if (maxW == Dp.Unspecified) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .widthIn(max = maxW)
                    .fillMaxWidth()
            },
            content = content
        )
    }
}
