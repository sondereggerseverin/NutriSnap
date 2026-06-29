package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.RecipeIngredient
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeGeneratorScreen(
    vm: RecipeGeneratorViewModel = viewModel(),
    sharedUrl: String? = null
) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf(sharedUrl ?: "") }
    var showActionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            input = sharedUrl
            vm.generate(sharedUrl)
        }
    }

    LaunchedEffect(state.addedToDiary, state.savedAsRecipe) {
        if (state.addedToDiary || state.savedAsRecipe) {
            kotlinx.coroutines.delay(2500)
            vm.clearAddedToDiary()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "KI-Rezeptgenerator",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Was möchtest du essen?") },
            placeholder = { Text("z.B. Schnelles Hähnchen mit Reis") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2, maxLines = 4
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { vm.generate(input) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isLoading && input.isNotBlank()
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(
                    Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("Generiere Rezept...")
            } else {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rezept generieren")
            }
        }

        // Status banners
        if (state.addedToDiary || state.savedAsRecipe) {
            Spacer(Modifier.height(8.dp))
            val msg = when {
                state.savedAndTracked -> "Ins Tagebuch & Rezepte gespeichert ✓"
                state.addedToDiary   -> "Ins Tagebuch hinzugefügt ✓"
                state.savedAsRecipe  -> "Als Rezept gespeichert ✓"
                else -> ""
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(8.dp))
                    Text(msg, color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::clearError) { Icon(Icons.Default.Close, null) }
                }
            }
        }

        state.recipe?.let { recipe ->
            Spacer(Modifier.height(16.dp))
            RecipeResultCard(
                recipe = recipe,
                editableIngredients = state.editableIngredients,
                onUpdateIngredient = vm::updateIngredient,
                onRemoveIngredient = vm::removeIngredient,
                onAddIngredient = vm::addIngredient,
                onShowActionDialog = { showActionDialog = true }
            )
        }

        if (state.recipe == null && !state.isLoading && state.history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Letzte Rezepte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            state.history.take(5).forEach { entity ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    onClick = { vm.loadFromHistory(entity) }
                ) {
                    ListItem(
                        headlineContent = { Text(entity.title, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text("${entity.calories} kcal · ${entity.servings} Port.") },
                        leadingContent = { Icon(Icons.Default.History, null) },
                        trailingContent = { Icon(Icons.Default.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }
            }
        }
    }

    // Action Dialog (Track / Speichern / Beides)
    if (showActionDialog) {
        state.recipe?.let {
            RecipeActionDialog(
                recipeTitle = it.title,
                servings = it.servings,
                onTrack = { s, meal, date -> vm.addToDiary(s, meal, date); showActionDialog = false },
                onSave = { vm.saveAsRecipe(); showActionDialog = false },
                onBoth = { s, meal, date -> vm.addToDiaryAndSave(s, meal, date); showActionDialog = false },
                onDismiss = { showActionDialog = false }
            )
        }
    }
}

// ── Action Dialog ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeActionDialog(
    recipeTitle: String,
    servings: Int,
    onTrack: (Float, MealType, LocalDate) -> Unit,
    onSave: () -> Unit,
    onBoth: (Float, MealType, LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var servingsText by remember { mutableStateOf("1") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var mealExpanded by remember { mutableStateOf(false) }
    var dateExpanded by remember { mutableStateOf(false) }

    val servingsVal = servingsText.toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Was möchtest du tun?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(recipeTitle, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Portionen
                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { servingsText = it },
                    label = { Text("Portionen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )

                // Mahlzeit
                Box {
                    OutlinedButton(onClick = { mealExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(selectedMeal.label())
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = mealExpanded, onDismissRequest = { mealExpanded = false }) {
                        MealType.values().forEach { meal ->
                            DropdownMenuItem(
                                text = { Text(meal.label()) },
                                onClick = { selectedMeal = meal; mealExpanded = false }
                            )
                        }
                    }
                }

                // Datum (Heute / Morgen / Übermorgen)
                Box {
                    OutlinedButton(onClick = { dateExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(selectedDate.dateLabel())
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = dateExpanded, onDismissRequest = { dateExpanded = false }) {
                        val today = LocalDate.now()
                        listOf(today, today.plusDays(1), today.plusDays(2)).forEach { d ->
                            DropdownMenuItem(
                                text = { Text(d.dateLabel()) },
                                onClick = { selectedDate = d; dateExpanded = false }
                            )
                        }
                    }
                }

                // 3 Action Buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onTrack(servingsVal, selectedMeal, selectedDate) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PlaylistAdd, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nur tracken")
                    }
                    OutlinedButton(
                        onClick = { onSave() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Nur als Rezept speichern")
                    }
                    Button(
                        onClick = { onBoth(servingsVal, selectedMeal, selectedDate) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Done, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tracken & speichern")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

// ── Recipe Result Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecipeResultCard(
    recipe: ch.nutrisnap.app.domain.GeneratedRecipe,
    editableIngredients: List<RecipeIngredient>,
    onUpdateIngredient: (Int, RecipeIngredient) -> Unit,
    onRemoveIngredient: (Int) -> Unit,
    onAddIngredient: (RecipeIngredient) -> Unit,
    onShowActionDialog: () -> Unit
) {
    // Compute live totals from editable ingredients
    val totalCalPerServing = if (editableIngredients.isEmpty()) recipe.calories
        else editableIngredients.sumOf { it.calories } / recipe.servings.coerceAtLeast(1)
    val totalProtPerServing = if (editableIngredients.isEmpty()) recipe.protein
        else (editableIngredients.sumOf { it.protein.toDouble() } / recipe.servings.coerceAtLeast(1)).toFloat()
    val totalCarbsPerServing = if (editableIngredients.isEmpty()) recipe.carbs
        else (editableIngredients.sumOf { it.carbs.toDouble() } / recipe.servings.coerceAtLeast(1)).toFloat()

    var showAddIngredient by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header — read-only
            Text(recipe.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (recipe.description.isNotBlank()) {
                Text(
                    recipe.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroChip("$totalCalPerServing kcal", MaterialTheme.colorScheme.primaryContainer)
                MacroChip("P ${totalProtPerServing.toInt()}g", MaterialTheme.colorScheme.secondaryContainer)
                MacroChip("K ${totalCarbsPerServing.toInt()}g", MaterialTheme.colorScheme.tertiaryContainer)
            }
            Text(
                "${recipe.servings} Port. · ${recipe.prepTimeMinutes} Min.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(12.dp))

            // ── Action Button ──────────────────────────────────────────────────
            Button(
                onClick = onShowActionDialog,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.PlaylistAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Tracken / Speichern", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))

            // ── Editierbare Zutaten ───────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                IconButton(onClick = { showAddIngredient = true }) {
                    Icon(Icons.Default.Add, "Zutat hinzufügen", tint = MaterialTheme.colorScheme.primary)
                }
            }

            editableIngredients.forEachIndexed { i, ing ->
                IngredientRow(
                    ingredient = ing,
                    onEdit = { editingIndex = i },
                    onDelete = { onRemoveIngredient(i) }
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Zubereitung — read-only ───────────────────────────────────────
            Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            recipe.steps.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        "${i + 1}.",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(step, fontSize = 14.sp)
                }
            }
        }
    }

    // Edit ingredient dialog
    editingIndex?.let { idx ->
        if (idx in editableIngredients.indices) {
            IngredientEditDialog(
                ingredient = editableIngredients[idx],
                onConfirm = { updated -> onUpdateIngredient(idx, updated); editingIndex = null },
                onDismiss = { editingIndex = null }
            )
        }
    }

    // Add ingredient dialog
    if (showAddIngredient) {
        IngredientEditDialog(
            ingredient = RecipeIngredient(),
            isNew = true,
            onConfirm = { onAddIngredient(it); showAddIngredient = false },
            onDismiss = { showAddIngredient = false }
        )
    }
}

@Composable
private fun IngredientRow(
    ingredient: RecipeIngredient,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${ingredient.amount} ${ingredient.name}".trim(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            if (ingredient.calories > 0) {
                Text(
                    "${ingredient.calories} kcal · P${ingredient.protein.toInt()}g · K${ingredient.carbs.toInt()}g · F${ingredient.fat.toInt()}g",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Edit, "Bearbeiten", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Delete, "Löschen", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun IngredientEditDialog(
    ingredient: RecipeIngredient,
    isNew: Boolean = false,
    onConfirm: (RecipeIngredient) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(ingredient.name) }
    var amount by remember { mutableStateOf(ingredient.amount) }
    var cal by remember { mutableStateOf(if (ingredient.calories > 0) ingredient.calories.toString() else "") }
    var prot by remember { mutableStateOf(if (ingredient.protein > 0) ingredient.protein.toInt().toString() else "") }
    var carbs by remember { mutableStateOf(if (ingredient.carbs > 0) ingredient.carbs.toInt().toString() else "") }
    var fat by remember { mutableStateOf(if (ingredient.fat > 0) ingredient.fat.toInt().toString() else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Zutat hinzufügen" else "Zutat bearbeiten", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it },
                    label = { Text("Menge (z.B. 200g)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cal, onValueChange = { cal = it },
                        label = { Text("kcal") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = prot, onValueChange = { prot = it },
                        label = { Text("Prot g") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = carbs, onValueChange = { carbs = it },
                        label = { Text("Carbs g") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = fat, onValueChange = { fat = it },
                        label = { Text("Fett g") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(RecipeIngredient(
                        name = name.trim(),
                        amount = amount.trim(),
                        calories = cal.toIntOrNull() ?: 0,
                        protein = prot.toFloatOrNull() ?: 0f,
                        carbs = carbs.toFloatOrNull() ?: 0f,
                        fat = fat.toFloatOrNull() ?: 0f
                    ))
                },
                enabled = name.isNotBlank()
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun MacroChip(text: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}

private fun LocalDate.dateLabel(): String {
    val today = LocalDate.now()
    return when (this) {
        today            -> "Heute"
        today.plusDays(1) -> "Morgen"
        today.plusDays(2) -> "Übermorgen"
        else -> toString()
    }
}
