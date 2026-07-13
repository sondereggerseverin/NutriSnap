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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import androidx.compose.ui.platform.LocalContext

// ── Design Tokens ─────────────────────────────────────────────────────────────

object NutriSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
}

object NutriRadius {
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
}

// ── Macro Colors ──────────────────────────────────────────────────────────────

object MacroColors {
    val protein = Color(0xFF3B82F6)
    val carbs = Color(0xFFF59E0B)
    val fat = Color(0xFFEF4444)
    val calories = Color(0xFF10B981)
    val fiber = Color(0xFF8B5CF6)
}

// ── Theme Definitions ─────────────────────────────────────────────────────────

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
        label = "Forest Green", emoji = "\uD83C\uDF3F",
        primary      = Color(0xFF059669),
        primaryDark  = Color(0xFF065F46),
        primaryLight = Color(0xFFD1FAE5),
        accent       = Color(0xFFF97316),
        accentLight  = Color(0xFFFFEDD5),
        background   = Color(0xFFF0FDF4)
    ),
    OCEAN_BLUE(
        label = "Ocean Blue", emoji = "\uD83C\uDF0A",
        primary      = Color(0xFF2563EB),
        primaryDark  = Color(0xFF1E40AF),
        primaryLight = Color(0xFFDBEAFE),
        accent       = Color(0xFF06B6D4),
        accentLight  = Color(0xFFCFFAFE),
        background   = Color(0xFFEFF6FF)
    ),
    SUNSET_ORANGE(
        label = "Sunset Orange", emoji = "\uD83C\uDF05",
        primary      = Color(0xFFEA580C),
        primaryDark  = Color(0xFFC2410C),
        primaryLight = Color(0xFFFED7AA),
        accent       = Color(0xFF8B5CF6),
        accentLight  = Color(0xFFEDE9FE),
        background   = Color(0xFFFFF7ED)
    ),
    LAVENDER_DUSK(
        label = "Lavender Dusk", emoji = "\uD83C\uDF06",
        primary      = Color(0xFF7C3AED),
        primaryDark  = Color(0xFF5B21B6),
        primaryLight = Color(0xFFEDE9FE),
        accent       = Color(0xFFEC4899),
        accentLight  = Color(0xFFFCE7F3),
        background   = Color(0xFFF5F3FF)
    ),
    MINT_FRESH(
        label = "Mint Fresh", emoji = "\uD83C\uDF43",
        primary      = Color(0xFF059669),
        primaryDark  = Color(0xFF047857),
        primaryLight = Color(0xFFD1FAE5),
        accent       = Color(0xFF0EA5E9),
        accentLight  = Color(0xFFE0F2FE),
        background   = Color(0xFFECFDF5)
    ),
    ROSE_GOLD(
        label = "Rose Gold", emoji = "\uD83C\uDF38",
        primary      = Color(0xFFE11D48),
        primaryDark  = Color(0xFFBE123C),
        primaryLight = Color(0xFFFCE7F3),
        accent       = Color(0xFFF59E0B),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFFFF1F2)
    ),
    LAGOON_TEAL(
        label = "Lagoon Teal", emoji = "\uD83C\uDFDD\uFE0F",
        primary      = Color(0xFF0D9488),
        primaryDark  = Color(0xFF115E59),
        primaryLight = Color(0xFFCCFBF1),
        accent       = Color(0xFFF97316),
        accentLight  = Color(0xFFFFEDD5),
        background   = Color(0xFFF0FDFA)
    ),
    GOLDEN_AMBER(
        label = "Golden Amber", emoji = "\uD83C\uDF6F",
        primary      = Color(0xFFD97706),
        primaryDark  = Color(0xFF92400E),
        primaryLight = Color(0xFFFEF3C7),
        accent       = Color(0xFF0891B2),
        accentLight  = Color(0xFFCFFAFE),
        background   = Color(0xFFFEFCE8)
    ),
    SLATE_CHARCOAL(
        label = "Slate Charcoal", emoji = "\uD83D\uDDA4",
        primary      = Color(0xFF475569),
        primaryDark  = Color(0xFF1E293B),
        primaryLight = Color(0xFFE2E8F0),
        accent       = Color(0xFFF59E0B),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFF8FAFC)
    ),
    CHERRY_RED(
        label = "Cherry Red", emoji = "\uD83C\uDF52",
        primary      = Color(0xFFDC2626),
        primaryDark  = Color(0xFF991B1B),
        primaryLight = Color(0xFFFEE2E2),
        accent       = Color(0xFF059669),
        accentLight  = Color(0xFFD1FAE5),
        background   = Color(0xFFFEF2F2)
    ),
    MIDNIGHT_INDIGO(
        label = "Midnight Indigo", emoji = "\uD83C\uDF03",
        primary      = Color(0xFF4F46E5),
        primaryDark  = Color(0xFF3730A3),
        primaryLight = Color(0xFFE0E7FF),
        accent       = Color(0xFFFBBF24),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFEEF2FF)
    ),
    CITRUS_ZEST(
        label = "Citrus Zest", emoji = "\uD83C\uDF4B",
        primary      = Color(0xFF65A30D),
        primaryDark  = Color(0xFF3F6212),
        primaryLight = Color(0xFFECFCCB),
        accent       = Color(0xFFDB2777),
        accentLight  = Color(0xFFFCE7F3),
        background   = Color(0xFFFAFDF0)
    );

    fun toColorScheme() = lightColorScheme(
        primary            = primary,
        onPrimary          = Color.White,
        primaryContainer   = primaryLight,
        onPrimaryContainer = primaryDark,
        secondary          = accent,
        onSecondary        = Color.White,
        secondaryContainer = accentLight,
        onSecondaryContainer = primaryDark,
        background         = background,
        onBackground       = Color(0xFF0F172A),
        surface            = Color.White,
        onSurface          = Color(0xFF0F172A),
        surfaceVariant     = Color(0xFFF1F5F9),
        onSurfaceVariant   = Color(0xFF64748B),
        outline            = Color(0xFFE2E8F0),
        outlineVariant     = Color(0xFFF1F5F9),
        error              = Color(0xFFDC2626),
        onError            = Color.White,
        errorContainer     = Color(0xFFFEE2E2),
        onErrorContainer   = Color(0xFF991B1B),
        inverseSurface     = Color(0xFF1E293B),
        inverseOnSurface   = Color(0xFFF1F5F9),
        surfaceTint        = primary
    )

    fun toDarkColorScheme() = darkColorScheme(
        primary            = primaryLight,
        onPrimary          = primaryDark,
        primaryContainer   = primaryDark,
        onPrimaryContainer = primaryLight,
        secondary          = accentLight,
        onSecondary        = primaryDark,
        secondaryContainer = primaryDark,
        onSecondaryContainer = accentLight,
        background         = Color(0xFF0F172A),
        onBackground       = Color(0xFFE2E8F0),
        surface            = Color(0xFF1E293B),
        onSurface          = Color(0xFFE2E8F0),
        surfaceVariant     = Color(0xFF334155),
        onSurfaceVariant   = Color(0xFF94A3B8),
        outline            = Color(0xFF334155),
        outlineVariant     = Color(0xFF1E293B),
        error              = Color(0xFFFCA5A5),
        onError            = Color(0xFF7F1D1D),
        errorContainer     = Color(0xFF991B1B),
        onErrorContainer   = Color(0xFFFEE2E2),
        inverseSurface     = Color(0xFFE2E8F0),
        inverseOnSurface   = Color(0xFF1E293B),
        surfaceTint        = primaryLight
    )
}

