package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.model.favoriteKey
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MacroBar
import ch.nutrisnap.app.ui.components.MicronutrientTable
import ch.nutrisnap.app.ui.components.NutritionFactsProgress
import ch.nutrisnap.app.ui.components.SectionHeader
import ch.nutrisnap.app.domain.EntryPlausibilityChecker
import ch.nutrisnap.app.domain.FoodPortionPresets
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import androidx.compose.ui.graphics.Color
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun defaultMealForNow(): MealType = when (LocalTime.now().hour) {
    in 5..10  -> MealType.BREAKFAST
    in 11..14 -> MealType.LUNCH
    in 17..21 -> MealType.DINNER
    else      -> MealType.SNACK
}

@Composable
fun DiaryScreen(
    vm: DiaryViewModel = viewModel(),
    initialMeal: MealType? = null,
    autoOpenAdd: Boolean = false,
    autoOpenScanner: Boolean = false
) {
    val state by vm.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(autoOpenAdd || autoOpenScanner) }
    var editEntry    by remember { mutableStateOf<DiaryEntry?>(null) }
    var detailEntry  by remember { mutableStateOf<DiaryEntry?>(null) }
    var expandedNutrition by remember { mutableStateOf<MealType?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val mealPrefs by context.notifDataStore.data.collectAsState(initial = null)
    val mealOrder = remember(mealPrefs) { parseMealOrder(mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_MEAL_ORDER)) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val quickAddFavorites by vm.favorites.collectAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "Eintrag hinzufügen")
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item { DateNavigator(state.selectedDate, vm::prevDay, vm::nextDay) }
            item {
                MacroBar(
                    calories = state.totalCalories,
                    goal = state.calorieGoal,
                    protein  = state.totalProtein,
                    carbs = state.totalCarbs,
                    fat      = state.totalFat,
                    modifier = Modifier.padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm)
                )
            }
            if (quickAddFavorites.isNotEmpty()) {
                item {
                    QuickAddBar(
                        favorites = quickAddFavorites,
                        onQuickAdd = { food ->
                            val grams = FoodPortionPresets.forFood(food).firstOrNull()?.grams
                                ?: food.servingSize.takeIf { it > 0f } ?: 100f
                            val meal = defaultMealForNow()
                            vm.quickAddFavorite(food, grams, meal) { entry ->
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "\"${food.name}\" (${grams.toInt()} g) hinzugefügt",
                                        actionLabel = "Rückgängig",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) vm.deleteEntry(entry)
                                }
                            }
                        }
                    )
                }
            }
            if (state.entries.isEmpty()) {
                item {
                    EmptyState(
                        icon = {
                            Icon(
                                Icons.Default.MenuBook, null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        message = "Noch keine Einträge",
                        sub     = "Tippe auf + um Mahlzeiten zu erfassen"
                    )
                }
            } else {
                val grouped = state.entries.groupBy { it.mealType }
                mealOrder.forEach { meal ->
                    val mealEntries = grouped[meal] ?: return@forEach
                    val mealKcal = mealEntries.sumOf { it.calories.toInt() }
                    item {
                        SectionHeader(
                            title  = meal.label(),
                            action = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$mealKcal kcal",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    IconButton(
                                        onClick = { expandedNutrition = if (expandedNutrition == meal) null else meal },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            if (expandedNutrition == meal) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = "Nährwerte anzeigen",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    if (expandedNutrition == meal) {
                        item(key = "meal_nutrition_${meal.name}") {
                            NutritionFactsProgress(
                                calories = mealEntries.sumOf { it.calories.toDouble() }.toFloat(),
                                caloriesGoal = state.calorieGoal,
                                carbs    = mealEntries.sumOf { it.carbs.toDouble() }.toFloat(),
                                carbsGoal = state.carbsGoal,
                                protein  = mealEntries.sumOf { it.protein.toDouble() }.toFloat(),
                                proteinGoal = state.proteinGoal,
                                fat      = mealEntries.sumOf { it.fat.toDouble() }.toFloat(),
                                fatGoal  = state.fatGoal,
                                modifier = Modifier.padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs)
                            )
                        }
                    }
                    item(key = "meal_group_${meal.name}") {
                        ReorderableMealEntries(
                            entries   = mealEntries,
                            onEdit    = { detailEntry = it; vm.loadEntryDetail(it) },
                            onDelete  = { entry ->
                                vm.deleteEntry(entry)
                                scope.launch {
                                    val result = snackbarHostState.showSnackbar(
                                        message = "\"${entry.foodName}\" gelöscht",
                                        actionLabel = "Rückgängig",
                                        duration = SnackbarDuration.Short
                                    )
                                    if (result == SnackbarResult.ActionPerformed) vm.restoreEntry(entry)
                                }
                            },
                            onReorder = { ids -> vm.reorderEntries(ids) }
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) AddFoodSheet(vm = vm, initialMeal = initialMeal, autoOpenScanner = autoOpenScanner, onDismiss = { showAddSheet = false })

    detailEntry?.let { entry ->
        val foodItem by vm.entryDetailFood.collectAsState()
        EntryDetailSheet(
            entry     = entry,
            foodItem  = foodItem,
            onEdit    = { editEntry = entry; detailEntry = null; vm.clearEntryDetail() },
            onDismiss = { detailEntry = null; vm.clearEntryDetail() }
        )
    }

    editEntry?.let { entry ->
        EditEntryDialog(
            entry    = entry,
            onSave   = { newAmount -> vm.updateEntryAmount(entry, newAmount); editEntry = null },
            onDelete = { vm.deleteEntry(entry); editEntry = null },
            onDismiss = { editEntry = null }
        )
    }
}

@Composable
private fun EditEntryDialog(
    entry: DiaryEntry,
    onSave: (Float) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val isRecipe = entry.amountGrams == 0f || entry.foodItemId < 0
    var amountText by remember { mutableStateOf(
        if (isRecipe) "1" else entry.amountGrams.toInt().toString()
    ) }
    val unit = if (isRecipe) "Port." else "g"

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
                val amount = amountText.toFloatOrNull() ?: 0f
                if (amount > 0 && entry.amountGrams > 0) {
                    val factor = amount / entry.amountGrams
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
                val v = amountText.toFloatOrNull()
                if (v != null && v > 0) onSave(v)
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
private fun EntryDetailSheet(
    entry: DiaryEntry,
    foodItem: ch.nutrisnap.app.data.model.FoodItem?,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val factor = entry.amountGrams / 100f
    val micros = remember(foodItem, entry.amountGrams) {
        buildMap<String, Float> {
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
            val isRecipe = entry.amountGrams == 0f || entry.foodItemId < 0
            Text(
                if (isRecipe) "1 Portion" else "${entry.amountGrams.toInt()} g",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(NutriSpacing.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EntryMacroItem("Kalorien", "${entry.calories.toInt()}", "kcal", MacroColors.calories)
                EntryMacroItem("Protein", "${entry.protein.toInt()}", "g", MacroColors.protein)
                EntryMacroItem("Kohlenhy.", "${entry.carbs.toInt()}", "g", MacroColors.carbs)
                EntryMacroItem("Fett", "${entry.fat.toInt()}", "g", MacroColors.fat)
            }
            if (micros.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = NutriSpacing.md))
                MicronutrientTable(micros, ratio = 1f)
            } else if (!isRecipe) {
                Spacer(Modifier.height(NutriSpacing.md))
                Text(
                    "Keine Mikronährstoffe für diesen Eintrag verfügbar.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EntryMacroItem(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = color
        )
        Text(unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickAddBar(favorites: List<FoodItem>, onQuickAdd: (FoodItem) -> Unit) {
    Column(Modifier.padding(top = NutriSpacing.xs, bottom = NutriSpacing.xs)) {
        Text(
            "\u26A1 Schnell hinzufügen",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = NutriSpacing.lg)
                .padding(bottom = NutriSpacing.sm)
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = NutriSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
        ) {
            favorites.take(10).forEach { food ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(NutriRadius.md))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .clickable { onQuickAdd(food) }
                        .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm)
                        .widthIn(max = 110.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        food.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "${food.calories.toInt()} kcal/100g",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DateNavigator(date: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.sm, vertical = NutriSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.ChevronLeft, "Vorheriger Tag")
        }
        val label = when (date) {
            LocalDate.now()              -> "Heute"
            LocalDate.now().minusDays(1) -> "Gestern"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, dd. MMM", Locale.GERMAN))
        }
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = NutriSpacing.sm)
        )
        IconButton(onClick = onNext, enabled = date.isBefore(LocalDate.now())) {
            Icon(Icons.Default.ChevronRight, "Nächster Tag")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiaryEntryRow(
    entry: DiaryEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier? = null
) {
    var showConfirm by remember { mutableStateOf(false) }

    val isRecipeEntry = entry.amountGrams == 0f || entry.foodItemId < 0
    val amountLabel   = if (isRecipeEntry) "1 Port." else "${entry.amountGrams.toInt()} g"

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) showConfirm = true
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = 3.dp),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(NutriRadius.md))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = NutriSpacing.xl),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.DeleteOutline, "Löschen",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .clickable { onEdit() },
            shape  = RoundedCornerShape(NutriRadius.md),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(0.5.dp)
        ) {
            Row(
                Modifier.padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.foodName,
                        fontWeight = FontWeight.Medium,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        amountLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(NutriSpacing.sm))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${entry.calories.toInt()} kcal",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MacroColors.calories
                    )
                    Text(
                        "P ${entry.protein.toInt()}  K ${entry.carbs.toInt()}  F ${entry.fat.toInt()}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (dragHandleModifier != null) {
                    Icon(
                        Icons.Default.DragHandle, "Reihenfolge ändern",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .padding(start = NutriSpacing.xs)
                            .size(20.dp)
                            .then(dragHandleModifier)
                    )
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title   = { Text("Eintrag löschen?") },
            text    = { Text(entry.foodName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") }
            }
        )
    }

    LaunchedEffect(showConfirm) {
        if (!showConfirm) dismissState.reset()
    }
}

private const val DIARY_ROW_HEIGHT_DP = 62

@Composable
private fun ReorderableMealEntries(
    entries: List<DiaryEntry>,
    onEdit: (DiaryEntry) -> Unit,
    onDelete: (DiaryEntry) -> Unit,
    onReorder: (List<Long>) -> Unit
) {
    var items by remember(entries.map { it.id }) { mutableStateOf(entries) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { DIARY_ROW_HEIGHT_DP.dp.toPx() }

    Column {
        items.forEach { entry ->
            val isDragging = entry.id == draggingId
            DiaryEntryRow(
                entry    = entry,
                onEdit   = { onEdit(entry) },
                onDelete = { onDelete(entry) },
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f },
                dragHandleModifier = Modifier.pointerInput(entry.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { draggingId = entry.id; dragOffset = 0f },
                        onDragEnd   = {
                            draggingId = null
                            dragOffset = 0f
                            onReorder(items.map { it.id })
                        },
                        onDragCancel = { draggingId = null; dragOffset = 0f },
                        onDrag = { change, delta ->
                            change.consume()
                            dragOffset += delta.y
                            val fromIndex = items.indexOfFirst { it.id == entry.id }
                            val toIndex = (fromIndex + (dragOffset / rowHeightPx).roundToInt())
                                .coerceIn(0, items.lastIndex)
                            if (toIndex != fromIndex) {
                                items = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
                                dragOffset -= (toIndex - fromIndex) * rowHeightPx
                            }
                        }
                    )
                }
            )
        }
    }
}

enum class AddFoodTab { SEARCH, MANUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(vm: DiaryViewModel, initialMeal: MealType? = null, autoOpenScanner: Boolean = false, onDismiss: () -> Unit) {
    var activeTab    by remember { mutableStateOf(AddFoodTab.SEARCH) }
    var showScanner  by remember { mutableStateOf(autoOpenScanner) }
    var barcodeStatus by remember { mutableStateOf("") }

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeDetected = { barcode ->
                showScanner = false
                barcodeStatus = "Suche Barcode $barcode..."
                vm.searchBarcode(barcode) { food ->
                    if (food != null) {
                        activeTab = AddFoodTab.SEARCH
                        barcodeStatus = ""
                        vm.setBarcodeResult(food)
                    } else {
                        barcodeStatus = "Barcode $barcode nicht gefunden"
                    }
                }
            },
            onNavigateBack = { showScanner = false }
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = NutriSpacing.lg)
                .navigationBarsPadding()
        ) {
            Text(
                "Eintrag hinzufügen",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = NutriSpacing.md)
            )

            TabRow(selectedTabIndex = activeTab.ordinal) {
                Tab(
                    selected = activeTab == AddFoodTab.SEARCH,
                    onClick  = { activeTab = AddFoodTab.SEARCH },
                    text     = { Text("Suche & Barcode") },
                    icon     = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) }
                )
                Tab(
                    selected = activeTab == AddFoodTab.MANUAL,
                    onClick  = { activeTab = AddFoodTab.MANUAL },
                    text     = { Text("Manuell") },
                    icon     = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
                )
            }

            Spacer(Modifier.height(NutriSpacing.md))

            when (activeTab) {
                AddFoodTab.SEARCH -> SearchTab(
                    vm = vm,
                    initialMeal = initialMeal,
                    barcodeStatus = barcodeStatus,
                    onOpenScanner = { showScanner = true },
                    onDismiss = onDismiss
                )
                AddFoodTab.MANUAL -> ManualEntryTab(
                    initialMeal = initialMeal,
                    onSave = { name, kcal, protein, carbs, fat, meal ->
                        vm.addManualEntry(name, kcal, protein, carbs, fat, meal)
                        onDismiss()
                    }
                )
            }

            Spacer(Modifier.height(NutriSpacing.xxxl))
        }
    }
}

