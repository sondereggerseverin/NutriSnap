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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.data.model.favoriteKey
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MacroBar
import ch.nutrisnap.app.ui.components.SectionHeader
import ch.nutrisnap.app.ui.screens.food.BarcodeScannerScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiaryScreen(vm: DiaryViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Default.Add, "Eintrag hinzufügen", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
            item { DateNavigator(state.selectedDate, vm::prevDay, vm::nextDay) }
            item {
                MacroBar(
                    calories = state.totalCalories, goal = state.calorieGoal,
                    protein  = state.totalProtein,  carbs = state.totalCarbs, fat = state.totalFat,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (state.entries.isEmpty()) {
                item {
                    EmptyState(
                        icon    = { Icon(Icons.Default.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
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
                        DiaryEntryRow(entry) { vm.deleteEntry(entry) }
                    }
                }
            }
        }
    }
    if (showAddSheet) AddFoodSheet(vm = vm, onDismiss = { showAddSheet = false })
}

@Composable
private fun DateNavigator(date: LocalDate, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, "Vorheriger Tag") }
        val label = when (date) {
            LocalDate.now()              -> "Heute"
            LocalDate.now().minusDays(1) -> "Gestern"
            else -> date.format(DateTimeFormatter.ofPattern("EEE, dd. MMM", Locale.GERMAN))
        }
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(onClick = onNext, enabled = date.isBefore(LocalDate.now())) {
            Icon(Icons.Default.ChevronRight, "Nächster Tag")
        }
    }
}

@Composable
private fun DiaryEntryRow(entry: DiaryEntry, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.foodName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("${entry.amountGrams.toInt()} g", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${entry.calories.toInt()} kcal", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Text("P ${entry.protein.toInt()}  K ${entry.carbs.toInt()}  F ${entry.fat.toInt()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showConfirm) {
        AlertDialog(onDismissRequest = { showConfirm = false },
            title = { Text("Eintrag löschen?") }, text = { Text(entry.foodName) },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } })
    }
}

// ── Add Food Bottom Sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(vm: DiaryViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<FoodItem?>(null) }
    var amountText by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    var showScanner by remember { mutableStateOf(false) }
    var barcodeStatus by remember { mutableStateOf("") }

    val results   by vm.searchResults.collectAsState()
    val searching by vm.isSearching.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val favoriteKeys = remember(favorites) { favorites.map { it.favoriteKey() }.toSet() }

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeDetected = { barcode ->
                showScanner = false
                barcodeStatus = "Suche Barcode $barcode..."
                vm.searchBarcode(barcode) { food ->
                    if (food != null) {
                        selectedFood = food
                        barcodeStatus = ""
                    } else {
                        barcodeStatus = "Barcode $barcode nicht gefunden"
                    }
                }
            },
            onDismiss = { showScanner = false }
        )
        return
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Lebensmittel hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(bottom = 12.dp))

            if (selectedFood == null) {
                // Search row + barcode button
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; vm.searchFood(it) },
                        label = { Text("Suchen…") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp)) },
                        modifier = Modifier.weight(1f), singleLine = true
                    )
                    IconButton(onClick = { showScanner = true },
                        modifier = Modifier.align(Alignment.CenterVertically).size(56.dp)) {
                        Icon(Icons.Default.QrCodeScanner, "Barcode scannen",
                            modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }

                if (barcodeStatus.isNotBlank()) {
                    Text(barcodeStatus, fontSize = 13.sp,
                        color = if ("nicht gefunden" in barcodeStatus) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp))
                }

                // Quick-pick from favorites when no search query is active
                if (query.isBlank() && favorites.isNotEmpty()) {
                    Text("⭐ Favoriten", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(favorites, key = { it.favoriteKey() }) { food ->
                            ListItem(
                                headlineContent = { Text(food.name) },
                                supportingContent = {
                                    val brand = food.brand?.let { " · $it" } ?: ""
                                    Text("${food.caloriesPer100g.toInt()} kcal/100g$brand")
                                },
                                leadingContent = { Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) },
                                trailingContent = {
                                    IconButton(onClick = { vm.toggleFavorite(food) }) {
                                        Icon(Icons.Default.Favorite, "Favorit entfernen", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                },
                                modifier = Modifier.clickable { selectedFood = food }
                            )
                            HorizontalDivider()
                        }
                    }
                }

                if (results.isEmpty() && query.length > 1 && !searching) {
                    Text("Keine Treffer — eigenes Lebensmittel anlegen?", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }

                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(results) { food ->
                        val isFav = food.favoriteKey() in favoriteKeys
                        ListItem(
                            headlineContent = { Text(food.name) },
                            supportingContent = {
                                val brand = food.brand?.let { " · $it" } ?: ""
                                Text("${food.caloriesPer100g.toInt()} kcal/100g$brand")
                            },
                            trailingContent = {
                                IconButton(onClick = { vm.toggleFavorite(food) }) {
                                    Icon(
                                        if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        if (isFav) "Favorit entfernen" else "Als Favorit speichern",
                                        tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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
                val food = selectedFood!!
                val isFav = food.favoriteKey() in favoriteKeys
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Column {
                        Text(food.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        food.brand?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = { vm.toggleFavorite(food) }) {
                        Icon(
                            if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            if (isFav) "Favorit entfernen" else "Als Favorit speichern",
                            tint = if (isFav) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
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
                val cals  = food.caloriesPer100g * grams / 100f
                if (grams > 0) {
                    Spacer(Modifier.height(8.dp))
                    // Macro summary
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("${cals.toInt()} kcal", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text("P ${(food.proteinPer100g * grams / 100f).toInt()}g", fontSize = 13.sp)
                        Text("K ${(food.carbsPer100g * grams / 100f).toInt()}g", fontSize = 13.sp)
                        Text("F ${(food.fatPer100g * grams / 100f).toInt()}g", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selectedFood = null }, Modifier.weight(1f)) { Text("Zurück") }
                    Button(onClick = { if (grams > 0) { vm.addEntry(food, grams, selectedMeal); onDismiss() } },
                        modifier = Modifier.weight(1f), enabled = grams > 0) { Text("Hinzufügen") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
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
                DropdownMenuItem(text = { Text(meal.label()) }, onClick = { onSelect(meal); expanded = false })
            }
        }
    }
}

fun MealType.label() = when(this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
