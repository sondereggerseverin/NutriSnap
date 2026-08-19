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
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * Editor: Komponenten eines Rezepts anlegen/bearbeiten.
 * Pro Komponente nur Name + Kochgewicht (g).
 * Nährwerte werden immer aus den verifizierten Zutaten berechnet – keine Eingabefelder.
 */
internal fun fmtNum(v: Float): String =
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
 * Kanonische Ableitung: Nährwerte einer Komponenten-Gruppe aus IngredientMatch-
 * Zeilen summieren. cookedWeightG bleibt der manuell erfasste Wert (kann nicht
 * aus Zutaten abgeleitet werden).
 */
fun deriveComponentNutrition(
    matches: List<IngredientMatch>,
    componentKey: String,
    cookedWeightG: Float,
    recipeId: Long = 0L,
    name: String = componentKey,
    sortOrder: Int = 0
): RecipeComponent {
    val key = componentKey.trim().lowercase()
    val group = matches.filter { m ->
        if (m.isDeleted) return@filter false
        val g = m.componentGroup?.trim()?.lowercase().orEmpty()
        when {
            g.isNotEmpty() && g == key -> true
            g.isEmpty() && key in setOf("side", "beilage") ->
                defaultComponentGroup("${m.ingredientRaw} ${m.ingredientName}") == "side"
            g.isEmpty() && key in setOf("sauce", "fleisch") ->
                defaultComponentGroup("${m.ingredientRaw} ${m.ingredientName}") == "sauce"
            else -> g == key
        }
    }
    return RecipeComponent(
        recipeId = recipeId,
        name = name,
        cookedWeightG = cookedWeightG,
        totalCalories = group.sumOf { (it.matchedCalories ?: 0f).toDouble() }.toFloat(),
        proteinG = group.sumOf { (it.matchedProtein ?: 0f).toDouble() }.toFloat(),
        carbsG = group.sumOf { (it.matchedCarbs ?: 0f).toDouble() }.toFloat(),
        fatG = group.sumOf { (it.matchedFat ?: 0f).toDouble() }.toFloat(),
        fiberG = group.sumOf { (it.manualFiberG ?: 0f).toDouble() }.toFloat(),
        sortOrder = sortOrder
    )
}

/**
 * Reichert gespeicherte Komponenten mit frisch aus Matches abgeleiteten
 * Nährwerten an. Fallback: proportionale Aufteilung am Rezept-Total, wenn
 * Matches fehlen und die Snapshot-Werte offensichtlich kaputt sind.
 */
fun enrichComponentsFromMatches(
    recipe: Recipe,
    components: List<RecipeComponent>,
    matches: List<IngredientMatch>
): List<RecipeComponent> {
    if (components.isEmpty()) return components
    val usable = matches.filter { !it.isDeleted && ((it.matchedCalories ?: 0f) > 0f || it.amountGrams > 0f) }
    if (usable.isNotEmpty()) {
        return components.mapIndexed { i, c ->
            val key = when {
                c.name.contains("beilage", true) || c.name.contains("side", true) -> "side"
                c.name.contains("sauce", true) || c.name.contains("fleisch", true) -> "sauce"
                else -> c.name.trim().lowercase()
            }
            val derived = deriveComponentNutrition(
                matches = usable,
                componentKey = key,
                cookedWeightG = c.cookedWeightG,
                recipeId = c.recipeId,
                name = c.name,
                sortOrder = c.sortOrder
            )
            // Nur überschreiben wenn Ableitung echte kcal hat
            if (derived.totalCalories > 0f) derived.copy(id = c.id) else c
        }
    }
    // Fallback: proportionale Heilung bei Duplikat-Snapshots
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
