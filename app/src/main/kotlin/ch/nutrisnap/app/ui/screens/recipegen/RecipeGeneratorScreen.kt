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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.GeneratedRecipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeGeneratorScreen(vm: RecipeGeneratorViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }

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
            RecipeResultCard(recipe = recipe)
        }

        if (state.recipe == null && !state.isLoading && state.history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Letzte Rezepte", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            state.history.take(5).forEach { entity ->
                Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
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

@Composable
private fun RecipeResultCard(recipe: GeneratedRecipe) {
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
