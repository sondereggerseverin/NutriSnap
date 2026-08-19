package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MacroField
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.isGramTrackedRecipe
import ch.nutrisnap.app.data.model.isPortionTracked
import ch.nutrisnap.app.data.model.isRecipeEntry
import ch.nutrisnap.app.data.model.isFoodEntry
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriSpacing
import ch.nutrisnap.app.ui.components.MicronutrientTable

@Composable
internal fun EditEntryDialog(
    entry: DiaryEntry,
    onSave: (Float) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    // Gramm-Tracking nur bei echten Rezept-Gramm (≥10); sonst Portionen für Rezept/Manual.
    val isGramTracked = entry.isGramTrackedRecipe
    val isPortionUnit = entry.isPortionTracked && !isGramTracked
    // "baseValue" ist die Menge in der Einheit, in der amountText editiert wird.
    val baseValue = when {
        isGramTracked -> entry.recipeGrams!!
        entry.amountGrams > 0f -> entry.amountGrams
        else -> 1f // Manual ohne Menge = 1 Portion Basis
    }
    var amountText by remember { mutableStateOf(
        when {
            isGramTracked -> entry.recipeGrams!!.toInt().toString()
            entry.amountGrams > 0f && isPortionUnit ->
                if (entry.amountGrams == entry.amountGrams.toInt().toFloat()) entry.amountGrams.toInt().toString()
                else entry.amountGrams.toString()
            isPortionUnit -> "1" // Manual: relative Portionsbasis
            else -> entry.amountGrams.toInt().toString()
        }
    ) }
    val unit = if (isGramTracked) "g" else if (isPortionUnit) "Port." else "g"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                entry.foodName,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Menge ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val amount = parseGramsInput(amountText) ?: 0f
                if (amount > 0f && baseValue > 0f) {
                    val factor = amount / baseValue
                    Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
                        Text(
                            "${(entry.calories * factor).toInt()} kcal",
                            fontWeight = FontWeight.SemiBold,
                            color = MacroColors.calories
                        )
                        Text("P ${(entry.protein * factor).toInt()}g", fontSize = 12.sp, color = MacroColors.protein)
                        Text("K ${(entry.carbs * factor).toInt()}g", fontSize = 12.sp, color = MacroColors.carbs)
                        Text("F ${(entry.fat * factor).toInt()}g", fontSize = 12.sp, color = MacroColors.fat)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val v = parseGramsInput(amountText)
                if (v != null && v > 0f) {
                    // Bei Gramm-Erfassung: eingegebene Gramm → äquivalenter Portionsfaktor
                    // (updateEntryAmount skaliert immer über amountGrams).
                    val toSave = if (isGramTracked && entry.recipeGrams != null && entry.recipeGrams > 0f) {
                        v / entry.recipeGrams * entry.amountGrams
                    } else {
                        v
                    }
                    onSave(toSave)
                }
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                TextButton(onClick = onDelete) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryDetailSheet(
    entry: DiaryEntry,
    foodItem: ch.nutrisnap.app.data.model.FoodItem?,
    recipe: ch.nutrisnap.app.data.model.Recipe? = null,
    onEdit: () -> Unit,
    onEditMacro: (MacroField) -> Unit,
    onDismiss: () -> Unit
) {
    val factor = entry.amountGrams / 100f
    val micros = remember(foodItem, entry.amountGrams) {
        buildMap<String, Float> {
            if (foodItem == null) {
                // Kein verknüpftes FoodItem (z.B. Rezept- oder manueller Eintrag) —
                // auf die am DiaryEntry selbst gespeicherten Werte zurückfallen.
                if (entry.fiber > 0f) put("fiber", entry.fiber)
                if (entry.sugar > 0f) put("sugar", entry.sugar)
                if (entry.saturatedFat > 0f) put("saturatedFat", entry.saturatedFat)
                if (entry.salt > 0f) put("salt", entry.salt)
                if (entry.sodium > 0f) put("sodium", entry.sodium)
            }
            foodItem?.let { f ->
                f.fiber?.let { put("fiber", it * factor) }
                f.sugar?.let { put("sugar", it * factor) }
                f.saturatedFat?.let { put("saturatedFat", it * factor) }
                f.monoFat?.let { put("monoFat", it * factor) }
                f.polyFat?.let { put("polyFat", it * factor) }
                f.transFat?.let { put("transFat", it * factor) }
                f.salt?.let { put("salt", it * factor) }
                f.sodium?.let { put("sodium", it * factor) }
                f.alcohol?.let { put("alcohol", it * factor) }
                f.cholesterol?.let { put("cholesterol", it * factor) }
                f.water?.let { put("water", it * factor) }
                f.vitaminA?.let { put("vitaminA", it * factor) }
                f.vitaminB1?.let { put("vitaminB1", it * factor) }
                f.vitaminB2?.let { put("vitaminB2", it * factor) }
                f.vitaminB3?.let { put("vitaminB3", it * factor) }
                f.vitaminB5?.let { put("vitaminB5", it * factor) }
                f.vitaminB6?.let { put("vitaminB6", it * factor) }
                f.vitaminB7?.let { put("vitaminB7", it * factor) }
                f.vitaminB11?.let { put("vitaminB11", it * factor) }
                f.vitaminB12?.let { put("vitaminB12", it * factor) }
                f.vitaminC?.let { put("vitaminC", it * factor) }
                f.vitaminD?.let { put("vitaminD", it * factor) }
                f.vitaminE?.let { put("vitaminE", it * factor) }
                f.vitaminK?.let { put("vitaminK", it * factor) }
                f.potassium?.let { put("potassium", it * factor) }
                f.calcium?.let { put("calcium", it * factor) }
                f.iron?.let { put("iron", it * factor) }
                f.magnesium?.let { put("magnesium", it * factor) }
                f.zinc?.let { put("zinc", it * factor) }
                f.phosphorus?.let { put("phosphorus", it * factor) }
                f.copper?.let { put("copper", it * factor) }
                f.manganese?.let { put("manganese", it * factor) }
                f.fluoride?.let { put("fluoride", it * factor) }
                f.iodine?.let { put("iodine", it * factor) }
                f.selenium?.let { put("selenium", it * factor) }
                f.chromium?.let { put("chromium", it * factor) }
                f.molybdenum?.let { put("molybdenum", it * factor) }
                f.chloride?.let { put("chloride", it * factor) }
                f.choline?.let { put("choline", it * factor) }
                f.arsenic?.let { put("arsenic", it * factor) }
                f.boron?.let { put("boron", it * factor) }
                f.cobalt?.let { put("cobalt", it * factor) }
                f.rubidium?.let { put("rubidium", it * factor) }
                f.silicon?.let { put("silicon", it * factor) }
                f.sulfur?.let { put("sulfur", it * factor) }
                f.tin?.let { put("tin", it * factor) }
                f.vanadium?.let { put("vanadium", it * factor) }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = NutriSpacing.lg)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = NutriSpacing.lg)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    entry.foodName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Menge bearbeiten")
                }
            }
            val showPortionLabel = entry.isPortionTracked || entry.isRecipeEntry
            Text(
                if (showPortionLabel) recipeAmountLabel(entry)
                else "${entry.amountGrams.toInt()} g",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (entry.isGloballyOverridden) {
                Spacer(Modifier.height(NutriSpacing.xs))
                Text(
                    "Gesamtwert manuell gesetzt – Zutaten wurden nicht verändert",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(NutriSpacing.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EntryMacroItem("Kalorien", "${entry.calories.toInt()}", "kcal", MacroColors.calories, entry.isGloballyOverridden) { onEditMacro(MacroField.CALORIES) }
                EntryMacroItem("Protein", "${entry.protein.toInt()}", "g", MacroColors.protein, entry.isGloballyOverridden) { onEditMacro(MacroField.PROTEIN) }
                EntryMacroItem("Kohlenhy.", "${entry.carbs.toInt()}", "g", MacroColors.carbs, entry.isGloballyOverridden) { onEditMacro(MacroField.CARBS) }
                EntryMacroItem("Fett", "${entry.fat.toInt()}", "g", MacroColors.fat, entry.isGloballyOverridden) { onEditMacro(MacroField.FAT) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Lange drücken zum direkten Anpassen eines Werts",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = NutriSpacing.xs)
                )
                TextButton(onClick = { onEditMacro(MacroField.FIBER) }) {
                    Text("Ballaststoffe", fontSize = 11.sp)
                }
            }
            if (micros.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = NutriSpacing.md))
                MicronutrientTable(micros, ratio = 1f)
            } else if (entry.isFoodEntry) {
                Spacer(Modifier.height(NutriSpacing.md))
                Text(
                    "Keine Mikronährstoffe für diesen Eintrag verfügbar.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Zutaten bei Rezept-Einträgen (skaliert auf getrackte Menge)
            if (entry.isRecipeEntry) {
                HorizontalDivider(Modifier.padding(vertical = NutriSpacing.md))
                EntryRecipeIngredientsSection(entry = entry, recipe = recipe)
            }
        }
    }
}

/**
 * Skaliert eine Zutatenzeile proportional zum getrackten Portionsfaktor.
 * (Gleiche Heuristik wie im Kochmodus: fuehrende Mengenangabe * Faktor.)
 */
private val entryIngredientQtyRegex =
    Regex("""^\s*(\d+/\d+|\d+(?:[.,]\d+)?)(\s*)(.*)$""", RegexOption.DOT_MATCHES_ALL)

internal fun scaleEntryIngredientLine(line: String, factor: Double): String {
    val match = entryIngredientQtyRegex.find(line) ?: return line
    val (numStr, spacer, rest) = match.destructured
    val value = if (numStr.contains("/")) {
        val parts = numStr.split("/")
        val n = parts[0].toDoubleOrNull() ?: return line
        val d = parts[1].toDoubleOrNull() ?: return line
        if (d == 0.0) return line
        n / d
    } else {
        numStr.replace(",", ".").toDoubleOrNull() ?: return line
    }
    val scaled = value * factor
    val rounded = kotlin.math.round(scaled * 100) / 100.0
    val formatted = if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.').replace(".", ",")
    }
    return "$formatted$spacer$rest"
}

@Composable
internal fun EntryRecipeIngredientsSection(
    entry: DiaryEntry,
    recipe: ch.nutrisnap.app.data.model.Recipe?
) {
    var expanded by remember { mutableStateOf(true) }
    val lines = remember(recipe?.id, recipe?.ingredients) {
        recipe?.ingredients
            ?.lineSequence()
            ?.map {
                it.trim()
                    .removePrefix("•").removePrefix("-").removePrefix("*")
                    .removePrefix("·").trim()
            }
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
    }
    // amountGrams = Portionsfaktor relativ zu 1 Rezept-Portion;
    // ingredients-Text bezieht sich auf das ganze Rezept (servings Portionen).
    val scaleFactor = remember(entry.amountGrams, recipe?.servings) {
        val servings = (recipe?.servings ?: 1).coerceAtLeast(1).toDouble()
        (entry.amountGrams.toDouble() / servings).coerceAtLeast(0.01)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                when {
                    recipe == null -> "Rezept nicht mehr in der Bibliothek"
                    entry.isGramTrackedRecipe ->
                        "für ${entry.recipeGrams!!.toInt()} g (×${"%.2f".format(scaleFactor).trimEnd('0').trimEnd('.')})"
                    else ->
                        "für ${formatPortionAmount(entry.amountGrams)} (×${"%.2f".format(scaleFactor).trimEnd('0').trimEnd('.')})"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (expanded) {
        Spacer(Modifier.height(NutriSpacing.sm))
        if (recipe == null) {
            Text(
                "Das Original-Rezept wurde gelöscht – Zutaten nicht verfügbar.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (lines.isEmpty()) {
            Text(
                "Keine Zutaten hinterlegt.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            lines.forEach { line ->
                val display = scaleEntryIngredientLine(line, scaleFactor)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "•",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp, top = 1.dp)
                    )
                    Text(
                        display,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // Makro-Zusammenfassung der getrackten Menge (bereits im Header, hier als Kontext)
            Spacer(Modifier.height(NutriSpacing.sm))
            Text(
                "${entry.calories.toInt()} kcal · P ${entry.protein.toInt()} · K ${entry.carbs.toInt()} · F ${entry.fat.toInt()}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun EntryMacroItem(
    label: String,
    value: String,
    unit: String,
    color: Color,
    isOverridden: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onLongPress != null) {
            Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress)
        } else Modifier
    ) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = color
        )
        Text(unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (isOverridden) ManualOverrideBadge()
    }
}

