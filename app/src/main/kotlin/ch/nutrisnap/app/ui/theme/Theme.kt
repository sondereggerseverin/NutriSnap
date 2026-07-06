package ch.nutrisnap.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.stringPreferencesKey
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import androidx.compose.ui.platform.LocalContext

// ── Theme definitions ─────────────────────────────────────────────────────────

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
    PURPLE_NIGHT(
        label = "Purple Night", emoji = "🔮",
        primary      = Color(0xFF6B46C1),
        primaryDark  = Color(0xFF44267A),
        primaryLight = Color(0xFFEDE9FE),
        accent       = Color(0xFFEC4899),
        accentLight  = Color(0xFFFCE7F3),
        background   = Color(0xFFF9F7FF)
    ),
    ROSE_GOLD(
        label = "Rose Gold", emoji = "🌸",
        primary      = Color(0xFFB5636E),
        primaryDark  = Color(0xFF7A3841),
        primaryLight = Color(0xFFFDE8EA),
        accent       = Color(0xFFD4A017),
        accentLight  = Color(0xFFFFF3CC),
        background   = Color(0xFFFFF5F6)
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
}

// Legacy color aliases (used by Components.kt)
val Green900 = Color(0xFF1B4332)
val Green700 = Color(0xFF2D6A4F)
val Green400 = Color(0xFF52B788)
val Green100 = Color(0xFFD8F3DC)
val Cream    = Color(0xFFF8F4EF)
val Coral    = Color(0xFFE07A5F)
val CoralLight = Color(0xFFF2C4BB)

val KEY_APP_THEME = stringPreferencesKey("app_theme")
val KEY_MEAL_ORDER = stringPreferencesKey("meal_order") // komma-getrennte MealType-Namen

@Composable
fun NutriSnapTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val themeName = prefs?.get(KEY_APP_THEME) ?: AppTheme.FOREST_GREEN.name
    val theme = runCatching { AppTheme.valueOf(themeName) }.getOrDefault(AppTheme.FOREST_GREEN)

    MaterialTheme(
        colorScheme = theme.toColorScheme(),
        typography  = Typography(
            headlineLarge = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Default
            ),
        ),
        content = content
    )
}
