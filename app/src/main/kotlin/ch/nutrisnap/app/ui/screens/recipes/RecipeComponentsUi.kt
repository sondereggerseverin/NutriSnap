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
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import java.time.LocalDate

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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Komponenten", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                        OutlinedTextField(
                            value = draft.cookedWeightG,
                            onValueChange = { v ->
                                drafts = drafts.toMutableList().also { it[index] = draft.copy(cookedWeightG = v) }
                            },
                            label = { Text("Kochgewicht (g)") },
                            supportingText = { Text("Gesamtgewicht dieser Komponente nach dem Kochen (ohne Topf)") },
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
                                        "Noch keine Zutaten-Zuordnung. Im Verify-Fenster Zutaten in Beilage/Sauce sortieren und Kochgewicht setzen.",
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Ins Tagebuch", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
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