// Legacy color aliases (used by Components.kt)
val Green900 = Color(0xFF065F46)
val Green700 = Color(0xFF059669)
val Green400 = Color(0xFF34D399)
val Green100 = Color(0xFFD1FAE5)
val Cream    = Color(0xFFF0FDF4)
val Coral    = Color(0xFFEA580C)
val CoralLight = Color(0xFFFED7AA)

val LocalAppTheme = staticCompositionLocalOf { AppTheme.FOREST_GREEN }

val KEY_APP_THEME = stringPreferencesKey("app_theme")
val KEY_MEAL_ORDER = stringPreferencesKey("meal_order")

// ── Typography ─────────────────────────────────────────────────────────────────

val NutriSnapTypography = Typography(
    displayLarge   = TextStyle(fontWeight = FontWeight.Bold,      fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    displayMedium  = TextStyle(fontWeight = FontWeight.Bold,      fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.25).sp),
    displaySmall   = TextStyle(fontWeight = FontWeight.Bold,      fontSize = 26.sp, lineHeight = 34.sp),
    headlineLarge  = TextStyle(fontWeight = FontWeight.Bold,      fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.25).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold,  fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall  = TextStyle(fontWeight = FontWeight.SemiBold,  fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge     = TextStyle(fontWeight = FontWeight.SemiBold,  fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium    = TextStyle(fontWeight = FontWeight.Medium,    fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall     = TextStyle(fontWeight = FontWeight.Medium,    fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge      = TextStyle(fontWeight = FontWeight.Normal,    fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium     = TextStyle(fontWeight = FontWeight.Normal,    fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall      = TextStyle(fontWeight = FontWeight.Normal,    fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge     = TextStyle(fontWeight = FontWeight.Medium,    fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium    = TextStyle(fontWeight = FontWeight.Medium,    fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall     = TextStyle(fontWeight = FontWeight.Medium,    fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
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
