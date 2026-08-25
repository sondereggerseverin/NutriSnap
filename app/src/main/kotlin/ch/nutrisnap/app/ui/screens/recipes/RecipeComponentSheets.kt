package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiComponentAddToDiarySheet(
    recipe: Recipe,
    components: List<RecipeComponent>,
    onConfirm: (gramsByComponentId: Map<Long, Float>, meal: MealType, date: LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onFreeze: ((gramsByComponentId: Map<Long, Float>, quantity: Int) -> Unit)? = null
) {
    // Nährwerte bevorzugt aus Matches ableiten (Fallback: proportionale Heilung)
    val comps = remember(recipe.id, components) { enrichComponentsFromMatches(recipe, components, emptyList()) }
    var equalMode by remember { mutableStateOf(false) }
    var portionsText by remember { mutableStateOf(recipe.servings.coerceAtLeast(1).toString()) }
    var gramsTexts by remember(comps) {
        mutableStateOf(
            comps.associate { c ->
                // Vorschlag: eine gleichmässige Portion
                val suggested = if (c.cookedWeightG > 0f && recipe.servings > 0)
                    (c.cookedWeightG / recipe.servings).toInt().toString()
                else ""
                c.id to suggested
            }
        )
    }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var freezeQtyText by remember { mutableStateOf("1") }

    val portions = portionsText.toIntOrNull()?.coerceAtLeast(1) ?: 1

    // Im Equal-Mode: Gramm pro Komponente aus Portionszahl ableiten
    val effectiveGrams: Map<Long, Float> = if (equalMode) {
        comps.associate { c ->
            val g = if (c.cookedWeightG > 0f) c.cookedWeightG / portions else 0f
            c.id to g
        }
    } else {
        comps.associate { c ->
            val g = gramsTexts[c.id]?.replace(',', '.')?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
            c.id to g
        }
    }

    val totalCals = comps.sumOf { c ->
        c.scaledTo(effectiveGrams[c.id] ?: 0f).calories.toDouble()
    }.toFloat()
    val totalProtein = comps.sumOf { c ->
        c.scaledTo(effectiveGrams[c.id] ?: 0f).protein.toDouble()
    }.toFloat()

    var allowTrackDismiss by remember { mutableStateOf(false) }
    val trackSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (newValue == SheetValue.Hidden) allowTrackDismiss else true
        }
    )
    fun requestTrackDismiss() {
        allowTrackDismiss = true
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = { requestTrackDismiss() },
        sheetState = trackSheetState
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ins Tagebuch", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { requestTrackDismiss() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Schliessen")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(recipe.displayTitle(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))

            // Mode toggle
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !equalMode,
                    onClick = { equalMode = false },
                    label = { Text("Getrennt abwiegen") }
                )
                FilterChip(
                    selected = equalMode,
                    onClick = { equalMode = true },
                    label = { Text("Gleichmässig (Meal-Prep)") }
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (equalMode)
                    "Feste Portionszahl – Ratio wie im Rezept, ideal für gleiche Boxen."
                else
                    "Jede Komponente einzeln abwiegen (z. B. 400 g Reis, 360 g Sauce).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (equalMode) {
                OutlinedTextField(
                    value = portionsText,
                    onValueChange = { portionsText = it },
                    label = { Text("Anzahl gleicher Portionen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                comps.forEach { c ->
                    val g = effectiveGrams[c.id] ?: 0f
                    val dens = if (c.cookedWeightG > 0f)
                        (c.totalCalories / c.cookedWeightG * 100f).toInt() else 0
                    Text(
                        "• ${c.name}: ${g.toInt()} g  (von ${c.cookedWeightG.toInt()} g · $dens kcal/100g)",
                        fontSize = 13.sp
                    )
                }
            } else {
                comps.forEach { c ->
                    val dens = if (c.cookedWeightG > 0f)
                        (c.totalCalories / c.cookedWeightG * 100f).toInt() else 0
                    OutlinedTextField(
                        value = gramsTexts[c.id] ?: "",
                        onValueChange = { v ->
                            gramsTexts = gramsTexts.toMutableMap().also { it[c.id] = v }
                        },
                        label = { Text("${c.name} (g)") },
                        supportingText = {
                            Text(
                                "Batch: ${c.cookedWeightG.toInt()} g · " +
                                    "${c.totalCalories.toInt()} kcal · $dens kcal/100g"
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            if (totalCals > 0f) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Summe: ${totalCals.toInt()} kcal · ${totalProtein.toInt()} g Protein",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Mahlzeit", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MealType.entries.forEach { mt ->
                    val label = when (mt) {
                        MealType.BREAKFAST -> "Frühstück"
                        MealType.LUNCH -> "Mittag"
                        MealType.DINNER -> "Abend"
                        MealType.SNACK -> "Snack"
                    }
                    FilterChip(
                        selected = selectedMeal == mt,
                        onClick = { selectedMeal = mt },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Tag", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val today = LocalDate.now()
                listOf(
                    today.minusDays(2) to "Vorgestern",
                    today.minusDays(1) to "Gestern",
                    today to "Heute",
                    today.plusDays(1) to "Morgen"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = selectedDate == d,
                        onClick = { selectedDate = d },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { requestTrackDismiss() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Abbrechen")
                }
                Button(
                    onClick = {
                        val filtered = effectiveGrams.filter { it.value >= 1f }
                        if (filtered.isNotEmpty()) onConfirm(filtered, selectedMeal, selectedDate)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = effectiveGrams.any { it.value >= 1f }
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
            }
            if (onFreeze != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = freezeQtyText,
                        onValueChange = { freezeQtyText = it.filter { c -> c.isDigit() } },
                        label = { Text("Packungen") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(110.dp)
                    )
                    OutlinedButton(
                        onClick = {
                            val filtered = effectiveGrams.filter { it.value >= 1f }
                            val q = freezeQtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            if (filtered.isNotEmpty()) onFreeze(filtered, q)
                        },
                        enabled = effectiveGrams.any { it.value >= 1f },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AcUnit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Einfrieren")
                    }
                }
            }
        }
    }
}


/**
 * Multi-Komponenten-Trennung: Zutaten beliebig vielen Teilen zuordnen + Kochgewicht.
 * Rückwärtskompatibel zu "side"/"sauce" (angezeigt als Beilage / Sauce / Fleisch).
 * Unabhängig vom Verify-Flow (der nur Nährwerte prüft).
 */
/**
 * Liest Abschnittsüberschriften aus dem Zutaten-Text (wie in der Rezept-Ansicht).
 * Header = Zeile ohne •/-/Ziffer am Anfang. Liefert (Abschnittsname → Zutatenzeilen).
 */
internal fun parseIngredientSections(ingredients: String): List<Pair<String, List<String>>> {
    if (ingredients.isBlank()) return emptyList()
    val sections = mutableListOf<Pair<String, MutableList<String>>>()
    var currentName: String? = null
    var currentLines = mutableListOf<String>()

    fun isHeader(line: String): Boolean {
        val d = line.trim().trimStart('•', '-', '*', ' ').trim()
        if (d.length <= 2) return false
        if (d.first().isDigit()) return false
        // Menge in der Zeile → Zutat, kein Header (auch "1 Ei", "Prise Salz" mit Kontext)
        if (Regex(
                """\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|oz|stück|stk)\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(d)
        ) return false
        // "1 Ei", "2 Eier", "Prise Salz" = Zutat, kein Abschnitt
        if (Regex("""(?i)^\d+\s+(ei|eier|prise|bund|zehe|scheibe|dose)\b""").containsMatchIn(d)) {
            return false
        }
        if (Regex("""(?i)^(prise|etwas|wenig)\s+\p{L}""").containsMatchIn(d)) return false
        val lc = d.lowercase().trimEnd(':').trim()
        // Anleitungssätze nie als Abschnitt (auch „für den … vermengen“)
        if (Regex(
                """(?i)\b(vermischen|vermengen|verrühren|verruehren|unterheben|backen|""" +
                    """drücken|toppen|kochen)\b"""
            ).containsMatchIn(d)
        ) return false
        if (d.length > 55) return false
        // Explizite Abschnitts-Muster (inkl. Back-Abschnitte) – NICHT jede kurze Zeile
        val shortFuer = Regex("""(?i)^für\s+(die|den|das)\s+[^:]{2,40}$""").matches(lc) ||
            Regex("""(?i)^for\s+the\s+[^:]{2,40}$""").matches(lc)
        if (shortFuer ||
            (d.trim().endsWith(":") && d.length in 4..48 && !d.any { it.isDigit() }) ||
            lc == "dough" || lc == "teig" || lc == "filling" || lc == "füllung" || lc == "fuellung" ||
            lc == "frosting" || lc == "glasur" || lc == "syrup" || lc == "sirup" ||
            lc == "topping" || lc == "belag" || lc == "boden" || lc == "crust" ||
            lc.endsWith(" filling") || lc.endsWith(" füllung") || lc.endsWith(" fuellung") ||
            lc.endsWith(" frosting") || lc.endsWith(" glasur") ||
            lc.endsWith(" syrup") || lc.endsWith(" sirup") ||
            lc.endsWith(" dough") || lc.endsWith(" teig") ||
            lc.endsWith(" cookie teig") || lc.endsWith(" cheesecake teig") ||
            lc.endsWith("-füllung") || lc.endsWith("-fuellung") ||
            lc.endsWith("-frosting") || lc.endsWith("-sirup") || lc.endsWith("-teig") ||
            lc.endsWith("-sauce") || lc.endsWith("-glasur") ||
            // "Raspberry Cookie Teig", "Cheesecake Teig" ohne Doppelpunkt
            (lc.contains("teig") && d.length in 4..48 && !d.any { it.isDigit() })
        ) return true
        // Reine GROSSBUCHSTABEN ohne Menge = Social-Caption-Header
        val lettersOnly = d.filter { it.isLetter() || it.isWhitespace() || it == '-' || it == '&' }
        if (lettersOnly.isNotBlank() && lettersOnly == lettersOnly.uppercase() &&
            lettersOnly.replace(" ", "").length in 3..40
        ) return true
        // Kein pauschales true mehr – sonst wird „Prise Salz“ zum Abschnitt
        return false
    }

    fun flush() {
        val name = currentName ?: return
        if (currentLines.isNotEmpty() || sections.isEmpty()) {
            sections += name to currentLines
        }
        currentLines = mutableListOf()
    }

    for (raw in ingredients.lineSequence()) {
        val line = raw.trim()
        if (line.isBlank()) continue
        if (isHeader(line)) {
            flush()
            currentName = line.trimEnd(':').trim()
            currentLines = mutableListOf()
        } else {
            if (currentName == null) {
                currentName = "Sonstiges"
            }
            currentLines += line.trimStart('•', '-', '*', ' ').trim()
        }
    }
    flush()
    // Mindestens 2 Abschnitte mit Zutaten, sonst nicht brauchbar
    return sections.filter { it.second.isNotEmpty() }
}

/**
 * Ob ein Rezept in trackbare Komponenten geteilt werden darf.
 * - Kategorie MAIN/SIDE/SAUCE/OTHER (mit Heuristik-Schutz gegen falsch gespeichertes MAIN)
 * - ODER ≥2 Abschnitte im Zutaten-Text (auch Dessert/Frühstück: Teig/Füllung/Frosting)
 */
internal fun recipeAllowsComponentSplit(recipe: Recipe): Boolean {
    if (parseIngredientSections(recipe.ingredients).size >= 2) return true
    val stored = recipe.withGuessedCategoryIfEmpty().category()
    val guessed = RecipeCategory.guess(recipe.title, recipe.ingredients, recipe.description)
    return stored.allowsComponentSplit && guessed.allowsComponentSplit
}

/** Ordnet eine Match-Zeile einem Abschnitts-Key zu (Name-Ähnlichkeit). */
/**
 * Score 0..100 wie gut ein Match zu einer Abschnitts-Zutatenzeile passt.
 */
internal fun scoreMatchToLine(m: IngredientMatch, line: String): Int {
    fun coreOf(s: String): String =
        s.lowercase()
            .trim()
            .trimStart('•', '-', '*', ' ')
            .replace(Regex("""^\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|oz)?\s*"""), "")
            .trim()

    val rawCore = coreOf(m.ingredientRaw)
    val nameCore = coreOf(m.ingredientName).ifBlank { rawCore }
    val matched = coreOf(m.matchedFoodName.orEmpty())
    val l = coreOf(line)
    if (l.length < 2) return 0

    var best = 0
    if (rawCore.length >= 3 && (l == rawCore || l.contains(rawCore) || rawCore.contains(l))) best = maxOf(best, 90)
    if (nameCore.length >= 3 && (l == nameCore || l.contains(nameCore) || nameCore.contains(l))) best = maxOf(best, 85)
    if (matched.length >= 3 && (l.contains(matched) || matched.contains(l))) best = maxOf(best, 70)

    val tokensL = l.split(Regex("""[\s,;/&]+""")).filter { it.length >= 4 }
    val tokensM = ("$rawCore $nameCore $matched").split(Regex("""[\s,;/&]+""")).filter { it.length >= 4 }
    val overlap = tokensL.count { t -> tokensM.any { it.startsWith(t.take(5)) || t.startsWith(it.take(5)) } }
    if (overlap > 0) best = maxOf(best, 40 + overlap * 15)
    return best.coerceAtMost(100)
}

/**
 * Ordnet jeden Match genau einem Abschnitt zu (beste Score, 1:1 pro Zeile wenn möglich).
 * Verhindert, dass dieselbe Zutat in mehreren Teilen landet.
 */
internal fun assignMatchesToSections(
    matches: List<IngredientMatch>,
    sections: List<Pair<String, List<String>>>
): Map<Int, String> {
    if (matches.isEmpty()) return emptyMap()
    if (sections.isEmpty()) return emptyMap()

    data class Cand(val matchIdx: Int, val section: String, val lineIdx: Int, val score: Int)

    val cands = mutableListOf<Cand>()
    matches.forEachIndexed { mi, m ->
        sections.forEach { (sectionName, lines) ->
            lines.forEachIndexed { li, line ->
                val s = scoreMatchToLine(m, line)
                if (s >= 40) cands += Cand(mi, sectionName, li, s)
            }
        }
    }
    // Beste zuerst
    cands.sortByDescending { it.score }

    val assignedMatch = mutableSetOf<Int>()
    // Pro Abschnitt+Zeile nur ein Match (wichtig bei x2-Rezept: zwei identische Zeilen → zwei Slots)
    val assignedLine = mutableSetOf<String>() // "section|lineIdx" but allow same line text twice via index
    val result = mutableMapOf<Int, String>()

    for (c in cands) {
        if (c.matchIdx in assignedMatch) continue
        val lineKey = "${c.section}\u0000${c.lineIdx}"
        // Zeile darf mehrfach vergeben werden wenn Score sehr hoch und weitere Matches warten
        // (x2-Rezept: zwei "600g Hähnchen" → beide auf Hähnchen-Zeile)
        // Deshalb line-Lock nur bei mittlerem Score
        if (c.score < 80 && lineKey in assignedLine) continue
        assignedMatch += c.matchIdx
        assignedLine += lineKey
        result[c.matchIdx] = c.section
    }

    // Rest: Fallback-Heuristik pro Match (nie unzugeordnet lassen)
    matches.forEachIndexed { mi, m ->
        if (mi in result) return@forEachIndexed
        // Persistierte Gruppe hat Vorrang
        normalizeGroupKey(m.componentGroup)?.let { ng ->
            sections.firstOrNull { it.first.equals(m.componentGroup, true) }?.let {
                result[mi] = it.first
                return@forEachIndexed
            }
            sections.firstOrNull { normalizeGroupKey(it.first) == ng }?.let {
                result[mi] = it.first
                return@forEachIndexed
            }
        }
        val n = "${m.ingredientRaw} ${m.ingredientName}".lowercase()
        val sideKeys = listOf(
            "reis", "kartoffel", "süsskartoffel", "suesskartoffel", "mais", "bohne",
            "stampf", "mash", "milch", "butter", "milk", "bean", "potato"
        )
        val pick = when {
            sideKeys.any { it in n } ->
                sections.firstOrNull { (name, _) ->
                    name.lowercase().let { s ->
                        listOf("stampf", "mash", "beilage", "kartoffel", "mais", "bohne", "potato").any { it in s }
                    }
                }?.first
            else ->
                sections.firstOrNull { (name, _) ->
                    name.lowercase().let { s ->
                        listOf("fleisch", "hähnchen", "huhn", "chicken", "sauce", "honig", "marinade").any { it in s }
                    }
                }?.first
        }
        result[mi] = pick ?: sections.lastOrNull()?.first ?: sections.first().first
    }
    return result
}

internal fun matchToSectionKey(
    m: IngredientMatch,
    sections: List<Pair<String, List<String>>>
): String? {
    if (sections.isEmpty()) return null
    var bestSection: String? = null
    var bestScore = 0
    for ((sectionName, lines) in sections) {
        for (line in lines) {
            val s = scoreMatchToLine(m, line)
            if (s > bestScore) {
                bestScore = s
                bestSection = sectionName
            }
        }
    }
    return if (bestScore >= 40) bestSection else null
}
