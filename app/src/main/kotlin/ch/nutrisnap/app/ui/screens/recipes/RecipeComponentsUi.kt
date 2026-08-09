package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
 * Pro Komponente: Name, Kochgewicht (g), Gesamtnährwerte des Batches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeComponentsEditorSheet(
    recipe: Recipe,
    initial: List<RecipeComponent>,
    onSave: (List<RecipeComponent>) -> Unit,
    onDismiss: () -> Unit
) {
    data class Draft(
        val id: Long,
        var name: String,
        var cookedWeightG: String,
        var totalCalories: String,
        var proteinG: String,
        var carbsG: String,
        var fatG: String,
        var fiberG: String
    )

    var drafts by remember(recipe.id, initial) {
        mutableStateOf(
            if (initial.isEmpty()) {
                listOf(
                    Draft(0, "Beilage", "", "", "", "", "", ""),
                    Draft(0, "Sauce / Fleisch", "", "", "", "", "", "")
                )
            } else {
                initial.map {
                    Draft(
                        id = it.id,
                        name = it.name,
                        cookedWeightG = it.cookedWeightG.takeIf { w -> w > 0f }?.toInt()?.toString() ?: "",
                        totalCalories = it.totalCalories.takeIf { c -> c > 0f }?.toInt()?.toString() ?: "",
                        proteinG = it.proteinG.takeIf { p -> p > 0f }?.let { p ->
                            if (p == p.toInt().toFloat()) p.toInt().toString() else "%.1f".format(p)
                        } ?: "",
                        carbsG = it.carbsG.takeIf { p -> p > 0f }?.let { p ->
                            if (p == p.toInt().toFloat()) p.toInt().toString() else "%.1f".format(p)
                        } ?: "",
                        fatG = it.fatG.takeIf { p -> p > 0f }?.let { p ->
                            if (p == p.toInt().toFloat()) p.toInt().toString() else "%.1f".format(p)
                        } ?: "",
                        fiberG = it.fiberG.takeIf { p -> p > 0f }?.let { p ->
                            if (p == p.toInt().toFloat()) p.toInt().toString() else "%.1f".format(p)
                        } ?: ""
                    )
                }
            }
        )
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
                "Beilage und Sauce/Fleisch getrennt. Pro Teil einmal das Kochgewicht (nach dem Garen) und die Batch-Nährwerte eintragen. Beim Tracken abwiegen – die App rechnet um.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            drafts.forEachIndexed { index, draft ->
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
                            supportingText = { Text("Gesamtgewicht dieser Komponente nach dem Kochen") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("Nährwerte des Batches", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = draft.totalCalories,
                                onValueChange = { v ->
                                    drafts = drafts.toMutableList().also { it[index] = draft.copy(totalCalories = v) }
                                },
                                label = { Text("kcal") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.proteinG,
                                onValueChange = { v ->
                                    drafts = drafts.toMutableList().also { it[index] = draft.copy(proteinG = v) }
                                },
                                label = { Text("Protein g") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = draft.carbsG,
                                onValueChange = { v ->
                                    drafts = drafts.toMutableList().also { it[index] = draft.copy(carbsG = v) }
                                },
                                label = { Text("KH g") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.fatG,
                                onValueChange = { v ->
                                    drafts = drafts.toMutableList().also { it[index] = draft.copy(fatG = v) }
                                },
                                label = { Text("Fett g") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = draft.fiberG,
                                onValueChange = { v ->
                                    drafts = drafts.toMutableList().also { it[index] = draft.copy(fiberG = v) }
                                },
                                label = { Text("Ballast g") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    drafts = drafts + Draft(0, "Weitere Komponente", "", "", "", "", "", "")
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
                        val result = drafts.mapNotNull { d ->
                            val weight = d.cookedWeightG.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                                ?: return@mapNotNull null
                            val name = d.name.trim().ifBlank { "Komponente" }
                            RecipeComponent(
                                id = d.id,
                                recipeId = recipe.id,
                                name = name,
                                cookedWeightG = weight,
                                totalCalories = d.totalCalories.replace(',', '.').toFloatOrNull() ?: 0f,
                                proteinG = d.proteinG.replace(',', '.').toFloatOrNull() ?: 0f,
                                carbsG = d.carbsG.replace(',', '.').toFloatOrNull() ?: 0f,
                                fatG = d.fatG.replace(',', '.').toFloatOrNull() ?: 0f,
                                fiberG = d.fiberG.replace(',', '.').toFloatOrNull() ?: 0f
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiComponentAddToDiarySheet(
    recipe: Recipe,
    components: List<RecipeComponent>,
    onConfirm: (gramsByComponentId: Map<Long, Float>, meal: MealType, date: LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var equalMode by remember { mutableStateOf(false) }
    var portionsText by remember { mutableStateOf(recipe.servings.coerceAtLeast(1).toString()) }
    var gramsTexts by remember(components) {
        mutableStateOf(
            components.associate { c ->
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

    val portions = portionsText.toIntOrNull()?.coerceAtLeast(1) ?: 1

    // Im Equal-Mode: Gramm pro Komponente aus Portionszahl ableiten
    val effectiveGrams: Map<Long, Float> = if (equalMode) {
        components.associate { c ->
            val g = if (c.cookedWeightG > 0f) c.cookedWeightG / portions else 0f
            c.id to g
        }
    } else {
        components.associate { c ->
            val g = gramsTexts[c.id]?.replace(',', '.')?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
            c.id to g
        }
    }

    val totalCals = components.sumOf { c ->
        c.scaledTo(effectiveGrams[c.id] ?: 0f).calories.toDouble()
    }.toFloat()
    val totalProtein = components.sumOf { c ->
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
                components.forEach { c ->
                    val g = effectiveGrams[c.id] ?: 0f
                    Text(
                        "• ${c.name}: ${g.toInt()} g  (von ${c.cookedWeightG.toInt()} g)",
                        fontSize = 13.sp
                    )
                }
            } else {
                components.forEach { c ->
                    OutlinedTextField(
                        value = gramsTexts[c.id] ?: "",
                        onValueChange = { v ->
                            gramsTexts = gramsTexts.toMutableMap().also { it[c.id] = v }
                        },
                        label = { Text("${c.name} (g)") },
                        supportingText = {
                            Text("Batch: ${c.cookedWeightG.toInt()} g · ${c.totalCalories.toInt()} kcal gesamt")
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
        }
    }
}
