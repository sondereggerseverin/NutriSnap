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
import ch.nutrisnap.app.ui.theme.Coral
import ch.nutrisnap.app.ui.theme.Green400
import ch.nutrisnap.app.ui.theme.Green700

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

    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${calories.toInt()} kcal", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("gegessen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${goal.toInt()}", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Ziel", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${remaining.toInt()} kcal", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        color = if (remaining > 0) Green400 else Coral)
                    Text(if (remaining > 0) "übrig" else "überschritten", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            // Fixed: use lambda form for progress (required by Compose Material3 1.2+)
            LinearProgressIndicator(
                progress        = { progress },
                modifier        = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color           = if (progress < 1f) Green700 else Coral,
                trackColor      = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap       = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MacroChip("Protein",  protein, Color(0xFF3B82F6))
                MacroChip("Kohlenh.", carbs,   Color(0xFFF59E0B))
                MacroChip("Fett",     fat,     Color(0xFFEF4444))
            }
        }
    }
}

/**
 * Nährwert-Fortschrittsbalken im Yazio-Stil: zeigt für Kalorien/Kohlenhydrate/
 * Protein/Fett je eine "verzehrt / Ziel"-Zeile mit Fortschrittsbalken.
 * Wird sowohl für Tages- als auch für Mahlzeit-Werte (gegen das Tagesziel) genutzt.
 */
@Composable
fun NutritionFactsProgress(
    calories: Float, caloriesGoal: Float,
    carbs: Float,    carbsGoal: Float,
    protein: Float,  proteinGoal: Float,
    fat: Float,      fatGoal: Float,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NutritionProgressRow("Kalorien", calories, caloriesGoal, "Cal", Green700)
            NutritionProgressRow("Kohlenh.", carbs, carbsGoal, "g", Color(0xFFF59E0B))
            NutritionProgressRow("Protein", protein, proteinGoal, "g", Color(0xFF3B82F6))
            NutritionProgressRow("Fett", fat, fatGoal, "g", Color(0xFFEF4444))
        }
    }
}

@Composable
private fun NutritionProgressRow(label: String, value: Float, goal: Float, unit: String, color: Color) {
    val progress = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text("${value.toInt()} / ${goal.toInt()} $unit", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress   = { progress },
            modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
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

/** Mikronaehrstoff-Tabelle im Yazio-Stil: Sonstiges / Vitamine / Mineralstoffe. */
@Composable
fun MicronutrientTable(
    perServing: Map<String, Float>,
    ratio: Float,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val subColor = contentColor.copy(alpha = 0.85f)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        listOf("Sonstiges" to MICRO_OTHER, "Vitamine" to MICRO_VITAMINS, "Mineralstoffe" to MICRO_MINERALS)
            .forEach { (groupLabel, keys) ->
                val rows = keys.mapNotNull { key -> perServing[key]?.let { key to it } }
                if (rows.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(groupLabel, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = contentColor)
                    rows.forEach { (key, value) ->
                        val (label, unit, factor) = MICRO_META.getValue(key)
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, fontSize = 12.sp, color = subColor)
                            val display = value * ratio * factor
                            val formatted = if (display < 1f && display > 0f) "< 1" else display.toInt().toString()
                            Text("$formatted $unit", fontSize = 12.sp, color = subColor)
                        }
                    }
                }
            }
    }
}

@Composable
private fun MacroChip(label: String, grams: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(4.dp))
        Text("${grams.toInt()}g", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground)
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: @Composable () -> Unit, message: String, sub: String = "") {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(message, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