@Composable
private fun SearchTab(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    barcodeStatus: String,
    onOpenScanner: () -> Unit,
    onDismiss: () -> Unit
) {
    var query        by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<FoodItem?>(null) }
    var amountText   by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    var portionWarning by remember { mutableStateOf<String?>(null) }

    val results   by vm.searchResults.collectAsState()
    val searching by vm.isSearching.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val barcodeResult by vm.barcodeResult.collectAsState()
    val favoriteKeys = remember(favorites) { favorites.map { it.favoriteKey() }.toSet() }

    LaunchedEffect(barcodeResult) {
        barcodeResult?.let { selectedFood = it; vm.clearBarcodeResult() }
    }

    if (selectedFood == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.searchFood(it) },
                label = { Text("Suchen\u2026") },
                leadingIcon  = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            IconButton(
                onClick = onOpenScanner,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(56.dp)
            ) {
                Icon(
                    Icons.Default.QrCodeScanner, "Barcode",
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (barcodeStatus.isNotBlank()) {
            Text(
                barcodeStatus,
                fontSize = 13.sp,
                color = if ("nicht gefunden" in barcodeStatus)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = NutriSpacing.xs)
            )
        }

        if (query.isBlank() && favorites.isNotEmpty()) {
            Text(
                "\u2B50 Favoriten",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = NutriSpacing.md, bottom = NutriSpacing.xs)
            )
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(favorites, key = { it.favoriteKey() }) { food ->
                    FoodResultRow(
                        food     = food,
                        onClick  = { selectedFood = food },
                        leading  = {
                            Icon(
                                Icons.Default.Star, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (results.isEmpty() && query.length > 1 && !searching) {
            Text(
                "Keine Treffer",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = NutriSpacing.sm)
            )
        }

        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(results) { food ->
                val isFav = food.favoriteKey() in favoriteKeys
                FoodResultRow(
                    food     = food,
                    onClick  = { selectedFood = food },
                    trailingAction = {
                        IconButton(
                            onClick = { vm.toggleFavorite(food) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = if (isFav) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    } else {
        val food  = selectedFood!!
        val isFav = food.favoriteKey() in favoriteKeys
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    food.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                food.brand?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { vm.toggleFavorite(food) }) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = if (isFav) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(NutriSpacing.md))
        val presets = remember(food) { ch.nutrisnap.app.domain.FoodPortionPresets.forFood(food) }
        if (presets.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = amountText.toFloatOrNull() == preset.grams,
                        onClick  = { amountText = preset.grams.toInt().toString() },
                        label    = { Text(preset.label, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(Modifier.height(NutriSpacing.sm))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Menge (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }
        val grams = amountText.toFloatOrNull() ?: 0f
        if (grams > 0) {
            Spacer(Modifier.height(NutriSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.lg)) {
                Text(
                    "${(food.calories * grams / 100f).toInt()} kcal",
                    fontWeight = FontWeight.SemiBold,
                    color = MacroColors.calories
                )
                Text("P ${(food.protein * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.protein)
                Text("K ${(food.carbs * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.carbs)
                Text("F ${(food.fat * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.fat)
            }
        }
        Spacer(Modifier.height(NutriSpacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedButton(
                onClick = { selectedFood = null },
                modifier = Modifier.weight(1f)
            ) {
                Text("Zurück")
            }
            Button(
                onClick  = {
                    if (grams > 0) {
                        val warning = EntryPlausibilityChecker.checkPortion(grams)
                        if (warning != null) portionWarning = warning
                        else { vm.addEntry(food, grams, selectedMeal); onDismiss() }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = grams > 0
            ) { Text("Hinzufügen") }
        }
        portionWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { portionWarning = null },
                title   = { Text("Menge prüfen") },
                text    = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.addEntry(food, grams, selectedMeal)
                        portionWarning = null
                        onDismiss()
                    }) { Text("Trotzdem hinzufügen") }
                },
                dismissButton = {
                    TextButton(onClick = { portionWarning = null }) { Text("Anpassen") }
                }
            )
        }
    }
}

@Composable
private fun ManualEntryTab(
    initialMeal: MealType? = null,
    onSave: (name: String, kcal: Float, protein: Float, carbs: Float, fat: Float, meal: MealType) -> Unit
) {
    var name         by remember { mutableStateOf("") }
    var kcalText     by remember { mutableStateOf("") }
    var proteinText  by remember { mutableStateOf("") }
    var carbsText    by remember { mutableStateOf("") }
    var fatText      by remember { mutableStateOf("") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    var manualWarning by remember { mutableStateOf<String?>(null) }

    val kcal    = kcalText.toFloatOrNull() ?: 0f
    val protein = proteinText.toFloatOrNull() ?: 0f
    val carbs   = carbsText.toFloatOrNull() ?: 0f
    val fat     = fatText.toFloatOrNull() ?: 0f
    val isValid = name.isNotBlank() && kcal > 0f

    Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name (z.B. Müesli mit Früchten)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(NutriRadius.md)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = kcalText, onValueChange = { kcalText = it },
                label = { Text("kcal *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }

        Text(
            "Makros (optional)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = proteinText, onValueChange = { proteinText = it },
                label = { Text("Protein g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            OutlinedTextField(
                value = carbsText, onValueChange = { carbsText = it },
                label = { Text("Kohlenhydrate g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            OutlinedTextField(
                value = fatText, onValueChange = { fatText = it },
                label = { Text("Fett g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
        }

        if (isValid) {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.lg)) {
                Text("${kcal.toInt()} kcal", fontWeight = FontWeight.SemiBold, color = MacroColors.calories)
                if (protein > 0) Text("P ${protein.toInt()}g", fontSize = 12.sp, color = MacroColors.protein)
                if (carbs > 0)   Text("K ${carbs.toInt()}g", fontSize = 12.sp, color = MacroColors.carbs)
                if (fat > 0)     Text("F ${fat.toInt()}g", fontSize = 12.sp, color = MacroColors.fat)
            }
        }

        Button(
            onClick  = {
                if (isValid) {
                    val warning = EntryPlausibilityChecker.checkManualEntry(kcal, protein, carbs, fat)
                    if (warning != null) manualWarning = warning
                    else onSave(name, kcal, protein, carbs, fat, selectedMeal)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = isValid
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(NutriSpacing.sm))
            Text("Manuell hinzufügen")
        }
        manualWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { manualWarning = null },
                title   = { Text("Werte prüfen") },
                text    = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = {
                        onSave(name, kcal, protein, carbs, fat, selectedMeal)
                        manualWarning = null
                    }) { Text("Trotzdem speichern") }
                },
                dismissButton = {
                    TextButton(onClick = { manualWarning = null }) { Text("Anpassen") }
                }
            )
        }
    }
}

@Composable
private fun FoodResultRow(
    food: FoodItem,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(NutriRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                food.name.firstOrNull()?.uppercase() ?: "?",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    },
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = NutriSpacing.sm, horizontal = NutriSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(NutriSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                food.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                food.brand ?: "${food.calories.toInt()} kcal/100g",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(NutriSpacing.sm))
        Text(
            "${food.calories.toInt()} kcal",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MacroColors.calories
        )
        trailingAction?.invoke()
    }
}

@Composable
private fun MealPicker(selected: MealType, onSelect: (MealType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label())
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MealType.values().forEach { meal ->
                DropdownMenuItem(
                    text    = { Text(meal.label()) },
                    onClick = { onSelect(meal); expanded = false }
                )
            }
        }
    }
}

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
