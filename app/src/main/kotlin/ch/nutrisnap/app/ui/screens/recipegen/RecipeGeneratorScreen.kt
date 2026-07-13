package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.CookingMethod
import ch.nutrisnap.app.domain.DayPlan
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.PlannedMeal
import ch.nutrisnap.app.domain.RecipeIngredient
import ch.nutrisnap.app.domain.WorkoutTiming
import ch.nutrisnap.app.ui.screens.scan.PhotoCaptureScreen
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RecipeGeneratorScreen(vm: RecipeGeneratorViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    if (state.showFridgeCamera) {
        PhotoCaptureScreen(
            title = "Kuehlschrank fotografieren",
            instructions = "Foto vom Kuehlschrank oder Vorrat machen, die KI erkennt die Zutaten",
            onPhotoCaptured = { bitmap -> vm.analyzeFridgePhoto(bitmap) },
            onNavigateBack = { vm.closeFridgeCamera() }
        )
        return
    }

    var input by remember { mutableStateOf("") }
    var ingredientInput by remember { mutableStateOf("") }
    var fillUpMealLabel by remember { mutableStateOf("Abendessen") }
    var showDiarySheet by remember { mutableStateOf(false) }
    var entityToDelete by remember { mutableStateOf<GeneratedRecipeEntity?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.savedToDiary) {
        if (state.savedToDiary) {
            snackbarHostState.showSnackbar("Rezept ins Tagebuch eingetragen ✓")
            vm.clearSavedFlag()
        }
    }
    LaunchedEffect(state.savedAsRecipe) {
        if (state.savedAsRecipe) {
            snackbarHostState.showSnackbar("Im Rezepte-Tab gespeichert ✓")
            vm.clearSavedAsRecipeFlag()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.recipe != null) {
                        IconButton(onClick = { vm.clearRecipe() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück zum Verlauf")
                        }
                    } else if (state.dayPlan != null) {
                        IconButton(onClick = { vm.clearDayPlan() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück zur Eingabe")
                        }
                    }
                    Text("KI-Rezeptgenerator",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
            }

            if (state.recipe == null && state.dayPlan == null) {
                item {
                    val tabs = listOf(
                        Triple(RecipeGenMode.FREITEXT, "Freitext", Icons.Default.Edit),
                        Triple(RecipeGenMode.ZUTATEN, "Zutaten", Icons.Default.Kitchen),
                        Triple(RecipeGenMode.FILL_UP, "Fill Up", Icons.Default.LocalFireDepartment),
                        Triple(RecipeGenMode.ZUFALL, "Zufall", Icons.Default.Casino),
                        Triple(RecipeGenMode.TAGESPLAN, "Tagesplan", Icons.Default.CalendarToday)
                    )
                    ScrollableTabRow(
                        selectedTabIndex = tabs.indexOfFirst { it.first == state.mode }.coerceAtLeast(0),
                        edgePadding = 0.dp
                    ) {
                        tabs.forEach { (mode, label, icon) ->
                            Tab(
                                selected = state.mode == mode,
                                onClick = { vm.setMode(mode) },
                                text = { Text(label, fontSize = 12.sp) },
                                icon = { Icon(icon, null, Modifier.size(18.dp)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))

                    CookingMethodSelector(
                        selected = state.cookingMethod,
                        applianceModel = state.applianceModel,
                        onSelect = { vm.setCookingMethod(it) },
                        onSetApplianceModel = { vm.setApplianceModel(it) }
                    )
                    Spacer(Modifier.height(12.dp))

                    when (state.mode) {
                        RecipeGenMode.FREITEXT -> FreitextInput(
                            input = input,
                            onInputChange = { input = it },
                            isLoading = state.isLoading,
                            onGenerate = { vm.generate(input) }
                        )
                        RecipeGenMode.ZUTATEN -> ZutatenInput(
                            chips = state.ingredientChips,
                            ingredientInput = ingredientInput,
                            onIngredientInputChange = { ingredientInput = it },
                            onAddChip = {
                                vm.addIngredientChip(ingredientInput)
                                ingredientInput = ""
                            },
                            onRemoveChip = { vm.removeIngredientChip(it) },
                            onOpenCamera = { vm.openFridgeCamera() },
                            isScanningFridge = state.isScanningFridge,
                            isLoading = state.isLoading,
                            onGenerate = { vm.generateFromIngredients() }
                        )
                        RecipeGenMode.FILL_UP -> FillUpInput(
                            budget = state.fillUpBudget,
                            mealLabel = fillUpMealLabel,
                            onMealLabelChange = { fillUpMealLabel = it },
                            isLoading = state.isLoading,
                            onGenerate = { vm.generateFillUp(fillUpMealLabel) }
                        )
                        RecipeGenMode.ZUFALL -> ZufallInput(
                            isLoading = state.isLoading,
                            onGenerate = { vm.generateRandomRecipe() }
                        )
                        RecipeGenMode.TAGESPLAN -> TagesplanInput(
                            state = state,
                            vm = vm,
                            isLoading = state.isDayPlanLoading
                        )
                    }
                }
            }

            if (state.mode == RecipeGenMode.TAGESPLAN) {
                state.dayPlanError?.let { error ->
                    item {
                        Card(colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                IconButton(onClick = vm::clearDayPlanError) { Icon(Icons.Default.Close, null) }
                            }
                        }
                    }
                }

                state.dayPlan?.let { plan ->
                    item {
                        DayPlanResultCard(
                            plan = plan,
                            savedMealIndices = state.dayPlanSavedMealIndices,
                            allSaved = state.dayPlanAllSaved,
                            onAddMeal = { meal, index -> vm.addPlannedMealToDiary(meal, index) },
                            onAddAll = { vm.addAllPlannedMealsToDiary() }
                        )
                    }
                }
            }

            state.error?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                            IconButton(onClick = vm::clearError) { Icon(Icons.Default.Close, null) }
                        }
                    }
                }
            }

            state.recipe?.let { recipe ->
                item {
                    RecipeResultCard(
                        recipe = recipe,
                        isSavingImage = state.isGeneratingImage,
                        onAddToDiary = { showDiarySheet = true },
                        onSaveAsRecipe = { vm.saveAsRecipe() },
                        onUpdate = { vm.updateRecipe(it) },
                        onRemoveIngredient = { vm.removeIngredient(it) },
                        onUpdateIngredient = { i, ing -> vm.updateIngredient(i, ing) },
                        cookingMethod = state.cookingMethod,
                        applianceModel = state.applianceModel,
                        isAdaptingMethod = state.isAdaptingMethod,
                        onAdaptToMethod = { vm.adaptCurrentRecipeToMethod(it) }
                    )
                }
            }

            if (state.recipe == null && state.dayPlan == null && !state.isLoading && state.history.isNotEmpty()) {
                item {
                    Text("Letzte Rezepte", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                items(state.history, key = { it.id }) { entity ->
                    Card(Modifier.fillMaxWidth()) {
                        ListItem(
                            modifier = Modifier.clickable { vm.openFromHistory(entity) },
                            headlineContent = { Text(entity.title, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("${entity.calories} kcal") },
                            leadingContent = { Icon(Icons.Default.History, null) },
                            trailingContent = {
                                IconButton(onClick = { entityToDelete = entity }) {
                                    Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDiarySheet) {
        state.recipe?.let { recipe ->
            AddToDiarySheet(
                recipe = recipe,
                onDismiss = { showDiarySheet = false },
                onConfirm = { servings, mealType ->
                    vm.addToDiary(recipe, servings, mealType)
                    showDiarySheet = false
                }
            )
        }
    }

    entityToDelete?.let { entity ->
        AlertDialog(
            onDismissRequest = { entityToDelete = null },
            title = { Text("Rezept löschen?") },
            text = { Text("\"${entity.title}\" wird aus dem Verlauf entfernt.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteFromHistory(entity); entityToDelete = null }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { entityToDelete = null }) { Text("Abbrechen") } }
        )
    }
}

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
private fun FreitextInput(
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
private fun ZutatenInput(
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
private fun FillUpInput(
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
private fun BudgetStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun ZufallInput(
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
private fun TagesplanInput(
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
private fun DayPlanResultCard(
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
private fun PlannedMealCard(
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
private fun RowScope.GenerateButtonContent(isLoading: Boolean, loadingText: String, idleText: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
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

private fun CookingMethod.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    CookingMethod.STOVETOP   -> Icons.Default.Whatshot
    CookingMethod.OVEN       -> Icons.Default.Fireplace
    CookingMethod.STEAM_OVEN -> Icons.Default.WaterDrop
    CookingMethod.SMART      -> Icons.Default.Bolt
}

@Composable
private fun CookingMethodSelector(
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
private fun MethodCard(
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
private fun RecipeMethodAdaptRow(
    current: CookingMethod,
    applianceModel: String,
    isAdapting: Boolean,
    onAdapt: (CookingMethod) -> Unit
) {
    Column {
        Text("Zubereitung für: ${current.label}", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CookingMethod.entries.filter { it != current }.forEach { target ->
                OutlinedButton(onClick = { onAdapt(target) }, enabled = !isAdapting) {
                    if (isAdapting) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("→ ${target.label}", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ── Rezept-Karte ───────────────────────────────────────────────────────────────

@Composable
private fun RecipeResultCard(
    recipe: GeneratedRecipe,
    isSavingImage: Boolean = false,
    onAddToDiary: () -> Unit,
    onSaveAsRecipe: () -> Unit,
    onUpdate: (GeneratedRecipe) -> Unit,
    onRemoveIngredient: (Int) -> Unit,
    onUpdateIngredient: (Int, RecipeIngredient) -> Unit,
    cookingMethod: CookingMethod = CookingMethod.STOVETOP,
    applianceModel: String = "",
    isAdaptingMethod: Boolean = false,
    onAdaptToMethod: (CookingMethod) -> Unit = {}
) {
    var checkedIngredients by remember(recipe) { mutableStateOf(setOf<Int>()) }
    var isEditing by remember { mutableStateOf(false) }

    // Edit-Felder (nur aktiv im Edit-Modus)
    var titleText    by remember(recipe, isEditing) { mutableStateOf(recipe.title) }
    var caloriesText by remember(recipe, isEditing) { mutableStateOf(recipe.calories.roundToInt().toString()) }
    var proteinText  by remember(recipe, isEditing) { mutableStateOf(recipe.protein.toInt().toString()) }
    var carbsText    by remember(recipe, isEditing) { mutableStateOf(recipe.carbs.toInt().toString()) }
    var fatText      by remember(recipe, isEditing) { mutableStateOf(recipe.fat.toInt().toString()) }
    var servingsText by remember(recipe, isEditing) { mutableStateOf(recipe.servings.toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (isEditing) {
                        OutlinedTextField(
                            value = titleText, onValueChange = { titleText = it },
                            label = { Text("Titel") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(recipe.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (recipe.description.isNotBlank()) {
                            Text(recipe.description, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                IconButton(onClick = {
                    if (isEditing) {
                        onUpdate(
                            recipe.copy(
                                title    = titleText.ifBlank { recipe.title },
                                calories = caloriesText.toFloatOrNull() ?: recipe.calories,
                                protein  = proteinText.toFloatOrNull()  ?: recipe.protein,
                                carbs    = carbsText.toFloatOrNull()    ?: recipe.carbs,
                                fat      = fatText.toFloatOrNull()      ?: recipe.fat,
                                servings = servingsText.toIntOrNull()   ?: recipe.servings
                            )
                        )
                    }
                    isEditing = !isEditing
                }) {
                    Icon(if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                        if (isEditing) "Speichern" else "Bearbeiten")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (!isEditing) {
                RecipeMethodAdaptRow(
                    current = cookingMethod,
                    applianceModel = applianceModel,
                    isAdapting = isAdaptingMethod,
                    onAdapt = onAdaptToMethod
                )
                Spacer(Modifier.height(8.dp))
            }

            if (isEditing) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditMacroField("kcal", caloriesText) { caloriesText = it }
                    EditMacroField("P g", proteinText) { proteinText = it }
                    EditMacroField("K g", carbsText) { carbsText = it }
                    EditMacroField("F g", fatText) { fatText = it }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = servingsText, onValueChange = { servingsText = it },
                    label = { Text("Portionen") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp)
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MacroChip("${recipe.calories.roundToInt()} kcal", MaterialTheme.colorScheme.primaryContainer)
                    MacroChip("P ${recipe.protein.toInt()}g", MaterialTheme.colorScheme.secondaryContainer)
                    MacroChip("K ${recipe.carbs.toInt()}g", MaterialTheme.colorScheme.tertiaryContainer)
                    MacroChip("F ${recipe.fat.toInt()}g", MaterialTheme.colorScheme.surfaceVariant)
                }
                Text("${recipe.servings} Port. · ${recipe.prepTimeMinutes} Min.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Zutaten", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            val ingredients = recipe.effectiveIngredients()
            if (isEditing) {
                ingredients.forEachIndexed { i, ing ->
                    EditableIngredientRow(
                        ingredient = ing,
                        onChange = { onUpdateIngredient(i, it) },
                        onDelete = { onRemoveIngredient(i) }
                    )
                }
                TextButton(onClick = {
                    onUpdateIngredient(ingredients.size, RecipeIngredient(name = "Neue Zutat"))
                }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Zutat hinzufügen")
                }
            } else {
                ingredients.forEachIndexed { i, ing ->
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = i in checkedIngredients,
                            onCheckedChange = { checked ->
                                checkedIngredients = if (checked) checkedIngredients + i else checkedIngredients - i
                            },
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (ing.amount.isNotBlank()) "${ing.amount} ${ing.name}" else ing.name,
                                fontSize = 14.sp,
                                color = if (i in checkedIngredients)
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (ing.calories > 0) {
                                Text("${ing.calories.roundToInt()} kcal", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text("Zubereitung", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            recipe.steps.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(step, fontSize = 14.sp, modifier = Modifier.weight(1f))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSaveAsRecipe,
                    enabled = !isSavingImage,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSavingImage) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Bild wird erstellt…")
                    } else {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Als Rezept")
                    }
                }
                Button(onClick = onAddToDiary, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Tagebuch")
                }
            }
        }
    }
}

@Composable
private fun RowScope.EditMacroField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.weight(1f)
    )
}

@Composable
private fun EditableIngredientRow(
    ingredient: RecipeIngredient,
    onChange: (RecipeIngredient) -> Unit,
    onDelete: () -> Unit
) {
    var name   by remember(ingredient) { mutableStateOf(ingredient.name) }
    var amount by remember(ingredient) { mutableStateOf(ingredient.amount) }
    var kcal   by remember(ingredient) { mutableStateOf(if (ingredient.calories > 0) ingredient.calories.roundToInt().toString() else "") }

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it; onChange(ingredient.copy(name = name, amount = it, calories = kcal.toFloatOrNull() ?: ingredient.calories)) },
            label = { Text("Menge") }, singleLine = true,
            modifier = Modifier.width(80.dp)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; onChange(ingredient.copy(name = it, amount = amount, calories = kcal.toFloatOrNull() ?: ingredient.calories)) },
            label = { Text("Zutat") }, singleLine = true,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(
            value = kcal,
            onValueChange = { kcal = it; onChange(ingredient.copy(name = name, amount = amount, calories = it.toFloatOrNull() ?: ingredient.calories)) },
            label = { Text("kcal") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(72.dp)
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, "Entfernen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        }
    }
}

// ── Tagebuch-Sheet ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToDiarySheet(
    recipe: GeneratedRecipe,
    onDismiss: () -> Unit,
    onConfirm: (servings: Int, mealType: MealType) -> Unit
) {
    var servings  by remember { mutableIntStateOf(1) }
    var mealType  by remember { mutableStateOf(MealType.LUNCH) }

    val mealLabels = mapOf(
        MealType.BREAKFAST to "☀️ Frühstück",
        MealType.LUNCH     to "🌤️ Mittagessen",
        MealType.DINNER    to "🌙 Abendessen",
        MealType.SNACK     to "🍎 Snack"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Zum Tagebuch hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(recipe.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))

            Text("Portionen", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(
                    onClick = { if (servings > 1) servings-- },
                    enabled = servings > 1
                ) { Icon(Icons.Default.Remove, null) }
                Text("$servings", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = { if (servings < 10) servings++ }) {
                    Icon(Icons.Default.Add, null)
                }
                val kcal = (recipe.calories / recipe.servings.coerceAtLeast(1)) * servings
                Text("= ${kcal.toInt()} kcal",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))

            Text("Mahlzeit", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mealLabels.entries.take(2).forEach { (type, label) ->
                    FilterChip(
                        selected = mealType == type,
                        onClick = { mealType = type },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mealLabels.entries.drop(2).forEach { (type, label) ->
                    FilterChip(
                        selected = mealType == type,
                        onClick = { mealType = type },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { onConfirm(servings, mealType) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Eintragen")
            }
        }
    }
}

@Composable
private fun MacroChip(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
