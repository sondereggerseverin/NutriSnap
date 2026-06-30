package ch.nutrisnap.app.ui.screens.recipegen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun RecipeGeneratorScreen(vm: RecipeGeneratorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var showDiarySheet by remember { mutableStateOf(false) }

    // Snackbar wenn gespeichert
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.savedToDiary) {
        if (state.savedToDiary) {
            snackbarHostState.showSnackbar("Rezept ins Tagebuch eingetragen ✓")
            vm.clearSavedFlag()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Titel
            item {
                Text("KI-Rezeptgenerator",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold)
            }

            // Input + Button
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

            // Fehler
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

            // Generiertes Rezept
            state.recipe?.let { recipe ->
                item { RecipeResultCard(recipe = recipe, onAddToDiary = { showDiarySheet = true }) }
            }

            // History
            if (state.recipe == null && !state.isLoading && state.history.isNotEmpty()) {
                item {
                    Text("Letzte Rezepte", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                }
                items(state.history.take(5)) { entity ->
                    Card(Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(entity.title, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text("${entity.calories} kcal") },
                            leadingContent = { Icon(Icons.Default.History, null) }
                        )
                    }
                }
            }
        }
    }

    // Diary-Sheet
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
}

// ── Rezept-Karte ───────────────────────────────────────────────────────────────

@Composable
private fun RecipeResultCard(recipe: GeneratedRecipe, onAddToDiary: () -> Unit) {
    var checkedIngredients by remember(recipe) { mutableStateOf(setOf<Int>()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Titel + Meta
            Text(recipe.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (recipe.description.isNotBlank()) {
                Text(recipe.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))

            // Makro-Chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MacroChip("${recipe.calories} kcal", MaterialTheme.colorScheme.primaryContainer)
                MacroChip("P ${recipe.protein.toInt()}g", MaterialTheme.colorScheme.secondaryContainer)
                MacroChip("K ${recipe.carbs.toInt()}g", MaterialTheme.colorScheme.tertiaryContainer)
                MacroChip("F ${recipe.fat.toInt()}g", MaterialTheme.colorScheme.surfaceVariant)
            }
            Text("${recipe.servings} Port. · ${recipe.prepTimeMinutes} Min.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Zutaten mit Häkchen
            Text("Zutaten", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
            recipe.ingredients.forEachIndexed { i, ingredient ->
                Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = i in checkedIngredients,
                        onCheckedChange = { checked ->
                            checkedIngredients = if (checked) checkedIngredients + i else checkedIngredients - i
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        ingredient, fontSize = 14.sp,
                        color = if (i in checkedIngredients)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // Zubereitungsschritte
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

            // "Zum Tagebuch" Button
            Button(
                onClick = onAddToDiary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Zum Tagebuch hinzufügen")
            }
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

            // Portionen
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

            // Mahlzeit wählen
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
