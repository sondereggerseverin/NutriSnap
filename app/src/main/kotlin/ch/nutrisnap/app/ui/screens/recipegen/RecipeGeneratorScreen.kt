package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.GeneratedRecipeEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.GeneratedRecipe
import ch.nutrisnap.app.domain.RecipeIngredient
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeGeneratorScreen(vm: RecipeGeneratorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
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
                    }
                    Text("KI-Rezeptgenerator",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold)
                }
            }

            if (state.recipe == null) {
                item {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Was möchtest du essen?") },
                        placeholder = { Text("z.B. Schnelles Hähnchen mit Reis") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2, maxLines = 4,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.generate(input) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading && input.isNotBlank()
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(8.dp))
                            Text("Generiere Rezept…")
                        } else {
                            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Rezept generieren")
                        }
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
                        onUpdateIngredient = { i, ing -> vm.updateIngredient(i, ing) }
                    )
                }
            }

            if (state.recipe == null && !state.isLoading && state.history.isNotEmpty()) {
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

// ── Rezept-Karte ───────────────────────────────────────────────────────────────

@Composable
private fun RecipeResultCard(
    recipe: GeneratedRecipe,
    isSavingImage: Boolean = false,
    onAddToDiary: () -> Unit,
    onSaveAsRecipe: () -> Unit,
    onUpdate: (GeneratedRecipe) -> Unit,
    onRemoveIngredient: (Int) -> Unit,
    onUpdateIngredient: (Int, RecipeIngredient) -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(
                    onClick = { if (servings > 1) servings-- },
                    enabled = servings > 1
                ) { Icon(Icons.Default.Remove, null) }
                Text("$servings", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
