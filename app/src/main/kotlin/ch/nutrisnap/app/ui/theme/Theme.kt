package ch.nutrisnap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import androidx.compose.ui.platform.LocalContext

enum class AppTheme(
    val label: String,
    val emoji: String,
    val primary: Color,
    val primaryDark: Color,
    val primaryLight: Color,
    val accent: Color,
    val accentLight: Color,
    val background: Color
) {
    FOREST_GREEN(
        label = "Forest Green", emoji = "🌿",
        primary      = Color(0xFF2D6A4F),
        primaryDark  = Color(0xFF1B4332),
        primaryLight = Color(0xFFD8F3DC),
        accent       = Color(0xFFE07A5F),
        accentLight  = Color(0xFFF2C4BB),
        background   = Color(0xFFF8F4EF)
    ),
    OCEAN_BLUE(
        label = "Ocean Blue", emoji = "🌊",
        primary      = Color(0xFF1E6091),
        primaryDark  = Color(0xFF0D3B5E),
        primaryLight = Color(0xFFD0E8F5),
        accent       = Color(0xFFF4A261),
        accentLight  = Color(0xFFFDE8D0),
        background   = Color(0xFFF0F6FB)
    ),
    SUNSET_ORANGE(
        label = "Sunset Orange", emoji = "🌅",
        primary      = Color(0xFFD4622A),
        primaryDark  = Color(0xFF8B3A10),
        primaryLight = Color(0xFFFFE0CC),
        accent       = Color(0xFF4ECDC4),
        accentLight  = Color(0xFFB8F0EC),
        background   = Color(0xFFFFF8F4)
    ),
    LAVENDER_DUSK(
        label = "Lavender Dusk", emoji = "🌆",
        primary      = Color(0xFF7C3AED),
        primaryDark  = Color(0xFF4C1D95),
        primaryLight = Color(0xFFDDD6FE),
        accent       = Color(0xFFF472B6),
        accentLight  = Color(0xFFFCE7F3),
        background   = Color(0xFFF5F3FF)
    ),
    MINT_FRESH(
        label = "Mint Fresh", emoji = "🍃",
        primary      = Color(0xFF10B981),
        primaryDark  = Color(0xFF047857),
        primaryLight = Color(0xFFD1FAE5),
        accent       = Color(0xFF60A5FA),
        accentLight  = Color(0xFFDBEAFE),
        background   = Color(0xFFF0FDF4)
    ),
    ROSE_GOLD(
        label = "Rose Gold", emoji = "🌸",
        primary      = Color(0xFFB5636E),
        primaryDark  = Color(0xFF7A3841),
        primaryLight = Color(0xFFFDE8EA),
        accent       = Color(0xFFD4A017),
        accentLight  = Color(0xFFFFF3CC),
        background   = Color(0xFFFFF5F6)
    ),
    LAGOON_TEAL(
        label = "Lagoon Teal", emoji = "🏝️",
        primary      = Color(0xFF0F766E),
        primaryDark  = Color(0xFF0B4F49),
        primaryLight = Color(0xFFCCFBF1),
        accent       = Color(0xFFFB923C),
        accentLight  = Color(0xFFFFEDD5),
        background   = Color(0xFFF0FDFA)
    ),
    GOLDEN_AMBER(
        label = "Golden Amber", emoji = "🍯",
        primary      = Color(0xFFB45309),
        primaryDark  = Color(0xFF78350F),
        primaryLight = Color(0xFFFEF3C7),
        accent       = Color(0xFF0891B2),
        accentLight  = Color(0xFFCFFAFE),
        background   = Color(0xFFFFFBEB)
    ),
    SLATE_CHARCOAL(
        label = "Slate Charcoal", emoji = "🖤",
        primary      = Color(0xFF334155),
        primaryDark  = Color(0xFF1E293B),
        primaryLight = Color(0xFFE2E8F0),
        accent       = Color(0xFFF59E0B),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFF8FAFC)
    ),
    CHERRY_RED(
        label = "Cherry Red", emoji = "🍒",
        primary      = Color(0xFFB91C1C),
        primaryDark  = Color(0xFF7F1D1D),
        primaryLight = Color(0xFFFEE2E2),
        accent       = Color(0xFF10B981),
        accentLight  = Color(0xFFD1FAE5),
        background   = Color(0xFFFFF5F5)
    ),
    MIDNIGHT_INDIGO(
        label = "Midnight Indigo", emoji = "🌌",
        primary      = Color(0xFF3730A3),
        primaryDark  = Color(0xFF1E1B4B),
        primaryLight = Color(0xFFE0E7FF),
        accent       = Color(0xFFFBBF24),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFF5F5FF)
    ),
    CITRUS_ZEST(
        label = "Citrus Zest", emoji = "🍋",
        primary      = Color(0xFF65A30D),
        primaryDark  = Color(0xFF3F6212),
        primaryLight = Color(0xFFECFCCB),
        accent       = Color(0xFFEC4899),
        accentLight  = Color(0xFFFCE7F3),
        background   = Color(0xFFFAFDF0)
    );

    fun toColorScheme() = lightColorScheme(
        primary            = primary,
        onPrimary          = Color(0xFFF1F1F0),
        primaryContainer   = primaryLight,
        onPrimaryContainer = primaryDark,
        secondary          = accent,
        onSecondary        = Color(0xFFF1F1F0),
        secondaryContainer = accentLight,
        onSecondaryContainer = primaryDark,
        background         = background,
        onBackground       = Color(0xFF1C1C1E),
        surface            = Color(0xFFFFFFFF),
        onSurface          = Color(0xFF1C1C1E),
        surfaceVariant     = Color(0xFFF0EDE8),
        onSurfaceVariant   = Color(0xFF6B7280),
        outline            = Color(0xFFD1D5DB),
        error              = Color(0xFFDC2626)
    )

    /** Dunkles Pendant: gleiche Akzentfarben (aufgehellt für Kontrast), neutrale dunkle
     *  Flächen statt der hellen Background/Surface-Werte aus [toColorScheme]. */
    fun toDarkColorScheme() = darkColorScheme(
        primary            = primaryLight,
        onPrimary          = primaryDark,
        primaryContainer   = primaryDark,
        onPrimaryContainer = primaryLight,
        secondary          = accentLight,
        onSecondary        = primaryDark,
        secondaryContainer = primaryDark,
        onSecondaryContainer = accentLight,
        background         = Color(0xFF15171A),
        onBackground       = Color(0xFFE3E3E3),
        surface            = Color(0xFF1C1E22),
        onSurface          = Color(0xFFE3E3E3),
        surfaceVariant     = Color(0xFF2A2D31),
        onSurfaceVariant   = Color(0xFFA8ADB4),
        outline            = Color(0xFF4B4F55),
        error              = Color(0xFFEF5350)
    )
}

