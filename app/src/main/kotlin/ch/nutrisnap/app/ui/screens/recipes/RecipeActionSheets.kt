package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory

internal enum class DiaryQuantityUnit { SERVING, GRAM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToDiarySheet(
    recipe: Recipe,
    gramsPerServing: Float? = null,
    yieldTotalG: Float? = null,
    isCookedWeight: Boolean = false,
    onConfirm: (servings: Float, gramsIfGramMode: Float?, meal: MealType, date: java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit,
    onFreeze: ((grams: Float, quantity: Int) -> Unit)? = null
) {
    var unit by remember { mutableStateOf(if (gramsPerServing != null) DiaryQuantityUnit.GRAM else DiaryQuantityUnit.SERVING) }
    var servingsText by remember { mutableStateOf("1") }
    var gramsText by remember {
        mutableStateOf(
            gramsPerServing?.toInt()?.toString()
                ?: yieldTotalG?.let { (it / recipe.servings.coerceAtLeast(1)).toInt().toString() }
                ?: ""
        )
    }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    var selectedDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var freezeQtyText by remember { mutableStateOf("1") }

    // Immer in Portionen umrechnen, egal welche Einheit der Nutzer eingibt — die
    // Datenschicht (addRecipeAsMeal) erwartet weiterhin einen Portionsfaktor.
    val servings = when (unit) {
        DiaryQuantityUnit.SERVING -> servingsText.toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f
        DiaryQuantityUnit.GRAM -> {
            val grams = gramsText.toFloatOrNull()?.coerceAtLeast(1f) ?: (gramsPerServing ?: 1f)
            if (gramsPerServing != null && gramsPerServing > 0f) grams / gramsPerServing else 1f
        }
    }
    val calsPerServ = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val estCals = calsPerServ?.let { it * servings }

    val diarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = diarySheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Ins Tagebuch", fontWeight=FontWeight.Bold, fontSize=18.sp)
            Spacer(Modifier.height(4.dp))
            Text(recipe.displayTitle(), fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            if (yieldTotalG != null && yieldTotalG > 0f) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isCookedWeight)
                        "Gesamt (nach Kochen): ${yieldTotalG.toInt()} g · ${gramsPerServing?.toInt() ?: "–"} g/Portion"
                    else
                        "Σ Zutaten (roh): ${yieldTotalG.toInt()} g · ${gramsPerServing?.toInt() ?: "–"} g/Portion",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Tipp: Nudeln/Reis – „Gewicht nach Kochen“ im Rezept setzen für genaues Tracking.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (unit == DiaryQuantityUnit.SERVING) servingsText else gramsText,
                    onValueChange = { if (unit == DiaryQuantityUnit.SERVING) servingsText=it else gramsText=it },
                    label = { Text("Menge") },
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal), modifier=Modifier.weight(1f), singleLine=true)
                var unitExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={unitExpanded=true}, modifier=Modifier.height(56.dp)) {
                        Text(if (unit == DiaryQuantityUnit.SERVING) "Portion" else "Gramm"); Icon(Icons.Default.ArrowDropDown,null)
                    }
                    DropdownMenu(expanded=unitExpanded, onDismissRequest={unitExpanded=false}) {
                        DropdownMenuItem(text={Text("Portion")}, onClick={unit=DiaryQuantityUnit.SERVING;unitExpanded=false})
                        DropdownMenuItem(
                            text={Text("Gramm")},
                            enabled = gramsPerServing != null,
                            onClick={unit=DiaryQuantityUnit.GRAM;unitExpanded=false}
                        )
                    }
                }
            }
            if (gramsPerServing == null) {
                Spacer(Modifier.height(4.dp))
                Text("Gramm-Eingabe nicht verfügbar — Nährwerte noch nicht analysiert.",
                    fontSize=11.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                Text("Mahlzeit:", fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
                var mealExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={mealExpanded=true}) {
                        Text(selectedMeal.label()); Icon(Icons.Default.ArrowDropDown,null)
                    }
                    DropdownMenu(expanded=mealExpanded, onDismissRequest={mealExpanded=false}) {
                        MealType.values().forEach { meal ->
                            DropdownMenuItem(text={Text(meal.label())}, onClick={selectedMeal=meal;mealExpanded=false})
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Tag:", fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val today = java.time.LocalDate.now()
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
            estCals?.let {
                Spacer(Modifier.height(8.dp))
                Text("≈ ${it.toInt()} kcal", fontWeight=FontWeight.SemiBold,
                    color=MaterialTheme.colorScheme.primary, fontSize=15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onDismiss, Modifier.weight(1f)) { Text("Abbrechen") }
                Button(onClick={
                    val gramsIfGramMode = if (unit == DiaryQuantityUnit.GRAM)
                        gramsText.replace(',', '.').toFloatOrNull()
                    else null
                    onConfirm(servings, gramsIfGramMode, selectedMeal, selectedDate)
                }, Modifier.weight(1f), enabled=servings>0) {
                    Icon(Icons.Default.Check,null,Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Hinzufügen")
                }
            }
            if (onFreeze != null) {
                val freezeGrams: Float? = when (unit) {
                    DiaryQuantityUnit.GRAM ->
                        gramsText.replace(',', '.').toFloatOrNull()?.takeIf { it >= 1f }
                    DiaryQuantityUnit.SERVING -> {
                        val per = gramsPerServing
                            ?: yieldTotalG?.div(recipe.servings.coerceAtLeast(1).toFloat())
                        per?.times(servings)?.takeIf { it >= 1f }
                    }
                }
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
                            val g = freezeGrams ?: return@OutlinedButton
                            val q = freezeQtyText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                            onFreeze(g, q)
                        },
                        enabled = freezeGrams != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.AcUnit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Einfrieren")
                    }
                }
                if (freezeGrams == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Einfrieren braucht ein Gewicht (Gramm oder analysierte Portionsgröße).",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

internal fun MealType.label() = when(this) {
    MealType.BREAKFAST -> "Frühstück"; MealType.LUNCH -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"; MealType.SNACK -> "Snack"
}

@Composable
/**
 * Nach dem Tracken: Sterne + optionale Notiz „Nächstes Mal“.
 * Speichert direkt am Rezept ([onSave]), nicht nur in DataStore.
 */
internal fun RecipeQuickRatingDialog(
    recipe: Recipe,
    onSave: (stars: Int, nextTimeNote: String) -> Unit,
    onSkip: () -> Unit
) {
    var stars by remember(recipe.id) { mutableStateOf(recipe.cookRating.coerceIn(0, 5)) }
    var nextTime by remember(recipe.id) { mutableStateOf(recipe.nextTimeNote) }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Wie war’s?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(recipe.displayTitle(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recipe.timesCooked > 0) {
                    Text(
                        "Bereits ${recipe.timesCooked}× getrackt",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("Sterne", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 1..5) {
                        IconButton(onClick = { stars = i }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "$i Sterne",
                                tint = if (i <= stars) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
                Text("Nächstes Mal", fontSize = 12.sp)
                OutlinedTextField(
                    value = nextTime,
                    onValueChange = { nextTime = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("z.B. Himbeeren mit Erythrit süssen", fontSize = 13.sp) },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = stars > 0 || nextTime.isNotBlank(),
                onClick = {
                    onSave(stars, nextTime.trim())
                }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Überspringen") }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookWithWhatIHaveSheet(
    recipes: List<Recipe>,
    remaining: ch.nutrisnap.app.domain.MacroRemaining? = null,
    onDismiss: () -> Unit,
    onPickRecipe: (Recipe) -> Unit,
    onSearch: (ingredients: String, category: RecipeCategory?, targetKcal: Float?) -> Unit
) {
    var mode by remember { mutableStateOf(ch.nutrisnap.app.domain.CookSuggestMode.SMART) }
    var category by remember { mutableStateOf<RecipeCategory?>(null) }
    var seed by remember { mutableStateOf(System.currentTimeMillis()) }
    var showIngredientSearch by remember { mutableStateOf(false) }
    var ingredients by remember { mutableStateOf("") }
    var kcalText by remember { mutableStateOf("") }

    val suggestions = remember(recipes, remaining, mode, category, seed) {
        ch.nutrisnap.app.domain.RecipeCookSuggester.suggest(
            recipes = recipes,
            remaining = remaining,
            mode = mode,
            preferredCategory = category,
            maxResults = 5,
            seed = seed
        )
    }

    val cookSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = cookSheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Was koche ich?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                if (remaining != null && remaining.hasMeaningfulGap)
                    "Smart-Vorschläge aus ${recipes.size} Rezepten · ${remaining.kcal.toInt()} kcal / P ${remaining.protein.toInt()}g offen"
                else
                    "Smart-Vorschläge aus ${recipes.size} Rezepten",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            // Modus-Chips
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val modes = listOf(
                    ch.nutrisnap.app.domain.CookSuggestMode.SMART to "✨ Smart",
                    ch.nutrisnap.app.domain.CookSuggestMode.NEVER_COOKED to "🆕 Neu",
                    ch.nutrisnap.app.domain.CookSuggestMode.QUICK to "⏱️ Schnell",
                    ch.nutrisnap.app.domain.CookSuggestMode.HIGH_PROTEIN to "💪 Protein",
                    ch.nutrisnap.app.domain.CookSuggestMode.FAVORITES to "★ Favoriten",
                    ch.nutrisnap.app.domain.CookSuggestMode.LONG_AGO to "🔁 Lange her"
                )
                modes.forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m; seed = System.currentTimeMillis() },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            // Kategorie optional
            Text("Kategorie", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null; seed = System.currentTimeMillis() },
                    label = { Text("Egal") }
                )
                RecipeCategory.entries.filter { it != RecipeCategory.OTHER }.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = {
                            category = if (category == cat) null else cat
                            seed = System.currentTimeMillis()
                        },
                        label = { Text("${cat.emoji} ${cat.label}", fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (suggestions.isEmpty()) {
                Text(
                    "Keine Treffer für diesen Filter. Anderen Modus wählen oder Zutaten suchen.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                suggestions.forEach { s ->
                    val r = s.recipe
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        onClick = { onPickRecipe(r) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    r.displayTitle(),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2
                                )
                                if (r.isFavorite) {
                                    Text("★", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            val macros = buildString {
                                r.totalCalories?.let {
                                    val per = it / r.servings.coerceAtLeast(1)
                                    append("${per.toInt()} kcal")
                                }
                                r.proteinPerServing?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("P ${it.toInt()}g")
                                }
                                r.prepTimeMinutes?.takeIf { it > 0 }?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("${it} min")
                                }
                            }
                            if (macros.isNotEmpty()) {
                                Text(
                                    macros,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            if (s.reasons.isNotEmpty()) {
                                Text(
                                    s.reasons.joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilledTonalButton(
                                    onClick = { onPickRecipe(r) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Öffnen", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { seed = System.currentTimeMillis() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                enabled = recipes.isNotEmpty()
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Andere Vorschläge")
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            TextButton(
                onClick = { showIngredientSearch = !showIngredientSearch },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (showIngredientSearch) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (showIngredientSearch) "Zutaten-Suche ausblenden" else "Nach Zutaten filtern…")
            }

            if (showIngredientSearch) {
                OutlinedTextField(
                    value = ingredients,
                    onValueChange = { ingredients = it },
                    label = { Text("Zutaten (z.B. Cottage Cheese, Banane)") },
                    placeholder = { Text("Komma oder neue Zeile") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    minLines = 2
                )
                OutlinedTextField(
                    value = kcalText,
                    onValueChange = { kcalText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Ziel-kcal pro Portion (optional)") },
                    placeholder = { Text("z.B. 500") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(
                    onClick = {
                        val kcal = kcalText.toFloatOrNull()
                        onSearch(ingredients, category, kcal)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    enabled = ingredients.isNotBlank() || category != null
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("In Liste filtern")
                }
            }
        }
    }
}
