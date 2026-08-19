package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.CookingMethod
import ch.nutrisnap.app.domain.DayPlan
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.PlannedMeal
import ch.nutrisnap.app.domain.RecipeIngredient
import ch.nutrisnap.app.domain.WorkoutTiming
import kotlin.math.roundToInt

// ── Modus-Eingaben ─────────────────────────────────────────────────────────────

private val FREITEXT_QUICK_PROMPTS = listOf(
    "Schnell (15 Min)" to "Schnelles Gericht, fertig in 15 Minuten",
    "Proteinreich" to "Proteinreiches Gericht",
    "Vegan" to "Veganes Gericht",
    "Low Carb" to "Kohlenhydratarmes Gericht",
    "Resteverwertung" to "Rezept zur Resteverwertung"
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FreitextInput(
    input: String,
    onInputChange: (String) -> Unit,
    isLoading: Boolean,
    onGenerate: () -> Unit
) {
    Column {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Was möchtest du essen?") },
            placeholder = { Text("z.B. Schnelles Hähnchen mit Reis") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 4,
            shape = RoundedCornerShape(12.dp)
        )
        // Vorschläge nur solange das Feld leer ist - danach nicht mehr im Weg.
        if (input.isBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("Ideen zum Start", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FREITEXT_QUICK_PROMPTS.forEach { (label, prompt) ->
                    SuggestionChip(
                        onClick = { onInputChange(prompt) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && input.isNotBlank()
        ) {
            GenerateButtonContent(isLoading, "Generiere Rezept…", "Rezept generieren", Icons.Default.AutoAwesome)
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ZutatenInput(
    chips: List<String>,
    ingredientInput: String,
    onIngredientInputChange: (String) -> Unit,
    onAddChip: () -> Unit,
    onRemoveChip: (String) -> Unit,
    onOpenCamera: () -> Unit,
    isScanningFridge: Boolean,
    isLoading: Boolean,
    onGenerate: () -> Unit
) {
    Column {
        Text("Was hast du zuhause?", fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ingredientInput,
                onValueChange = onIngredientInputChange,
                placeholder = { Text("z.B. Eier") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onAddChip, enabled = ingredientInput.isNotBlank()) {
                Icon(Icons.Default.Add, "Zutat hinzufügen")
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onOpenCamera, enabled = !isScanningFridge) {
                if (isScanningFridge) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.PhotoCamera, "Kühlschrank fotografieren")
            }
        }
        if (isScanningFridge) {
            Spacer(Modifier.height(6.dp))
            Text("Analysiere Foto…", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { chip ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveChip(chip) },
                        label = { Text(chip, fontSize = 13.sp) },
                        trailingIcon = { Icon(Icons.Default.Close, "Entfernen", Modifier.size(14.dp)) }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && chips.isNotEmpty()
        ) {
            GenerateButtonContent(isLoading, "Zaubere Rezept…", "Rezept aus Zutaten zaubern", Icons.Default.AutoAwesome)
        }
        if (chips.isEmpty() && !isScanningFridge) {
            Spacer(Modifier.height(4.dp))
            Text("Zutaten eintippen oder Kühlschrank fotografieren", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FillUpInput(
    budget: FillUpBudget,
    mealLabel: String,
    onMealLabelChange: (String) -> Unit,
    isLoading: Boolean,
    onGenerate: () -> Unit
) {
    Column {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text("Heute noch übrig", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BudgetStat("${budget.calories.roundToInt()}", "kcal")
                    BudgetStat("${budget.protein.roundToInt()}g", "Protein")
                    BudgetStat("${budget.carbs.roundToInt()}g", "Carbs")
                    BudgetStat("${budget.fat.roundToInt()}g", "Fett")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Für welche Mahlzeit?", fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Mittagessen", "Abendessen", "Snack").forEach { label ->
                FilterChip(
                    selected = mealLabel == label,
                    onClick = { onMealLabelChange(label) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && budget.calories > 0f
        ) {
            GenerateButtonContent(isLoading, "Fülle auf…", "Mit Restbudget auffüllen", Icons.Default.LocalFireDepartment)
        }
        if (budget.calories <= 0f) {
            Spacer(Modifier.height(4.dp))
            Text("Kein Kalorienbudget mehr übrig für heute", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun BudgetStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun ZufallInput(
    isLoading: Boolean,
    onGenerate: () -> Unit
) {
    Column {
        Text("Lass dich überraschen – ein zufälliges, alltagstaugliches Rezept.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            GenerateButtonContent(isLoading, "Würfle Rezept…", "Zufallsrezept", Icons.Default.Casino)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TagesplanInput(
    state: RecipeGenUiState,
    vm: RecipeGeneratorViewModel,
    isLoading: Boolean
) {
    Column {
        Text("Tagesziele", fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.dayPlanTargetCalories,
                onValueChange = vm::setDayPlanCalories,
                label = { Text("kcal") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = state.dayPlanTargetProtein,
                onValueChange = vm::setDayPlanProtein,
                label = { Text("Protein g") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = state.dayPlanTargetFiber,
                onValueChange = vm::setDayPlanFiber,
                label = { Text("Ballaststoffe g") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Frühstück einplanen", fontSize = 14.sp)
            Switch(checked = state.dayPlanIncludeBreakfast, onCheckedChange = vm::setDayPlanIncludeBreakfast)
        }

        Spacer(Modifier.height(10.dp))
        Text("Anzahl Mahlzeiten", fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(
                onClick = { vm.setDayPlanMealCount(state.dayPlanMealCount - 1) },
                enabled = state.dayPlanMealCount > 2
            ) { Icon(Icons.Default.Remove, null) }
            Text("${state.dayPlanMealCount}", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.width(28.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilledTonalIconButton(
                onClick = { vm.setDayPlanMealCount(state.dayPlanMealCount + 1) },
                enabled = state.dayPlanMealCount < 6
            ) { Icon(Icons.Default.Add, null) }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("High Volume Eating", fontSize = 14.sp)
            Switch(checked = state.dayPlanHighVolume, onCheckedChange = vm::setDayPlanHighVolume)
        }

        Spacer(Modifier.height(10.dp))
        Text("Workout-Timing", fontWeight = FontWeight.Medium, fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        val workoutOptions = listOf(
            WorkoutTiming.NONE to "Keins",
            WorkoutTiming.PRE to "Vor Training",
            WorkoutTiming.POST to "Nach Training",
            WorkoutTiming.BOTH to "Beides"
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            workoutOptions.forEach { (timing, label) ->
                FilterChip(
                    selected = state.dayPlanWorkoutTiming == timing,
                    onClick = { vm.setDayPlanWorkoutTiming(timing) },
                    label = { Text(label, fontSize = 12.sp) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.dayPlanMustUseIngredients,
            onValueChange = vm::setDayPlanMustUseIngredients,
            label = { Text("Zutaten, die vorkommen sollen") },
            placeholder = { Text("z.B. Hähnchen, Reis, Broccoli") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.dayPlanExtraNotes,
            onValueChange = vm::setDayPlanExtraNotes,
            label = { Text("Zusätzliche Wünsche") },
            placeholder = { Text("z.B. vegetarisch, wenig Aufwand") },
            minLines = 2, maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        val canGenerate = state.dayPlanTargetCalories.toFloatOrNull() != null &&
            state.dayPlanTargetProtein.toFloatOrNull() != null
        Button(
            onClick = { vm.generateDayPlan() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading && canGenerate
        ) {
            GenerateButtonContent(isLoading, "Erstelle Tagesplan…", "Tagesplan generieren", Icons.Default.CalendarToday)
        }
        if (!canGenerate) {
            Spacer(Modifier.height(4.dp))
            Text("Kalorien- und Proteinziel angeben", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun DayPlanResultCard(
    plan: DayPlan,
    savedMealIndices: Set<Int>,
    allSaved: Boolean,
    onAddMeal: (PlannedMeal, Int) -> Unit,
    onAddAll: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text("Tagesplan", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BudgetStat("${plan.totalCalories.roundToInt()}", "kcal")
                    BudgetStat("${plan.totalProtein.roundToInt()}g", "Protein")
                    BudgetStat("${plan.totalCarbs.roundToInt()}g", "Carbs")
                    BudgetStat("${plan.totalFat.roundToInt()}g", "Fett")
                    BudgetStat("${plan.totalFiber.roundToInt()}g", "Fasern")
                }
                if (plan.note.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(plan.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        plan.meals.forEachIndexed { i, meal ->
            PlannedMealCard(
                meal = meal,
                isSaved = i in savedMealIndices,
                onAddToDiary = { onAddMeal(meal, i) }
            )
        }

        Button(
            onClick = onAddAll,
            modifier = Modifier.fillMaxWidth(),
            enabled = !allSaved
        ) {
            Icon(if (allSaved) Icons.Default.Check else Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (allSaved) "Alle im Tagebuch ✓" else "Alle ins Tagebuch eintragen")
        }
    }
}

@Composable
internal fun PlannedMealCard(
    meal: PlannedMeal,
    isSaved: Boolean,
    onAddToDiary: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val mealLabel = when (meal.mealType) {
        "BREAKFAST" -> "☀️ Frühstück"
        "LUNCH" -> "🌤️ Mittagessen"
        "DINNER" -> "🌙 Abendessen"
        "SNACK" -> "🍎 Snack"
        else -> meal.mealType
    }

    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mealLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (meal.timing.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                                Text(meal.timing, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp)
                            }
                        }
                    }
                    Text(meal.title, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
                IconButton(onClick = onAddToDiary, enabled = !isSaved) {
                    Icon(
                        if (isSaved) Icons.Default.Check else Icons.Default.BookmarkAdd,
                        if (isSaved) "Eingetragen" else "Ins Tagebuch"
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroChip("${meal.calories.roundToInt()} kcal", MaterialTheme.colorScheme.primaryContainer)
                MacroChip("P ${meal.protein.toInt()}g", MaterialTheme.colorScheme.secondaryContainer)
                MacroChip("K ${meal.carbs.toInt()}g", MaterialTheme.colorScheme.tertiaryContainer)
                MacroChip("F ${meal.fat.toInt()}g", MaterialTheme.colorScheme.surfaceVariant)
            }

            if (expanded && meal.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(meal.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun RowScope.GenerateButtonContent(isLoading: Boolean, loadingText: String, idleText: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    if (isLoading) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary)
        Spacer(Modifier.width(8.dp))
        Text(loadingText)
    } else {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(idleText)
    }
}

// ── Kochgerät-Auswahl ────────────────────────────────────────────────────────

internal fun CookingMethod.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    CookingMethod.STOVETOP   -> Icons.Default.Whatshot
    CookingMethod.OVEN       -> Icons.Default.Fireplace
    CookingMethod.STEAM_OVEN -> Icons.Default.WaterDrop
    CookingMethod.SMART      -> Icons.Default.Bolt
}

@Composable
internal fun CookingMethodSelector(
    selected: CookingMethod,
    applianceModel: String,
    onSelect: (CookingMethod) -> Unit,
    onSetApplianceModel: (String) -> Unit
) {
    var showApplianceDialog by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Zubereitung", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            // Feste 2×2-Karten statt Chips: verhindert das Zeichen-für-Zeichen-Umbrechen
            // von langen Labels wie "Dampfgarer/Kombi-Dampfgarer" und gibt gleich große Tap-Ziele.
            CookingMethod.entries.chunked(2).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { method ->
                        MethodCard(
                            method = method,
                            isSelected = selected == method,
                            onClick = { onSelect(method) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (selected != CookingMethod.STOVETOP) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { showApplianceDialog = true }
                ) {
                    Icon(Icons.Default.Tune, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (applianceModel.isBlank()) "Gerätemodell hinterlegen für exakte Programme"
                        else "Gerät: $applianceModel",
                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    if (showApplianceDialog) {
        var text by remember { mutableStateOf(applianceModel) }
        AlertDialog(
            onDismissRequest = { showApplianceDialog = false },
            title = { Text("Gerätemodell") },
            text = {
                Column {
                    Text("Für exakte Ofen-/Dampfgarer-Programme (z.B. V-ZUG, Miele).",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("z.B. V-ZUG Combi-Steam SL CSTSLc") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSetApplianceModel(text.trim()); showApplianceDialog = false }) {
                    Text("Speichern")
                }
            },
            dismissButton = { TextButton(onClick = { showApplianceDialog = false }) { Text("Abbrechen") } }
        )
    }
}

/** Gleich große Auswahlkarte für eine Zubereitungsart (ersetzt die zuvor umbrechenden Chips). */
@Composable
internal fun MethodCard(
    method: CookingMethod,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(14.dp),
            color = containerColor,
            border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(method.icon(), null, Modifier.size(20.dp), tint = contentColor)
                Spacer(Modifier.height(4.dp))
                Text(
                    // "/" ist der einzige natürliche Umbruchpunkt in den Labels - an dieser
                    // Stelle explizit umbrechen statt der Textengine das Silbentrennen zu überlassen.
                    method.label.replace("/", "/\n"),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = contentColor,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp)
            )
        }
    }
}

/** Bietet an, das bereits generierte Rezept auf ein anderes Kochgerät umzuschreiben. */
@Composable
internal fun RecipeMethodAdaptRow(
    current: CookingMethod,
    applianceModel: String,
    isAdapting: Boolean,
    onAdapt: (CookingMethod) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            current.label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        CookingMethod.entries.filter { it != current }.forEach { target ->
            OutlinedButton(
                onClick = { onAdapt(target) },
                enabled = !isAdapting,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                if (isAdapting) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                } else {
                    Text("→ ${target.label}", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

