package ch.nutrisnap.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
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
    val background: Color,
    /** Nur kuratierte Themes im Picker; Rest bleibt für DataStore-Kompatibilität. */
    val showInPicker: Boolean = true
) {
    // ── Picker-Themes: bewusst stark unterschiedliche Primärfarben (Herz-Palette) ──
    CHERRY_RED(
        label = "Rot", emoji = "❤️",
        primary      = Color(0xFFDC2626),
        primaryDark  = Color(0xFF991B1B),
        primaryLight = Color(0xFFFEE2E2),
        accent       = Color(0xFFF97316),
        accentLight  = Color(0xFFFFEDD5),
        background   = Color(0xFFFEF2F2)
    ),
    SUNSET_ORANGE(
        label = "Orange", emoji = "🧡",
        primary      = Color(0xFFEA580C),
        primaryDark  = Color(0xFFC2410C),
        primaryLight = Color(0xFFFED7AA),
        accent       = Color(0xFFFBBF24),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFFFF7ED)
    ),
    SUNNY(
        label = "Gelb", emoji = "💛",
        primary      = Color(0xFFEAB308),
        primaryDark  = Color(0xFFA16207),
        primaryLight = Color(0xFFFEF9C3),
        accent       = Color(0xFFEA580C),
        accentLight  = Color(0xFFFFEDD5),
        background   = Color(0xFFFEFCE8)
    ),
    FOREST_GREEN(
        label = "Grün", emoji = "💚",
        primary      = Color(0xFF16A34A),
        primaryDark  = Color(0xFF166534),
        primaryLight = Color(0xFFDCFCE7),
        accent       = Color(0xFF0EA5E9),
        accentLight  = Color(0xFFE0F2FE),
        background   = Color(0xFFF0FDF4)
    ),
    LAGOON_TEAL(
        label = "Türkis", emoji = "🩵",
        primary      = Color(0xFF0891B2),
        primaryDark  = Color(0xFF155E75),
        primaryLight = Color(0xFFCFFAFE),
        accent       = Color(0xFF8B5CF6),
        accentLight  = Color(0xFFEDE9FE),
        background   = Color(0xFFECFEFF)
    ),
    OCEAN_BLUE(
        label = "Blau", emoji = "💙",
        primary      = Color(0xFF2563EB),
        primaryDark  = Color(0xFF1E40AF),
        primaryLight = Color(0xFFDBEAFE),
        accent       = Color(0xFF06B6D4),
        accentLight  = Color(0xFFCFFAFE),
        background   = Color(0xFFEFF6FF)
    ),
    LAVENDER_DUSK(
        label = "Lila", emoji = "💜",
        primary      = Color(0xFF7C3AED),
        primaryDark  = Color(0xFF5B21B6),
        primaryLight = Color(0xFFEDE9FE),
        accent       = Color(0xFFEC4899),
        accentLight  = Color(0xFFFCE7F3),
        background   = Color(0xFFF5F3FF)
    ),
    ROSE_GOLD(
        label = "Rosa", emoji = "🩷",
        primary      = Color(0xFFDB2777),
        primaryDark  = Color(0xFF9D174D),
        primaryLight = Color(0xFFFCE7F3),
        accent       = Color(0xFFF59E0B),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFFDF2F8)
    ),
    SLATE_CHARCOAL(
        label = "Grau", emoji = "🩶",
        primary      = Color(0xFF64748B),
        primaryDark  = Color(0xFF334155),
        primaryLight = Color(0xFFE2E8F0),
        accent       = Color(0xFFF59E0B),
        accentLight  = Color(0xFFFEF3C7),
        background   = Color(0xFFF8FAFC)
    ),
    MIDNIGHT_INDIGO(
        label = "Schwarz", emoji = "🖤",
        primary      = Color(0xFF1E293B),
        primaryDark  = Color(0xFF0F172A),
        primaryLight = Color(0xFFCBD5E1),
        accent       = Color(0xFF38BDF8),
        accentLight  = Color(0xFFE0F2FE),
        background   = Color(0xFFF1F5F9)
    ),
    // Legacy – bleiben gültig, erscheinen nicht im Picker
    MINT_FRESH(
        label = "Mint", emoji = "\uD83C\uDF43",
        primary = Color(0xFF2DD4BF), primaryDark = Color(0xFF0F9B8E), primaryLight = Color(0xFFD9FBF0),
        accent = Color(0xFF0EA5E9), accentLight = Color(0xFFE0F2FE), background = Color(0xFFECFDF5),
        showInPicker = false
    ),
    GOLDEN_AMBER(
        label = "Golden", emoji = "\uD83C\uDF6F",
        primary = Color(0xFFD97706), primaryDark = Color(0xFF92400E), primaryLight = Color(0xFFFEF3C7),
        accent = Color(0xFF0891B2), accentLight = Color(0xFFCFFAFE), background = Color(0xFFFEFCE8),
        showInPicker = false
    ),
    CITRUS_ZEST(
        label = "Citrus", emoji = "\uD83C\uDF4B",
        primary = Color(0xFF65A30D), primaryDark = Color(0xFF3F6212), primaryLight = Color(0xFFECFCCB),
        accent = Color(0xFFDB2777), accentLight = Color(0xFFFCE7F3), background = Color(0xFFFAFDF0),
        showInPicker = false
    );

    companion object {
        val pickerThemes: List<AppTheme> get() = entries.filter { it.showInPicker }
    }

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
        // Primär: gesättigte Markenfarbe, weiße Icons darauf (hoher Kontrast)
        primary            = primary,
        onPrimary          = Color.White,
        primaryContainer   = primaryDark,
        onPrimaryContainer = primaryLight,
        // Secondary: ebenfalls gesättigt – verhindert „zu helle“ FABs/Icons
        secondary          = accent,
        onSecondary        = Color.White,
        secondaryContainer = primaryDark,
        onSecondaryContainer = primaryLight,
        tertiary           = accent,
        onTertiary         = Color.White,
        tertiaryContainer  = primaryDark,
        onTertiaryContainer = primaryLight,
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
        surfaceTint        = primary
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
/** Anteil am Tages-Kalorienziel pro Mahlzeit, JSON z.B. {"BREAKFAST":0.25,"LUNCH":0.35,"DINNER":0.30,"SNACK":0.10} */
val KEY_MEAL_SPLIT = stringPreferencesKey("meal_split_json")
val KEY_AUTO_GERMAN_METRIC = androidx.datastore.preferences.core.booleanPreferencesKey("auto_german_metric")
val KEY_MANUAL_ACTIVITY_ENABLED = androidx.datastore.preferences.core.booleanPreferencesKey("manual_activity_enabled")
val KEY_AGGRESSIVE_SPORT_DAY = androidx.datastore.preferences.core.booleanPreferencesKey("aggressive_sport_day")
/** JSON-Map recipeId → Sterne 1–5, z.B. {"12":4,"3":5} */
val KEY_RECIPE_RATINGS = stringPreferencesKey("recipe_ratings_json")
/** FreshBatch-inspiriertes UI (Experiment, default aus). */
val KEY_FRESH_UI = androidx.datastore.preferences.core.booleanPreferencesKey("fresh_ui")
val KEY_FRESH_RECIPE_CARDS = androidx.datastore.preferences.core.booleanPreferencesKey("fresh_recipe_cards")
val KEY_FRESH_HOME = androidx.datastore.preferences.core.booleanPreferencesKey("fresh_home")
/** Strukturierte Rezept-Detail-Ansicht: ausgerichtete Menge|Einheit|Name, klarere Gruppen (default aus). */
val KEY_FRESH_RECIPE_DETAIL = androidx.datastore.preferences.core.booleanPreferencesKey("fresh_recipe_detail")
/** true = klassische 1-Spalten-Liste (bisheriges Design), false/absent = kompakte 2-Spalten-Grid-Übersicht */
val KEY_CLASSIC_RECIPE_LIST = androidx.datastore.preferences.core.booleanPreferencesKey("classic_recipe_list")
/**
 * Ziel-Zeilen sichtbarer Rezept-Kacheln auf einem Phone-Bildschirm (Grid).
 * Erlaubt: 6 (Standard, 3 Zeilen) oder 4 (größere Kacheln wie früher, 2 Zeilen).
 */
