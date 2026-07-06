package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.model.favoriteKey
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MacroBar
import ch.nutrisnap.app.ui.components.SectionHeader
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val context = androidx.compose.ui.platform.LocalContext.current
    val mealPrefs by context.notifDataStore.data.collectAsState(initial = null)
    val mealOrder = remember(mealPrefs) { parseMealOrder(mealPrefs?.get(ch.nutrisnap.app.ui.theme.KEY_MEAL_ORDER)) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, "Eintrag hinzufügen",
                    tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item { DateNavigator(state.selectedDate, vm::prevDay, vm::nextDay) }
            item {
                MacroBar(
                    calories = state.totalCalories, goal = state.calorieGoal,
                    protein  = state.totalProtein,  carbs = state.totalCarbs,
                    fat      = state.totalFat,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (state.entries.isEmpty()) {
                item {
                    EmptyState(
                        icon    = { Icon(Icons.Default.MenuBook, null, Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                                Text("$mealKcal kcal", fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        )
                    }
                    item(key = "meal_group_${meal.name}") {
                        ReorderableMealEntries(
                            entries   = mealEntries,
                            onEdit    = { editEntry = it },
                            onDelete  = { vm.deleteEntry(it) },
                            onReorder = { ids -> vm.reorderEntries(ids) }
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) AddFoodSheet(vm = vm, initialMeal = initialMeal, autoOpenScanner = autoOpenScanner, onDismiss = { showAddSheet = false })

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
            Text(entry.foodName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${(entry.calories * factor).toInt()} kcal",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        Text("P ${(entry.protein * factor).toInt()}g", fontSize = 12.sp)
                        Text("K ${(entry.carbs * factor).toInt()}g", fontSize = 12.sp)
                        Text("F ${(entry.fat * factor).toInt()}g", fontSize = 12.sp)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        }
    )
}

@Composable
private fun DateNavigator(date: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, "Vorheriger Tag") }
        val label = when (date) {
            LocalDate.now()              -> "Heute"
            LocalDate.now().minusDays(1) -> "Gestern"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, dd. MMM", Locale.GERMAN))
        }
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
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
            false // Karte bleibt sichtbar, bis der Dialog bestätigt wurde
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(Icons.Default.DeleteOutline, "Löschen",
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    ) {
        Card(
            Modifier.fillMaxWidth().clickable { onEdit() },
            shape  = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.foodName,
                        fontWeight = FontWeight.Medium,
                        fontSize   = 14.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Text(amountLabel, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${entry.calories.toInt()} kcal",
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary)
                    Text("P ${entry.protein.toInt()}  K ${entry.carbs.toInt()}  F ${entry.fat.toInt()}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (dragHandleModifier != null) {
                    Icon(
                        Icons.Default.DragHandle, "Reihenfolge ändern",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 4.dp).size(20.dp).then(dragHandleModifier)
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

    // Dialog abgebrochen -> Swipe-Zustand zurücksetzen, damit die Karte an Ort bleibt
    LaunchedEffect(showConfirm) {
        if (!showConfirm) dismissState.reset()
    }
}

// Grobe Zeilenhöhe der DiaryEntryRow (Card-Innenpadding 10dp*2 + ~1-zeiliger Text ~20dp
// + Subtext ~16dp + Card-Rand-Padding 3dp*2) – Näherungswert für die Drag-Index-Berechnung,
// exakte Pixelmessung wäre hier unverhältnismäßig aufwändig.
private const val DIARY_ROW_HEIGHT_DP = 62

/**
 * Rendert die Einträge einer Mahlzeit als Drag-&-Drop-sortierbare Liste (Long-Press auf
 * das Handle-Icon startet das Ziehen). Bewusst als normale Column statt LazyColumn-Items,
 * da pro Mahlzeit nur wenige Einträge anfallen und die Drag-Index-Berechnung dadurch simpel bleibt.
 */
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

// ── Add Food Bottom Sheet ─────────────────────────────────────────────────────

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
                        // food wird via searchResults angezeigt — setze direkt als Ergebnis
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
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Eintrag hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp))

            // Tab-Row: Suche / Manuell
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

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Search Tab (existing logic) ───────────────────────────────────────────────

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

    val results   by vm.searchResults.collectAsState()
    val searching by vm.isSearching.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val barcodeResult by vm.barcodeResult.collectAsState()
    val favoriteKeys = remember(favorites) { favorites.map { it.favoriteKey() }.toSet() }

    // If barcode found, auto-select it
    LaunchedEffect(barcodeResult) {
        barcodeResult?.let { selectedFood = it; vm.clearBarcodeResult() }
    }

    if (selectedFood == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it; vm.searchFood(it) },
                label = { Text("Suchen…") },
                leadingIcon  = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp)) },
                modifier = Modifier.weight(1f), singleLine = true
            )
            IconButton(onClick = onOpenScanner,
                modifier = Modifier.align(Alignment.CenterVertically).size(56.dp)) {
                Icon(Icons.Default.QrCodeScanner, "Barcode",
                    Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }

        if (barcodeStatus.isNotBlank()) {
            Text(barcodeStatus, fontSize = 13.sp,
                color = if ("nicht gefunden" in barcodeStatus)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp))
        }

        if (query.isBlank() && favorites.isNotEmpty()) {
            Text("⭐ Favoriten", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(favorites, key = { it.favoriteKey() }) { food ->
                    FoodResultRow(
                        food     = food,
                        onClick  = { selectedFood = food },
                        leading  = {
                            Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp))
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (results.isEmpty() && query.length > 1 && !searching) {
            Text("Keine Treffer", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp))
        }

        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(results) { food ->
                val isFav = food.favoriteKey() in favoriteKeys
                FoodResultRow(
                    food     = food,
                    onClick  = { selectedFood = food },
                    trailingAction = {
                        IconButton(onClick = { vm.toggleFavorite(food) }, modifier = Modifier.size(36.dp)) {
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
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(food.name, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                food.brand?.let { Text(it, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { vm.toggleFavorite(food) }) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = if (isFav) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = amountText, onValueChange = { amountText = it },
                label = { Text("Menge (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), singleLine = true
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }
        val grams = amountText.toFloatOrNull() ?: 0f
        if (grams > 0) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("${(food.calories * grams / 100f).toInt()} kcal",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                Text("P ${(food.protein * grams / 100f).toInt()}g", fontSize = 13.sp)
                Text("K ${(food.carbs * grams / 100f).toInt()}g", fontSize = 13.sp)
                Text("F ${(food.fat * grams / 100f).toInt()}g", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selectedFood = null }, Modifier.weight(1f)) {
                Text("Zurück")
            }
            Button(
                onClick  = { if (grams > 0) { vm.addEntry(food, grams, selectedMeal); onDismiss() } },
                modifier = Modifier.weight(1f), enabled = grams > 0
            ) { Text("Hinzufügen") }
        }
    }
}

// ── Manual Entry Tab ──────────────────────────────────────────────────────────

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

    val kcal    = kcalText.toFloatOrNull() ?: 0f
    val protein = proteinText.toFloatOrNull() ?: 0f
    val carbs   = carbsText.toFloatOrNull() ?: 0f
    val fat     = fatText.toFloatOrNull() ?: 0f
    val isValid = name.isNotBlank() && kcal > 0f

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name (z.B. Müesli mit Früchten)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = kcalText, onValueChange = { kcalText = it },
                label = { Text("kcal *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), singleLine = true
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }

        Text("Makros (optional)", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = proteinText, onValueChange = { proteinText = it },
                label = { Text("Protein g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), singleLine = true
            )
            OutlinedTextField(
                value = carbsText, onValueChange = { carbsText = it },
                label = { Text("Kohlenhydrate g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), singleLine = true
            )
            OutlinedTextField(
                value = fatText, onValueChange = { fatText = it },
                label = { Text("Fett g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f), singleLine = true
            )
        }

        if (isValid) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${ kcal.toInt()} kcal", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                if (protein > 0) Text("P ${protein.toInt()}g", fontSize = 12.sp)
                if (carbs > 0)   Text("K ${carbs.toInt()}g", fontSize = 12.sp)
                if (fat > 0)     Text("F ${fat.toInt()}g", fontSize = 12.sp)
            }
        }

        Button(
            onClick  = { if (isValid) onSave(name, kcal, protein, carbs, fat, selectedMeal) },
            modifier = Modifier.fillMaxWidth(),
            enabled  = isValid
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Manuell hinzufügen")
        }
    }
}

/**
 * Kompakte Ergebnis-Zeile: Icon links, Titel/Subtitel gestapelt, kcal-Wert +
 * optionale Trailing-Action rechts. Gemeinsam genutzt von Favoriten- und Suchliste.
 */
@Composable
private fun FoodResultRow(
    food: FoodItem,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(food.name.firstOrNull()?.uppercase() ?: "?", fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    },
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(food.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(food.brand ?: "${food.calories.toInt()} kcal/100g", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text("${food.calories.toInt()} kcal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary)
        trailingAction?.invoke()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

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
