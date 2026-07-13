package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

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
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(NutriSpacing.lg), verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
            NutritionProgressRow("Kalorien", calories, caloriesGoal, "Cal", MacroColors.calories)
            NutritionProgressRow("Kohlenh.", carbs, carbsGoal, "g", MacroColors.carbs)
            NutritionProgressRow("Protein", protein, proteinGoal, "g", MacroColors.protein)
            NutritionProgressRow("Fett", fat, fatGoal, "g", MacroColors.fat)
        }
    }
}

@Composable
private fun NutritionProgressRow(label: String, value: Float, goal: Float, unit: String, color: Color) {
    val progress = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
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
            color      = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap  = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

/** label, Anzeige-Einheit, Umrechnungsfaktor von Gramm in die Anzeige-Einheit. */
val MICRO_META: Map<String, Triple<String, String, Float>> = mapOf(
    "fiber" to Triple("Ballaststoffe", "g", 1f),
    "sugar" to Triple("Zucker", "g", 1f),
    "saturatedFat" to Triple("Gesättigte Fettsäuren", "g", 1f),
    "monoFat" to Triple("Einfach ungesättigt", "g", 1f),
    "polyFat" to Triple("Mehrfach ungesättigt", "g", 1f),
    "transFat" to Triple("Trans-Fette", "g", 1f),
    "alcohol" to Triple("Alkohol", "g", 1f),
    "cholesterol" to Triple("Cholesterin", "mg", 1000f),
    "salt" to Triple("Salz", "g", 1f),
    "sodium" to Triple("Natrium", "mg", 1000f),
    "water" to Triple("Wasser", "g", 1f),
    "vitaminA" to Triple("Vitamin A", "µg", 1_000_000f),
    "vitaminB1" to Triple("Vitamin B1 (Thiamin)", "mg", 1000f),
    "vitaminB2" to Triple("Vitamin B2 (Riboflavin)", "mg", 1000f),
    "vitaminB3" to Triple("Vitamin B3 (Niacin)", "mg", 1000f),
    "vitaminB5" to Triple("Vitamin B5 (Pantothensäure)", "mg", 1000f),
    "vitaminB6" to Triple("Vitamin B6", "mg", 1000f),
    "vitaminB7" to Triple("Vitamin B7 (Biotin)", "µg", 1_000_000f),
    "vitaminB11" to Triple("Vitamin B11 (Folsäure)", "µg", 1_000_000f),
    "vitaminB12" to Triple("Vitamin B12", "µg", 1_000_000f),
    "vitaminC" to Triple("Vitamin C", "mg", 1000f),
    "vitaminD" to Triple("Vitamin D", "µg", 1_000_000f),
    "vitaminE" to Triple("Vitamin E", "mg", 1000f),
    "vitaminK" to Triple("Vitamin K", "µg", 1_000_000f),
    "potassium" to Triple("Kalium", "mg", 1000f),
    "calcium" to Triple("Calcium", "mg", 1000f),
    "iron" to Triple("Eisen", "mg", 1000f),
    "magnesium" to Triple("Magnesium", "mg", 1000f),
    "zinc" to Triple("Zink", "mg", 1000f),
    "phosphorus" to Triple("Phosphor", "mg", 1000f),
    "copper" to Triple("Kupfer", "mg", 1000f),
    "manganese" to Triple("Mangan", "mg", 1000f),
    "fluoride" to Triple("Fluorid", "mg", 1000f),
    "iodine" to Triple("Jod", "µg", 1_000_000f),
    "selenium" to Triple("Selen", "µg", 1_000_000f),
    "chromium" to Triple("Chrom", "µg", 1_000_000f),
    "molybdenum" to Triple("Molybdän", "µg", 1_000_000f),
    "chloride" to Triple("Chlorid", "mg", 1000f),
    "choline" to Triple("Cholin", "mg", 1000f),
    "arsenic" to Triple("Arsen", "µg", 1_000_000f),
    "boron" to Triple("Bor", "mg", 1000f),
    "cobalt" to Triple("Kobalt", "µg", 1_000_000f),
    "rubidium" to Triple("Rubidium", "mg", 1000f),
    "silicon" to Triple("Silizium", "mg", 1000f),
    "sulfur" to Triple("Schwefel", "mg", 1000f),
    "tin" to Triple("Zinn", "mg", 1000f),
    "vanadium" to Triple("Vanadium", "µg", 1_000_000f)
)
private val MICRO_OTHER = listOf("fiber","sugar","saturatedFat","monoFat","polyFat","transFat","alcohol","cholesterol","salt","sodium","water")
private val MICRO_VITAMINS = listOf("vitaminA","vitaminB1","vitaminB2","vitaminB3","vitaminB5","vitaminB6","vitaminB7","vitaminB11","vitaminB12","vitaminC","vitaminD","vitaminE","vitaminK")
private val MICRO_MINERALS = listOf("potassium","calcium","iron","magnesium","zinc","phosphorus","copper","manganese","fluoride","iodine","selenium","chromium","molybdenum","chloride","choline","arsenic","boron","cobalt","rubidium","silicon","sulfur","tin","vanadium")

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
                            val display = value * ratio * factor
                            val formatted = if (display in 0.01f..0.99f) "< 1" else display.toInt().toString()
                            Text("$formatted $unit", fontSize = 12.sp, color = subColor)
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