val KEY_RECIPE_GRID_DENSITY = intPreferencesKey("recipe_grid_density")
/**
 * Spaltenanzahl im Rezept-Grid (Phone). 2 (Standard) oder 3.
 * Tablets nutzen weiter WindowInfo-Breakpoints als Untergrenze.
 */
val KEY_RECIPE_GRID_COLUMNS = intPreferencesKey("recipe_grid_columns")

/**
 * Rezept-Import-Experimente (default aus = bisheriges Verhalten).
 * Fast AI: Groq openai/gpt-oss-20b statt openai/gpt-oss-120b.
 * Fast Scrape: kürzerer Instagram-Race-Timeout, weniger Late-Mirrors.
 */
val KEY_RECIPE_FAST_AI_PARSE = androidx.datastore.preferences.core.booleanPreferencesKey("recipe_fast_ai_parse")
val KEY_RECIPE_FAST_SCRAPE = androidx.datastore.preferences.core.booleanPreferencesKey("recipe_fast_scrape")
/** Persistenter Caption-Cache über App-Neustart (default an). */
val KEY_RECIPE_PERSISTENT_CACHE = androidx.datastore.preferences.core.booleanPreferencesKey("recipe_persistent_cache")
/** Bei schwacher Caption Video via Groq-Whisper transkribieren (default aus). */
val KEY_RECIPE_VIDEO_TRANSCRIPT = androidx.datastore.preferences.core.booleanPreferencesKey("recipe_video_transcript")

