package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.GeneratedRecipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeGeneratorScreen(
    vm: RecipeGeneratorViewModel = viewModel(),
    sharedUrl: String? = null
) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf(sharedUrl ?: "") }
    var showDiaryDialog by remember { mutableStateOf(false) }

    // Auto-generate when a URL is shared from Instagram/TikTok/etc.
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) {
            input = sharedUrl
            vm.generate(sharedUrl)
        }
    }

    // Reset snackbar after showing
    LaunchedEffect(state.addedToDiary) {
        if (state.addedToDiary) {
            kotlinx.coroutines.delay(2000)
            vm.clearAddedToDiary()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("KI-Rezeptgenerator",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp))

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Was moechtest du essen?") },
            placeholder = { Text("z.B. Schnelles Haehnchen mit Reis") },
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
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Generiere Rezept...")
            } else {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rezept generieren")
            }
        }

        // Success banner
        if (state.addedToDiary) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(
                containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f))) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                    Spacer(Modifier.width(8.dp))
                    Text("Ins Tagebuch hinzugefügt ✓",
                        color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
            }
        }

        state.error?.let { error ->
            Spacer(Modifier.height(8.dp))
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

        state.recipe?.let { recipe ->
            Spacer(Modifier.height(16.dp))
            RecipeResultCard(
                recipe = recipe,
                onAddToDiary = { showDiaryDialog = true }
            )
        }

        if (state.recipe == null && !state.isLoading && state.history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Letzte Rezepte", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
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
                        trailingContent = { Icon(Icons.Default.ArrowForward, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                }
            }
        }
    }

    // "Ins Tagebuch" dialog
    if (showDiaryDialog) {
        state.recipe?.let { recipe ->
            AddRecipeToDiaryDialog(
                recipe = recipe,
                onConfirm = { servings, meal ->
                    vm.addGeneratedRecipeToDiary(recipe, servings, meal)
                    showDiaryDialog = false
                },
                onDismiss = { showDiaryDialog = false }
            )
        }
    }
}

@Composable
private fun AddRecipeToDiaryDialog(
    recipe: GeneratedRecipe,
    onConfirm: (Float, MealType) -> Unit,
    onDismiss: () -> Unit
) {
    var servingsText by remember { mutableStateOf("1") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    var expanded by remember { mutableStateOf(false) }

    val servings = servingsText.toFloatOrNull() ?: 1f
    val factor = servings / recipe.servings.coerceAtLeast(1).toFloat()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ins Tagebuch", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(recipe.title, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { servingsText = it },
                    label = { Text("Portionen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )

                // Mahlzeit-Auswahl
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(selectedMeal.label())
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        MealType.values().forEach { meal ->
                            DropdownMenuItem(
                                text = { Text(meal.label()) },
                                onClick = { selectedMeal = meal; expanded = false }
                            )
                        }
                    }
                }

                // Macro preview
                if (servings > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${(recipe.calories * factor).toInt()} kcal",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        Text("P ${(recipe.protein * factor).toInt()}g", fontSize = 12.sp)
                        Text("K ${(recipe.carbs * factor).toInt()}g", fontSize = 12.sp)
                        Text("F ${(recipe.fat * factor).toInt()}g", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (servings > 0) onConfirm(servings, selectedMeal) },
                enabled = servings > 0
            ) { Text("Hinzufügen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun RecipeResultCard(recipe: GeneratedRecipe, onAddToDiary: () -> Unit) {
    var checkedIngredients by remember { mutableStateOf(setOf<Int>()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(recipe.title, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            if (recipe.description.isNotBlank()) {
                Text(recipe.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MacroChip("${recipe.calories} kcal", MaterialTheme.colorScheme.primaryContainer)
                MacroChip("P ${recipe.protein.toInt()}g", MaterialTheme.colorScheme.secondaryContainer)
                MacroChip("K ${recipe.carbs.toInt()}g", MaterialTheme.colorScheme.tertiaryContainer)
            }
            Text("${recipe.servings} Port. - ${recipe.prepTimeMinutes} Min.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(12.dp))

            // ── Ins Tagebuch Button ────────────────────────────────────────────
            Button(
                onClick = onAddToDiary,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.PlaylistAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ins Tagebuch", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(12.dp))
            Text("Zutaten", fontWeight = FontWeight.SemiBold)
            recipe.ingredients.forEachIndexed { i, ingredient ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = i in checkedIngredients,
                        onCheckedChange = { c ->
                            checkedIngredients = if (c) checkedIngredients + i else checkedIngredients - i
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(ingredient, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Zubereitung", fontWeight = FontWeight.SemiBold)
            recipe.steps.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 4.dp)) {
                    Text("${i + 1}.", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp))
                    Text(step, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun MacroChip(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
