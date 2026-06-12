package ch.nutrisnap.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette ──────────────────────────────────────────────────────────────────
val Green900   = Color(0xFF1B4332)
val Green700   = Color(0xFF2D6A4F)
val Green400   = Color(0xFF52B788)
val Green100   = Color(0xFFD8F3DC)
val Cream      = Color(0xFFF8F4EF)
val Coral      = Color(0xFFE07A5F)
val CoralLight = Color(0xFFF2C4BB)
val OnDark     = Color(0xFFF1F1F0)
val TextPrimary   = Color(0xFF1C1C1E)
val TextSecondary = Color(0xFF6B7280)
val Surface    = Color(0xFFFFFFFF)

// ── Dark palette ─────────────────────────────────────────────────────────────
val DarkBackground = Color(0xFF121212)
val DarkSurface    = Color(0xFF1E1E1E)
val DarkSurface2   = Color(0xFF2C2C2C)

private val LightColors = lightColorScheme(
    primary              = Green700,
    onPrimary            = OnDark,
    primaryContainer     = Green100,
    onPrimaryContainer   = Green900,
    secondary            = Coral,
    onSecondary          = OnDark,
    secondaryContainer   = CoralLight,
    onSecondaryContainer = Color(0xFF6B2737),
    background           = Cream,
    onBackground         = TextPrimary,
    surface              = Surface,
    onSurface            = TextPrimary,
    surfaceVariant       = Color(0xFFF0EDE8),
    onSurfaceVariant     = TextSecondary,
    outline              = Color(0xFFD1D5DB),
    error                = Color(0xFFDC2626)
)

private val DarkColors = darkColorScheme(
    primary              = Green400,
    onPrimary            = Green900,
    primaryContainer     = Green700,
    onPrimaryContainer   = Green100,
    secondary            = Coral,
    onSecondary          = OnDark,
    secondaryContainer   = Color(0xFF6B2737),
    onSecondaryContainer = CoralLight,
    background           = DarkBackground,
    onBackground         = OnDark,
    surface              = DarkSurface,
    onSurface            = OnDark,
    surfaceVariant       = DarkSurface2,
    onSurfaceVariant     = Color(0xFFAAAAAA),
    outline              = Color(0xFF444444),
    error                = Color(0xFFFF6B6B)
)

@Composable
fun NutriSnapTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography  = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Default
            ),
        ),
        content     = content
    )
}
