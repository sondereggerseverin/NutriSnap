package ch.nutrisnap.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette: deep forest green primary, warm cream background, coral accent
val Green900 = Color(0xFF1B4332)
val Green700 = Color(0xFF2D6A4F)
val Green400 = Color(0xFF52B788)
val Green100 = Color(0xFFD8F3DC)
val Cream    = Color(0xFFF8F4EF)
val Coral    = Color(0xFFE07A5F)
val CoralLight = Color(0xFFF2C4BB)
val OnDark   = Color(0xFFF1F1F0)
val TextPrimary = Color(0xFF1C1C1E)
val TextSecondary = Color(0xFF6B7280)
val Surface  = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary          = Green700,
    onPrimary        = OnDark,
    primaryContainer = Green100,
    onPrimaryContainer = Green900,
    secondary        = Coral,
    onSecondary      = OnDark,
    secondaryContainer = CoralLight,
    onSecondaryContainer = Color(0xFF6B2737),
    background       = Cream,
    onBackground     = TextPrimary,
    surface          = Surface,
    onSurface        = TextPrimary,
    surfaceVariant   = Color(0xFFF0EDE8),
    onSurfaceVariant = TextSecondary,
    outline          = Color(0xFFD1D5DB),
    error            = Color(0xFFDC2626)
)

@Composable
fun NutriSnapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography  = Typography(
            headlineLarge  = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Default
            ),
        ),
        content     = content
    )
}
