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
    onDismiss: () -> Unit
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
    onDismiss: () -> Unit,
    onSearch: (ingredients: String, category: RecipeCategory?, targetKcal: Float?) -> Unit
) {
    var ingredients by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<RecipeCategory?>(null) }
    var kcalText by remember { mutableStateOf("") }
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
                "Zutaten eingeben, die du hast oder nutzen willst — wir filtern deine Rezepte.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = ingredients,
                onValueChange = { ingredients = it },
                label = { Text("Zutaten (z.B. Cottage Cheese, Banane)") },
                placeholder = { Text("Komma oder neue Zeile") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                minLines = 3
            )
            Text("Kategorie (optional)", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("Egal") }
                )
                RecipeCategory.entries.filter { it != RecipeCategory.OTHER }.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = if (category == cat) null else cat },
                        label = { Text("${cat.emoji} ${cat.label}", fontSize = 12.sp) }
                    )
                }
            }
            OutlinedTextField(
                value = kcalText,
                onValueChange = { kcalText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Ziel-kcal pro Portion (optional)") },
                placeholder = { Text("z.B. 500") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                "Beim Öffnen eines Rezepts wird die Portion automatisch auf dieses Ziel skaliert.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Button(
                onClick = {
                    val kcal = kcalText.toFloatOrNull()
                    onSearch(ingredients, category, kcal)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                enabled = ingredients.isNotBlank() || category != null
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rezepte finden")
            }
        }
    }
}