// ── Design-Toggles (Design-Backlog, einzeln schaltbar unter Mehr → Design) ────
// Touch-Targets (Default an)
val KEY_TOGGLE_TOUCH_MEAL_QUICKADD = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_touch_meal_quickadd")
val KEY_TOGGLE_TOUCH_MEAL_ICON = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_touch_meal_icon")
val KEY_TOGGLE_TOUCH_DIARY_ICONS = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_touch_diary_icons")
val KEY_TOGGLE_TOUCH_MEAL_HEADER = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_touch_meal_header")
val KEY_TOGGLE_TOUCH_YESTERDAY_BTN = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_touch_yesterday_btn")
val KEY_TOGGLE_TOUCH_RECIPE_MENU = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_touch_recipe_menu")
// Farben (Default aus, außer Cropper)
val KEY_TOGGLE_MACRO_COLOR_SEPARATION = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_macro_color_separation")
val KEY_TOGGLE_CARD_ELEVATION = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_card_elevation")
val KEY_TOGGLE_DARK_MODE_CONTRAST = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_dark_mode_contrast")
val KEY_TOGGLE_CROPPER_THEME_COLOR = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_cropper_theme_color")
val KEY_TOGGLE_CALORIES_REMAINING_HIGHLIGHT = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_calories_remaining_highlight")
// Layout
val KEY_TOGGLE_SPACING_TOKENS = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_spacing_tokens")
val KEY_TOGGLE_HOME_ACTIVITY_MERGE = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_home_activity_merge")
val KEY_TOGGLE_HOME_REORDER = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_home_reorder")
val KEY_TOGGLE_DIARY_LAYOUT_COMPACT = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_diary_layout_compact")
// FABs & Buttons
val KEY_TOGGLE_DIARY_FAB_CONSOLIDATION = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_diary_fab_consolidation")
val KEY_TOGGLE_RECIPES_FAB_CONSOLIDATION = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_recipes_fab_consolidation")
val KEY_TOGGLE_BUTTON_STANDARD_SIZING = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_button_standard_sizing")
val KEY_TOGGLE_RECIPE_CHIP_SIZING = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_recipe_chip_sizing")
val KEY_TOGGLE_PROGRESS_BAR_COLOR_SHIFT = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_progress_bar_color_shift")
// Typografie
val KEY_TOGGLE_TYPOGRAPHY_NUMBERS = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_typography_numbers")
// Navigation
val KEY_TOGGLE_NAV_LABEL_RENAME = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_nav_label_rename")
val KEY_TOGGLE_NAV_SHORTCUTS = androidx.datastore.preferences.core.booleanPreferencesKey("toggle_nav_shortcuts")

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
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
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
