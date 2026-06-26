package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.model.favoriteKey
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MacroBar
import ch.nutrisnap.app.ui.components.SectionHeader
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiaryScreen(vm: DiaryViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editEntry    by remember { mutableStateOf<DiaryEntry?>(null) }

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
                MealType.values().forEach { meal ->
                    val mealEntries = grouped[meal] ?: return@forEach
                    item { SectionHeader(meal.label()) }
                    items(mealEntries, key = { it.id }) { entry ->
                        DiaryEntryRow(
                            entry    = entry,
                            onEdit   = { editEntry = entry },
                            onDelete = { vm.deleteEntry(entry) }
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) AddFoodSheet(vm = vm, onDismiss = { showAddSheet = false })

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

@Composable
private fun DiaryEntryRow(entry: DiaryEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    val isRecipeEntry = entry.amountGrams == 0f || entry.foodItemId < 0
    val amountLabel   = if (isRecipeEntry) "1 Port." else "${entry.amountGrams.toInt()} g"

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clickable { onEdit() },
        shape = RoundedCornerShape(12.dp),
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
            IconButton(onClick = { showConfirm = true }) {
                Icon(Icons.Default.DeleteOutline, "Löschen",
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
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
}

// ── Add Food Bottom Sheet ─────────────────────────────────────────────────────

enum class AddFoodTab { SEARCH, MANUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(vm: DiaryViewModel, onDismiss: () -> Unit) {
    var activeTab    by remember { mutableStateOf(AddFoodTab.SEARCH) }
    var showScanner  by remember { mutableStateOf(false) }
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
                    barcodeStatus = barcodeStatus,
                    onOpenScanner = { showScanner = true },
                    onDismiss = onDismiss
                )
                AddFoodTab.MANUAL -> ManualEntryTab(
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
    barcodeStatus: String,
    onOpenScanner: () -> Unit,
    onDismiss: () -> Unit
) {
    var query        by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<FoodItem?>(null) }
    var amountText   by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }

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
                    ListItem(
                        headlineContent   = { Text(food.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${food.calories.toInt()} kcal/100g${food.brand?.let { " · $it" } ?: ""}") },
                        leadingContent    = { Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                        modifier          = Modifier.clickable { selectedFood = food }
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
                ListItem(
                    headlineContent   = { Text(food.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${food.calories.toInt()} kcal/100g${food.brand?.let { " · $it" } ?: ""}") },
                    trailingContent   = {
                        IconButton(onClick = { vm.toggleFavorite(food) }) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = if (isFav) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    modifier = Modifier.clickable { selectedFood = food }
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
    onSave: (name: String, kcal: Float, protein: Float, carbs: Float, fat: Float, meal: MealType) -> Unit
) {
    var name         by remember { mutableStateOf("") }
    var kcalText     by remember { mutableStateOf("") }
    var proteinText  by remember { mutableStateOf("") }
    var carbsText    by remember { mutableStateOf("") }
    var fatText      by remember { mutableStateOf("") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }

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
