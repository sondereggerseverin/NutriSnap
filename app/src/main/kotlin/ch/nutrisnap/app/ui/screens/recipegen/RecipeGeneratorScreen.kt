package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.background
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    val state by vm.state.collectAsStateWithLifecycle()

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
                        imageUrl = state.recipeImageUrl,
                        imageError = state.imageError,
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
                onConfirm = { servings, mealType, date ->
                    vm.addToDiary(recipe, servings, mealType, date)
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

