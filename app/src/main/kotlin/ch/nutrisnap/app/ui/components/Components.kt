package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nutrisnap.app.domain.MICRO_META
import ch.nutrisnap.app.domain.MICRO_MINERALS
import ch.nutrisnap.app.domain.MICRO_OTHER
import ch.nutrisnap.app.domain.MICRO_VITAMINS
import ch.nutrisnap.app.domain.NRV_REFERENCE
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_TOGGLE_CARD_ELEVATION
import ch.nutrisnap.app.ui.theme.KEY_TOGGLE_PROGRESS_BAR_COLOR_SHIFT
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

/**
 * Design-Toggle #10 "Card-Elevation erhöhen" (Mehr → Design).
 * Zentraler Ersatz für die ~193 einzeln aufgesetzten `Card(...)`-Stellen mit dem
 * "flachen" Standard-Look (1dp / surface / weiss). Default = exakt das bisherige
 * Verhalten (1dp, surface). Bei aktiviertem Toggle: 2dp Elevation + surfaceContainer
 * statt weiss – A/B-vergleichbar, bevor es fest verdrahtet wird (siehe
 * docs/design-audit-2026-08.md §4.3).
 *
 * Nur für die "neutralen" Karten gedacht (containerColor = surface/weiss). Karten mit
 * bewusst eingefärbtem Container (z. B. MacroColors-Tint, secondaryContainer, reine
 * Border-Karten ohne Elevation) bleiben unverändert `Card(...)` – die würden vom
 * Toggle sonst falsch überschrieben.
 */
@Composable
fun NutriCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(NutriRadius.lg),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val elevated = prefs?.get(KEY_TOGGLE_CARD_ELEVATION) ?: false
    val colors = CardDefaults.cardColors(
        containerColor = if (elevated) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface
    )
    val elevation = CardDefaults.cardElevation(if (elevated) 2.dp else 1.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, elevation = elevation, content = content)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, elevation = elevation, content = content)
    }
}

@Composable
fun MacroBar(
    calories: Float,
    goal: Float,
    protein: Float,
    carbs: Float,
    fat: Float,
    modifier: Modifier = Modifier
) {
    val progress  = (calories / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val remaining = (goal - calories).coerceAtLeast(0f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(NutriSpacing.lg)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "${calories.toInt()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "von ${goal.toInt()} kcal",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${remaining.toInt()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = if (remaining > 0) MacroColors.calories else MaterialTheme.colorScheme.error
                    )
                    Text(
                        if (remaining > 0) "übrig" else "überschritten",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(NutriSpacing.md))

            LinearProgressIndicator(
                progress        = { progress },
                modifier        = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color           = if (progress < 1f) MacroColors.calories else MaterialTheme.colorScheme.error,
                trackColor      = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap       = androidx.compose.ui.graphics.StrokeCap.Round
            )

            Spacer(Modifier.height(NutriSpacing.lg))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                MacroChip("Protein",  protein, MacroColors.protein)
                MacroChip("Kohlenh.", carbs,   MacroColors.carbs)
                MacroChip("Fett",     fat,     MacroColors.fat)
            }
        }
    }
}

/**
 * Nährwert-Fortschrittsbalken im Yazio-Stil
 */
@Composable
fun NutritionFactsProgress(
    calories: Float, caloriesGoal: Float,
    carbs: Float,    carbsGoal: Float,
    protein: Float,  proteinGoal: Float,
    fat: Float,      fatGoal: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val colorShiftOnOverGoal = prefs?.get(KEY_TOGGLE_PROGRESS_BAR_COLOR_SHIFT) ?: false
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(NutriSpacing.lg), verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
            NutritionProgressRow("Kalorien", calories, caloriesGoal, "Cal", MacroColors.calories, colorShiftOnOverGoal)
            NutritionProgressRow("Kohlenh.", carbs, carbsGoal, "g", MacroColors.carbs, colorShiftOnOverGoal)
            NutritionProgressRow("Protein", protein, proteinGoal, "g", MacroColors.protein, colorShiftOnOverGoal)
            NutritionProgressRow("Fett", fat, fatGoal, "g", MacroColors.fat, colorShiftOnOverGoal)
        }
    }
}

@Composable
private fun NutritionProgressRow(
    label: String,
    value: Float,
    goal: Float,
    unit: String,
    color: Color,
    colorShiftOnOverGoal: Boolean = false
) {
    val progress = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val overGoal = value > goal && goal > 0f
    val barColor = if (colorShiftOnOverGoal && overGoal) MaterialTheme.colorScheme.error else color
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${value.toInt()} / ${goal.toInt()} $unit",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress   = { progress },
            modifier   = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color      = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap  = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

// MICRO_META / NRV_REFERENCE / MICRO_OTHER / MICRO_VITAMINS / MICRO_MINERALS wurden nach
// domain/NutrientReferenceData.kt verschoben (Single Source of Truth, auch vom
// NutrientDeficiencyEngine genutzt). Re-exportiert via Import unten, damit bestehende
// Verweise auf ch.nutrisnap.app.ui.components.MICRO_META etc. weiter funktionieren.

@Composable
fun MicronutrientTable(
    perServing: Map<String, Float>,
    ratio: Float,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val subColor = contentColor.copy(alpha = 0.85f)
    Column(Modifier.fillMaxWidth().padding(top = NutriSpacing.xs)) {
        listOf("Sonstiges" to MICRO_OTHER, "Vitamine" to MICRO_VITAMINS, "Mineralstoffe" to MICRO_MINERALS)
            .forEach { (groupLabel, keys) ->
                val rows = keys.mapNotNull { key -> perServing[key]?.let { key to it } }
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.height(NutriSpacing.sm))
                    Text(
                        groupLabel,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = contentColor
                    )
                    rows.forEach { (key, value) ->
                        val (label, unit, factor) = MICRO_META.getValue(key)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(label, fontSize = 12.sp, color = subColor)
                            val grams = value * ratio
                            val display = grams * factor
                            val formatted = when {
                                key == "fiber" -> "%.1f".format(display)
                                display in 0.01f..0.99f -> "< 1"
                                else -> display.toInt().toString()
                            }
                            val nrvPct = NRV_REFERENCE[key]?.let { ref -> ((grams / ref) * 100f).toInt() }
                            Text(
                                "$formatted $unit" + (nrvPct?.let { "  ·  $it% NRV" } ?: ""),
                                fontSize = 12.sp, color = subColor
                            )
                        }
                    }
                }
            }
    }
}

@Composable
private fun MacroChip(label: String, grams: Float, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(NutriRadius.sm))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm)
    ) {
        Text(
            "${grams.toInt()}g",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = color
        )
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color.copy(alpha = 0.8f)
        )
    }
}

@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: @Composable () -> Unit, message: String, sub: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(NutriSpacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(NutriRadius.xl))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(Modifier.height(NutriSpacing.lg))
        Text(
            message,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(NutriSpacing.xs))
            Text(
                sub,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