// Legacy color aliases (used by Components.kt)
val Green900 = Color(0xFF1B4332)
val Green700 = Color(0xFF2D6A4F)
val Green400 = Color(0xFF52B788)
val Green100 = Color(0xFFD8F3DC)
val Cream    = Color(0xFFF8F4EF)
val Coral    = Color(0xFFE07A5F)
val CoralLight = Color(0xFFF2C4BB)

// Gibt Zugriff auf die rohen, mode-unabhängigen AppTheme-Farben (primary/primaryDark/...),
// z.B. für Hero-Header mit fest weißer Schrift, die unabhängig vom Light/Dark-Farbschema
// immer ausreichend Kontrast brauchen (die Material3-Rollen primary/onPrimaryContainer
// vertauschen ihre Helligkeit zwischen Light- und Dark-Scheme und eignen sich dafür nicht).
val LocalAppTheme = staticCompositionLocalOf { AppTheme.FOREST_GREEN }

val KEY_APP_THEME = stringPreferencesKey("app_theme")
val KEY_MEAL_ORDER = stringPreferencesKey("meal_order") // komma-getrennte MealType-Namen

// ── Typography ─────────────────────────────────────────────────────────────────
// Durchgaengige Groessen-/Gewichts-Hierarchie statt einzelner Ad-hoc-Ueberschreibungen
// in den Screens (vorher wurde ueberall mit rohen fontSize=13.sp etc. gearbeitet).
val NutriSnapTypography = Typography(
    displayLarge   = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 36.sp, lineHeight = 44.sp),
    displayMedium  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 30.sp, lineHeight = 38.sp),
    displaySmall   = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 26.sp, lineHeight = 34.sp),
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,     fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.15.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
)

@Composable
fun NutriSnapTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val themeName = prefs?.get(KEY_APP_THEME) ?: AppTheme.FOREST_GREEN.name
    val theme = runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.FOREST_GREEN)
    val useDarkColors = isSystemInDarkTheme()

    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(
            colorScheme = if (useDarkColors) theme.toDarkColorScheme() else theme.toColorScheme(),
            typography  = NutriSnapTypography,
            content     = content
        )
    }
}
