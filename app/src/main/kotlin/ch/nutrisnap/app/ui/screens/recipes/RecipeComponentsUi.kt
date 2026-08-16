package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import ch.nutrisnap.app.data.model.RecipeComponent
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Editor: Komponenten eines Rezepts anlegen/bearbeiten.
 * Pro Komponente nur Name + Kochgewicht (g).
 * Nährwerte werden immer aus den verifizierten Zutaten berechnet – keine Eingabefelder.
 */
private fun fmtNum(v: Float): String =
    if (v <= 0f) "—" else if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

/** Nur Name + Gewicht vom Nutzer; Nährwerte kommen aus [suggested]. */
private data class Draft(
    val id: Long,
    val name: String,
    val cookedWeightG: String
)

private fun componentToDraft(c: RecipeComponent) = Draft(
    id = c.id,
    name = c.name,
    cookedWeightG = c.cookedWeightG.takeIf { it > 0f }?.toInt()?.toString() ?: ""
)

/**
 * Findet für einen Draft die passenden Suggestion-Nährwerte
 * (gleicher Name, sonst Index, sonst proportionaler Fallback).
 */
private fun nutritionFor(
    draft: Draft,
    index: Int,
    suggested: List<RecipeComponent>,
    recipe: Recipe,
    allDrafts: List<Draft>
): RecipeComponent {
    val byName = suggested.firstOrNull {
        it.name.equals(draft.name.trim(), ignoreCase = true) &&
            !it.name.equals("Gesamt", ignoreCase = true)
    }
    // Index nur wenn Suggestion echte Splits hat (nicht einzelnes „Gesamt“)
    val realSplits = suggested.filter {
        it.totalCalories > 0f && !it.name.equals("Gesamt", ignoreCase = true)
    }
    val byIndex = realSplits.getOrNull(index)
    val base = byName ?: byIndex
    if (base != null && base.totalCalories > 0f) return base

    // Kein Fake-Split: ohne echte Zutaten-Matches bleiben Nährwerte leer
    // (Verify → Zutaten zuordnen → Kochgewicht ist der korrekte Weg)
    return RecipeComponent(
        recipeId = recipe.id,
        name = draft.name,
        cookedWeightG = draft.cookedWeightG.replace(',', '.').toFloatOrNull() ?: 0f,
        totalCalories = 0f
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeComponentsEditorSheet(
    recipe: Recipe,
    initial: List<RecipeComponent>,
    suggested: List<RecipeComponent> = emptyList(),
    onSave: (List<RecipeComponent>) -> Unit,
    onDismiss: () -> Unit,
    onRequestSuggest: () -> Unit = {}
) {
    fun toDrafts(list: List<RecipeComponent>): List<Draft> {
        if (list.isEmpty()) {
            return listOf(
                Draft(0, "Beilage", ""),
                Draft(0, "Sauce / Fleisch", "")
            )
        }
        // Duplikate nach Name entfernen (z. B. 2× Sauce)
        val deduped = list
            .groupBy { it.name.trim().lowercase() }
            .map { (_, g) ->
                g.lastOrNull { it.cookedWeightG > 0f } ?: g.last()
            }
        return deduped.map { componentToDraft(it) }
    }

    var drafts by remember(recipe.id) {
        mutableStateOf(toDrafts(if (initial.isNotEmpty()) initial else suggested))
    }
    // initial kommt oft async (collectAsState startet leer) → Gewichte nachziehen
    LaunchedEffect(initial) {
        if (initial.isEmpty()) return@LaunchedEffect
        val fromDb = toDrafts(initial)
        drafts = if (drafts.all { it.cookedWeightG.isBlank() } && drafts.size <= fromDb.size) {
            fromDb
        } else {
            drafts.map { d ->
                if (d.cookedWeightG.isNotBlank()) d
                else {
                    val match = fromDb.firstOrNull {
                        it.name.equals(d.name, ignoreCase = true) && it.cookedWeightG.isNotBlank()
                    }
                    if (match != null) d.copy(cookedWeightG = match.cookedWeightG, id = match.id)
                    else d
                }
            }
        }
    }

    // Suggestion-Namen nachziehen, wenn Drafts noch Default-Namen haben und suggested da ist
    LaunchedEffect(suggested) {
        if (suggested.isEmpty()) return@LaunchedEffect
        if (drafts.size == suggested.size) {
            drafts = drafts.mapIndexed { i, d ->
                val s = suggested[i]
                // Nur Name übernehmen wenn Draft noch leer/Default und suggested einen hat
                if (d.name.isBlank() || d.name == "Beilage" || d.name == "Sauce / Fleisch" ||
                    d.name == "Weitere Komponente"
                ) {
                    d.copy(name = s.name.ifBlank { d.name })
                } else d
            }
        }
    }

    // Beim Öffnen sofort Suggestion anfordern
    LaunchedEffect(recipe.id) {
        onRequestSuggest()
    }

    // Swipe-to-dismiss aus: Scrollen soll das Sheet nicht schliessen (nur X / Abbrechen).
    var allowSheetDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (newValue == SheetValue.Hidden) allowSheetDismiss else true
        }
    )
    fun requestDismiss() {
        allowSheetDismiss = true
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = { requestDismiss() },
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Komponenten", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Schliessen")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Nur Kochgewicht eintragen. Nährwerte kommen automatisch aus den verifizierten Zutaten – keine manuelle Eingabe.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            drafts.forEachIndexed { index, draft ->
                val nut = nutritionFor(draft, index, suggested, recipe, drafts)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Teil ${index + 1}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (drafts.size > 1) {
                                IconButton(
                                    onClick = { drafts = drafts.toMutableList().also { it.removeAt(index) } },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Delete, "Entfernen", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { v ->
                                drafts = drafts.toMutableList().also { it[index] = draft.copy(name = v) }
                            },
                            label = { Text("Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        val weightParsed = draft.cookedWeightG.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                        val densText = if (weightParsed != null && nut.totalCalories > 0f) {
                            val per100 = nut.totalCalories / weightParsed * 100f
                            " · ${fmtNum(per100)} kcal/100g"
                        } else ""
                        OutlinedTextField(
                            value = draft.cookedWeightG,
                            onValueChange = { v ->
                                drafts = drafts.toMutableList().also { it[index] = draft.copy(cookedWeightG = v) }
                            },
                            label = { Text("Kochgewicht (g)") },
                            supportingText = {
                                Text(
                                    if (weightParsed != null)
                                        "Eingetragen: ${weightParsed.toInt()} g$densText"
                                    else
                                        "Noch nicht gesetzt – Gesamtgewicht nach dem Kochen (ohne Topf)"
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    "Nährwerte (berechnet aus Zutaten)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(2.dp))
                                if (nut.totalCalories > 0f) {
                                    Text(
                                        "${fmtNum(nut.totalCalories)} kcal · " +
                                            "P ${fmtNum(nut.proteinG)} g · " +
                                            "KH ${fmtNum(nut.carbsG)} g · " +
                                            "F ${fmtNum(nut.fatG)} g" +
                                            if (nut.fiberG > 0f) " · Ballast ${fmtNum(nut.fiberG)} g" else "",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    Text(
                                        "Noch keine Zutaten-Zuordnung. Über «Komponenten trennen» Zutaten diesem Teil zuordnen und Kochgewicht setzen.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    drafts = drafts + Draft(0, "Weitere Komponente", "")
                }
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Komponente hinzufügen")
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        // Alle Komponenten entfernen → One-Pot
                        onSave(emptyList())
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Entfernen (One-Pot)")
                }
                Button(
                    onClick = {
                        val result = drafts.mapIndexedNotNull { index, d ->
                            val weight = d.cookedWeightG.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                                ?: return@mapIndexedNotNull null
                            val name = d.name.trim().ifBlank { "Komponente" }
                            val nut = nutritionFor(d, index, suggested, recipe, drafts)
                            RecipeComponent(
                                id = d.id,
                                recipeId = recipe.id,
                                name = name,
                                cookedWeightG = weight,
                                totalCalories = nut.totalCalories,
                                proteinG = nut.proteinG,
                                carbsG = nut.carbsG,
                                fatG = nut.fatG,
                                fiberG = nut.fiberG
                            )
                        }
                        onSave(result)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = drafts.any {
                        it.cookedWeightG.replace(',', '.').toFloatOrNull()?.let { w -> w > 0f } == true
                    }
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Speichern")
                }
            }
        }
    }
}

/**
 * Tracking-Sheet für Multi-Komponenten-Rezepte.
 * Standard: getrennte Grammeingaben.
 * Optional: „Gleichmässig aufteilen“ (Meal-Prep) – eine Portionszahl, Ratio bleibt.
 */
/**
 * Heilt offensichtliche Duplikat-Nährwerte (jede Komponente trägt die vollen
 * Rezept-kcal) rein clientseitig für die Anzeige/Skalierung, ohne DB-Schreiben.
 * Persistente Heilung läuft über [RecipesViewModel.healComponentNutrition].
 */
private fun displayHealedComponents(
    recipe: Recipe,
    components: List<RecipeComponent>
): List<RecipeComponent> {
    if (components.size <= 1) return components
    val recipeTotal = recipe.totalCalories ?: 0f
    if (recipeTotal <= 0f) return components
    val duplicated = components.all { c ->
        c.totalCalories > 0f &&
            kotlin.math.abs(c.totalCalories - recipeTotal) / recipeTotal < 0.08f
    }
    val sumTooHigh = components.sumOf { it.totalCalories.toDouble() }.toFloat() > recipeTotal * 1.35f
    if (!duplicated && !sumTooHigh) return components
    val serv = recipe.servings.coerceAtLeast(1).toFloat()
    val sourceProt = (recipe.proteinPerServing ?: 0f) * serv
    val sourceCarbs = (recipe.carbsPerServing ?: 0f) * serv
    val sourceFat = (recipe.fatPerServing ?: 0f) * serv
    val sourceFiber = (recipe.fiberPerServing ?: 0f) * serv
    val wSum = components.sumOf { it.cookedWeightG.toDouble() }.toFloat().coerceAtLeast(1f)
    return components.map { c ->
        val f = if (c.cookedWeightG > 0f) c.cookedWeightG / wSum else 1f / components.size
        c.copy(
            totalCalories = recipeTotal * f,
            proteinG = sourceProt * f,
            carbsG = sourceCarbs * f,
            fatG = sourceFat * f,
            fiberG = sourceFiber * f
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiComponentAddToDiarySheet(
    recipe: Recipe,
    components: List<RecipeComponent>,
    onConfirm: (gramsByComponentId: Map<Long, Float>, meal: MealType, date: LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onFreeze: ((gramsByComponentId: Map<Long, Float>, quantity: Int) -> Unit)? = null
) {
    // Sofortige Korrektur bei kaputten Bestandsdaten (Duplikat-kcal)
    val comps = remember(recipe.id, components) { displayHealedComponents(recipe, components) }
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
        val d = line.trim()
        if (d.length <= 2) return false
        if (d.startsWith("•") || d.startsWith("-") || d.startsWith("*")) return false
        if (d.first().isDigit()) return false
        if (d.first().isWhitespace()) return false
        val lc = d.lowercase()
        // Explizite Abschnitts-Muster (DE/EN)
        if (lc.startsWith("für die ") || lc.startsWith("für den ") || lc.startsWith("für das ") ||
            lc.startsWith("for the ") || lc.startsWith("for ") ||
            lc.endsWith(":") ||
            lc.startsWith("sauce") || lc.startsWith("marinade") || lc.startsWith("dressing") ||
            lc.startsWith("topping") || lc.startsWith("beilage")
        ) return true
        // Wie Rezept-Ansicht: kein Bullet, keine Ziffer – aber nur wenn KEINE Mengenangabe
        val hasAmount = Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|oz)\b""", RegexOption.IGNORE_CASE).containsMatchIn(d)
        if (hasAmount) return false
        // Zeile ohne Menge und ohne typische Zutat-Wörter → Header (z.B. "Charred Zuckermais & Beans")
        val looksLikeIngredient = listOf(
            "hähnchen", "huhn", "chicken", "fleisch", "filet", "mais", "bohne", "bohnen",
            "kartoffel", "zwiebel", "joghurt", "honig", "öl", "butter", "milch", "limette",
            "paprika", "knoblauch", "gewürz", "salz", "pfeffer", "reis", "nudel"
        ).any { it in lc }
        return !looksLikeIngredient
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

/** Ordnet eine Match-Zeile einem Abschnitts-Key zu (Name-Ähnlichkeit). */
internal fun matchToSectionKey(
    m: IngredientMatch,
    sections: List<Pair<String, List<String>>>
): String? {
    if (sections.isEmpty()) return null
    val raw = m.ingredientRaw.lowercase().trim()
        .trimStart('•', '-', '*', ' ')
    val name = m.ingredientName.lowercase().trim()
    val matched = m.matchedFoodName.orEmpty().lowercase().trim()

    fun coreOf(s: String): String =
        s.lowercase()
            .trimStart('•', '-', '*', ' ')
            .replace(Regex("""^\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|oz)?\s*"""), "")
            .trim()

    val rawCore = coreOf(raw)
    val nameCore = coreOf(name).ifBlank { rawCore }

    for ((sectionName, lines) in sections) {
        for (line in lines) {
            val l = coreOf(line)
            if (l.length < 3) continue
            // Direkte Teilstring-Übereinstimmung
            if (rawCore.length >= 3 && (l.contains(rawCore.take(14)) || rawCore.contains(l.take(14)))) {
                return sectionName
            }
            if (nameCore.length >= 3 && (l.contains(nameCore.take(14)) || nameCore.contains(l.take(14)))) {
                return sectionName
            }
            if (matched.length >= 3 && l.contains(matched.take(12))) {
                return sectionName
            }
            // Token-Overlap (mind. ein signifikantes Wort >= 4 Zeichen)
            val tokensL = l.split(Regex("""[\s,;/&]+""")).filter { it.length >= 4 }
            val tokensM = (rawCore + " " + nameCore).split(Regex("""[\s,;/&]+""")).filter { it.length >= 4 }
            if (tokensL.any { t -> tokensM.any { it.startsWith(t.take(5)) || t.startsWith(it.take(5)) } }) {
                return sectionName
            }
        }
    }
    return null
}

private data class SplitPart(
    val key: String,
    val name: String,
    val weightText: String
)

private fun defaultPartKey(
    m: IngredientMatch,
    sections: List<Pair<String, List<String>>> = emptyList()
): String {
    // Abschnitte aus Zutaten-Text haben Vorrang vor alter side/sauce-Zuordnung
    matchToSectionKey(m, sections)?.let { return it }
    val g = m.componentGroup?.trim().orEmpty()
    if (g.isNotBlank() && g != "side" && g != "sauce") return g
    // side/sauce nur behalten wenn keine Abschnitte vorhanden
    if (sections.isEmpty() && (g == "side" || g == "sauce")) return g
    val n = "${m.ingredientRaw} ${m.ingredientName} ${m.matchedFoodName.orEmpty()}".lowercase()
    val sideKeys = listOf(
        "reis", "basmati", "erbse", "erbsen", "peas", "kartoffel", "nudel", "pasta",
        "quinoa", "couscous", "bulgur", "beilage", "hafer", "flocken", "sweet potato",
        "süsskartoffel", "suesskartoffel"
    )
    if (sections.isNotEmpty()) {
        // Fallback: erste/letzte Sektion nach Heuristik
        return if (sideKeys.any { it in n }) {
            sections.firstOrNull { (name, _) ->
                name.lowercase().let { n ->
                    listOf("stampf", "mash", "beilage", "kartoffel", "reis").any { it in n }
                }
            }?.first ?: sections.first().first
        } else {
            sections.firstOrNull { (name, _) ->
                name.lowercase().let { n ->
                    listOf("sauce", "fleisch", "hähnchen", "huhn", "chicken", "honig").any { it in n }
                }
            }?.first ?: sections.last().first
        }
    }
    return if (sideKeys.any { it in n }) "side" else "sauce"
}

private fun displayNameForKey(key: String): String = when (key) {
    "side" -> "Beilage"
    "sauce" -> "Sauce / Fleisch"
    else -> key
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComponentSplitSheet(
    recipe: Recipe,
    matches: List<IngredientMatch>,
    initialComponents: List<RecipeComponent>,
    onSave: (components: List<RecipeComponent>, matches: List<IngredientMatch>) -> Unit,
    onDismiss: () -> Unit
) {
    // Abschnitte aus Zutaten-Text (z.B. "Für die Sauce", "Charred Zuckermais & Beans")
    val ingredientSections = remember(recipe.id, recipe.ingredients) {
        parseIngredientSections(recipe.ingredients)
    }

    // Teile: Zutaten-Abschnitte haben Vorrang (wie im Rezept), sonst gespeicherte
    // Komponenten, sonst Beilage+Sauce. Gewichte aus initialComponents per Name übernehmen.
    var parts by remember {
        mutableStateOf(
            run {
                fun weightFor(name: String): String {
                    val match = initialComponents.firstOrNull {
                        it.name.equals(name, true) ||
                            (name.contains("sauce", true) && it.name.contains("sauce", true)) ||
                            (name.contains("beilage", true) && it.name.contains("beilage", true))
                    }
                    return match?.cookedWeightG?.takeIf { it > 0f }?.toInt()?.toString() ?: ""
                }
                when {
                    ingredientSections.size >= 2 -> ingredientSections.map { (name, _) ->
                        SplitPart(key = name, name = name, weightText = weightFor(name))
                    }
                    initialComponents.isNotEmpty() -> initialComponents.mapIndexed { i, c ->
                        val key = when {
                            c.name.contains("beilage", true) -> "side"
                            c.name.contains("sauce", true) || c.name.contains("fleisch", true) -> "sauce"
                            else -> c.name.ifBlank { "teil$i" }
                        }
                        SplitPart(
                            key = key,
                            name = c.name.ifBlank { displayNameForKey(key) },
                            weightText = c.cookedWeightG.takeIf { it > 0f }?.toInt()?.toString() ?: ""
                        )
                    }
                    else -> listOf(
                        SplitPart("side", "Beilage", ""),
                        SplitPart("sauce", "Sauce / Fleisch", "")
                    )
                }
            }
        )
    }

    var groups by remember {
        mutableStateOf(
            matches.associate { it.ingredientRaw to defaultPartKey(it, ingredientSections) }
        )
    }
    LaunchedEffect(matches, ingredientSections) {
        if (matches.isEmpty()) return@LaunchedEffect
        groups = matches.associate { it.ingredientRaw to defaultPartKey(it, ingredientSections) }
    }

    fun sumFor(key: String): Triple<Float, Float, Float> {
        val list = matches.filter { groups[it.ingredientRaw] == key }
        val kcal = list.sumOf { (it.matchedCalories ?: 0f).toDouble() }.toFloat()
        val prot = list.sumOf { (it.matchedProtein ?: 0f).toDouble() }.toFloat()
        val carbs = list.sumOf { (it.matchedCarbs ?: 0f).toDouble() }.toFloat()
        val fat = list.sumOf { (it.matchedFat ?: 0f).toDouble() }.toFloat()
        return Triple(kcal, prot, carbs) // fat separately if needed
    }
    fun fatFor(key: String): Float =
        matches.filter { groups[it.ingredientRaw] == key }
            .sumOf { (it.matchedFat ?: 0f).toDouble() }.toFloat()

    // Swipe-to-dismiss aus: Scrollen soll das Sheet nicht schliessen (nur X / Abbrechen).
    var allowSheetDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (newValue == SheetValue.Hidden) allowSheetDismiss else true
        }
    )
    fun requestDismiss() {
        allowSheetDismiss = true
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = { requestDismiss() },
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Komponenten trennen",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Schliessen")
                }
            }
            Text(
                recipe.displayTitle(),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (matches.isEmpty()) {
                Text(
                    "Zuerst «Verify» ausführen – ohne verifizierte Zutaten keine Trennung möglich.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { requestDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Schliessen") }
                return@Column
            }

            // Status-Banner für bereits gesetzte Gewichte
            val setParts = parts.filter {
                it.weightText.replace(',', '.').toFloatOrNull()?.let { w -> w > 0f } == true
            }
            if (setParts.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Bereits getrennt: " + setParts.joinToString(" · ") {
                            "${it.name} ${it.weightText} g"
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            parts.forEachIndexed { index, part ->
                val (kcal, prot, carbs) = sumFor(part.key)
                val fat = fatFor(part.key)
                val partMatches = matches.filter { groups[it.ingredientRaw] == part.key }

                Text(part.name, fontWeight = FontWeight.SemiBold)
                Text(
                    if (kcal > 0f) "${fmtNum(kcal)} kcal aus Zutaten"
                    else "Keine Zutaten zugeordnet",
                    fontSize = 12.sp,
                    color = if (kcal > 0f) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = part.weightText,
                    onValueChange = { v ->
                        parts = parts.toMutableList().also {
                            it[index] = part.copy(weightText = v)
                        }
                    },
                    label = { Text("Kochgewicht ${part.name} (g)") },
                    placeholder = { Text("Nach dem Kochen, ohne Topf") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                if (part.weightText.replace(',', '.').toFloatOrNull()?.let { it > 0f } == true && kcal > 0f) {
                    val w = part.weightText.replace(',', '.').toFloatOrNull() ?: 0f
                    if (w > 0f) {
                        Text(
                            "Eingetragen: ${w.toInt()} g · ${fmtNum(kcal / w * 100f)} kcal/100g",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                partMatches.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.ingredientRaw, fontSize = 13.sp)
                            Text(
                                "${fmtNum(m.matchedCalories ?: 0f)} kcal",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Zum nächsten Teil verschieben
                        val nextIdx = (index + 1) % parts.size
                        if (parts.size > 1) {
                            AssistChip(
                                onClick = {
                                    groups = groups + (m.ingredientRaw to parts[nextIdx].key)
                                },
                                label = {
                                    Text("→ ${parts[nextIdx].name}", fontSize = 11.sp)
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Zutaten ohne Zuordnung (falls key fehlt)
            val partKeys = parts.map { it.key }.toSet()
            val orphan = matches.filter { groups[it.ingredientRaw] !in partKeys }
            if (orphan.isNotEmpty()) {
                Text("Nicht zugeordnet", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(
                    "Tippe auf einen Teil, um zuzuordnen.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                orphan.forEach { m ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(m.ingredientRaw, fontSize = 13.sp)
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            parts.forEach { p ->
                                AssistChip(
                                    onClick = { groups = groups + (m.ingredientRaw to p.key) },
                                    label = { Text(p.name.take(18), fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            TextButton(
                onClick = {
                    val n = parts.size + 1
                    val key = "teil$n"
                    parts = parts + SplitPart(key, "Teil $n", "")
                }
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Komponente hinzufügen")
            }

            if (parts.size > 2) {
                TextButton(
                    onClick = {
                        val lastKey = parts.last().key
                        // Zutaten der letzten Gruppe in die vorletzte schieben
                        val prevKey = parts[parts.size - 2].key
                        groups = groups.mapValues { (_, v) -> if (v == lastKey) prevKey else v }
                        parts = parts.dropLast(1)
                    }
                ) {
                    Text("Letzte Komponente entfernen")
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val comps = parts.mapIndexedNotNull { i, part ->
                        val w = part.weightText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                            ?: return@mapIndexedNotNull null
                        val (kcal, prot, carbs) = sumFor(part.key)
                        val fat = fatFor(part.key)
                        RecipeComponent(
                            recipeId = recipe.id,
                            name = part.name.ifBlank { displayNameForKey(part.key) },
                            cookedWeightG = w,
                            totalCalories = kcal,
                            proteinG = prot,
                            carbsG = carbs,
                            fatG = fat,
                            sortOrder = i
                        )
                    }
                    val updatedMatches = matches.map { m ->
                        m.copy(componentGroup = groups[m.ingredientRaw] ?: parts.firstOrNull()?.key ?: "sauce")
                    }
                    if (comps.isNotEmpty()) onSave(comps, updatedMatches)
                    requestDismiss()
                },
                enabled = parts.any {
                    it.weightText.replace(',', '.').toFloatOrNull()?.let { w -> w > 0f } == true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Trennung speichern")
            }
            TextButton(onClick = { requestDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
        }
    }
}
