package ch.nutrisnap.app.ui.screens.diary

@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MacroBar
import ch.nutrisnap.app.ui.components.SectionHeader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiaryScreen(vm: DiaryViewModel = viewModel()) {
    val state by vm.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<DiaryEntry?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddSheet = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Add, "Eintrag hinzufügen", tint = MaterialTheme.colorScheme.onPrimary) }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            item { DateNavigator(state.selectedDate, vm::prevDay, vm::nextDay) }
            item {
                MacroBar(
                    calories = state.totalCalories,
                    goal     = state.calorieGoal,
                    protein  = state.totalProtein,
                    carbs    = state.totalCarbs,
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
                            onDelete = { vm.deleteEntry(entry) },
                            onEdit   = { editingEntry = entry }
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddFoodSheet(vm = vm, onDismiss = { showAddSheet = false })
    }

    editingEntry?.let { entry ->
        EditEntrySheet(
            entry     = entry,
            onSave    = { newGrams -> vm.updateEntryAmount(entry, newGrams); editingEntry = null },
            onDismiss = { editingEntry = null }
        )
    }
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
        IconButton(
            onClick  = onNext,
            enabled  = date.isBefore(LocalDate.now())
        ) { Icon(Icons.Default.ChevronRight, "Nächster Tag") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiaryEntryRow(entry: DiaryEntry, onDelete: () -> Unit, onEdit: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .combinedClickable(onClick = {}, onLongClick = { onEdit() }),
        shape  = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.foodName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                val subText = buildString {
                    if (entry.amountGrams > 0f) append("${entry.amountGrams.toInt()} g  ·  ")
                    append("lang drücken zum Bearbeiten")
                }
                Text(subText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${entry.calories.toInt()} kcal", fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                Text("P ${entry.protein.toInt()}  K ${entry.carbs.toInt()}  F ${entry.fat.toInt()}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title            = { Text("Eintrag löschen?") },
            text             = { Text(entry.foodName) },
            confirmButton    = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } }
        )
    }
}

// ── Edit Entry Sheet ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(entry: DiaryEntry, onSave: (Float) -> Unit, onDismiss: () -> Unit) {
    var amountText by remember { mutableStateOf(entry.amountGrams.toInt().takeIf { it > 0 }?.toString() ?: "") }
    val grams = amountText.toFloatOrNull() ?: 0f

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Eintrag bearbeiten", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            Text(entry.foodName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))

            if (entry.amountGrams > 0f) {
                OutlinedTextField(
                    value           = amountText,
                    onValueChange   = { amountText = it },
                    label           = { Text("Menge (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                    singleLine      = true
                )
                if (grams > 0) {
                    val newCals = entry.calories * (grams / entry.amountGrams)
                    Spacer(Modifier.height(6.dp))
                    Text("= ${newCals.toInt()} kcal", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
            } else {
                // Recipe entry – show current kcal, allow scaling by servings
                Text("${entry.calories.toInt()} kcal", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Für Rezept-Einträge Kalorien direkt anpassen:", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value           = amountText,
                    onValueChange   = { amountText = it },
                    label           = { Text("Kalorien (kcal)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                    singleLine      = true
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Abbrechen") }
                Button(
                    onClick  = { if (grams > 0) onSave(grams) },
                    modifier = Modifier.weight(1f),
                    enabled  = grams > 0
                ) { Text("Speichern") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Add Food Bottom Sheet ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(vm: DiaryViewModel, onDismiss: () -> Unit) {
    var query        by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<FoodItem?>(null) }
    var amountText   by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    val results   by vm.searchResults.collectAsState()
    val searching by vm.isSearching.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Lebensmittel hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 12.dp))

            if (selectedFood == null) {
                OutlinedTextField(
                    value         = query,
                    onValueChange = { query = it; vm.searchFood(it) },
                    label         = { Text("Suchen…") },
                    leadingIcon   = { Icon(Icons.Default.Search, null) },
                    trailingIcon  = { if (searching) CircularProgressIndicator(Modifier.size(20.dp)) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true
                )
                Spacer(Modifier.height(8.dp))
                if (results.isEmpty() && query.length > 1 && !searching) {
                    Text("Keine Treffer — eigenes Lebensmittel anlegen?",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(results) { food ->
                        ListItem(
                            headlineContent   = { Text(food.name) },
                            supportingContent = {
                                Text("${food.caloriesPer100g.toInt()} kcal/100g" +
                                        (food.brand?.let { " · $it" } ?: ""))
                            },
                            modifier = Modifier.combinedClickable(onClick = { selectedFood = food })
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                val food = selectedFood!!
                Text(food.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                food.brand?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value           = amountText,
                        onValueChange   = { amountText = it },
                        label           = { Text("Menge (g)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier        = Modifier.weight(1f),
                        singleLine      = true
                    )
                    MealPicker(selected = selectedMeal) { selectedMeal = it }
                }
                val grams = amountText.toFloatOrNull() ?: 0f
                val cals  = food.caloriesPer100g * grams / 100f
                if (grams > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text("= ${cals.toInt()} kcal", fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selectedFood = null }, Modifier.weight(1f)) {
                        Text("Zurück")
                    }
                    Button(
                        onClick  = { if (grams > 0) { vm.addEntry(food, grams, selectedMeal); onDismiss() } },
                        modifier = Modifier.weight(1f),
                        enabled  = grams > 0
                    ) { Text("Hinzufügen") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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

fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
