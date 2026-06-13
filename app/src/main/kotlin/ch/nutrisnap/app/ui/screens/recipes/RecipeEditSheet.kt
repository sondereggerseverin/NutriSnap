package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import ch.nutrisnap.app.data.model.Recipe

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditSheet(
    recipe: Recipe,
    onSave: (Recipe) -> Unit,
    onDismiss: () -> Unit
) {
    var title        by remember { mutableStateOf(recipe.title) }
    var description  by remember { mutableStateOf(recipe.description
        .lines().filterNot { it.startsWith("📊") }.joinToString("\n").trim()) }
    var ingredients  by remember { mutableStateOf(recipe.ingredients) }
    var instructions by remember { mutableStateOf(recipe.instructions) }
    var servingsText by remember { mutableStateOf(recipe.servings.toString()) }
    var prepText     by remember { mutableStateOf(recipe.prepTimeMinutes?.toString() ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.96f)) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                Text("Rezept bearbeiten", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = {
                    val servings = servingsText.toIntOrNull()?.coerceAtLeast(1) ?: recipe.servings
                    val prep     = prepText.toIntOrNull()?.takeIf { it > 0 }
                    // Rebuild description: keep 📊 macro line if it was there
                    val macroLine = recipe.description.lines().firstOrNull { it.startsWith("📊") } ?: ""
                    val newDesc   = buildString {
                        if (description.isNotBlank()) append(description.trim())
                        if (macroLine.isNotBlank()) {
                            if (isNotEmpty()) append("\n\n")
                            append(macroLine)
                        }
                    }
                    onSave(recipe.copy(
                        title           = title.ifBlank { recipe.title },
                        description     = newDesc,
                        ingredients     = ingredients,
                        instructions    = instructions,
                        servings        = servings,
                        prepTimeMinutes = prep
                    ))
                }) {
                    Text("Speichern", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value         = title,
                        onValueChange = { title = it },
                        label         = { Text("Titel") },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        leadingIcon   = { Icon(Icons.Default.Title, null) }
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value         = servingsText,
                            onValueChange = { servingsText = it },
                            label         = { Text("Portionen") },
                            modifier      = Modifier.weight(1f),
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon   = { Icon(Icons.Default.People, null, Modifier.size(18.dp)) }
                        )
                        OutlinedTextField(
                            value         = prepText,
                            onValueChange = { prepText = it },
                            label         = { Text("Zeit (min)") },
                            modifier      = Modifier.weight(1f),
                            singleLine    = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon   = { Icon(Icons.Default.Timer, null, Modifier.size(18.dp)) }
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value         = description,
                        onValueChange = { description = it },
                        label         = { Text("Beschreibung") },
                        modifier      = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                        maxLines      = 4
                    )
                }
                item {
                    Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Eine Zutat pro Zeile. Format: Menge + Einheit + Name",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("z.B. \"500g Chicken Breast\" oder \"2 Tsp Salt\"",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value         = ingredients,
                        onValueChange = { ingredients = it },
                        label         = { Text("Zutaten") },
                        modifier      = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        maxLines      = 30
                    )
                }
                item {
                    Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value         = instructions,
                        onValueChange = { instructions = it },
                        label         = { Text("Zubereitung") },
                        modifier      = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines      = 20
                    )
                }
                item {
                    Button(
                        onClick  = {
                            val servings = servingsText.toIntOrNull()?.coerceAtLeast(1) ?: recipe.servings
                            val prep     = prepText.toIntOrNull()?.takeIf { it > 0 }
                            val macroLine = recipe.description.lines().firstOrNull { it.startsWith("📊") } ?: ""
                            val newDesc   = buildString {
                                if (description.isNotBlank()) append(description.trim())
                                if (macroLine.isNotBlank()) { if (isNotEmpty()) append("\n\n"); append(macroLine) }
                            }
                            onSave(recipe.copy(
                                title = title.ifBlank { recipe.title }, description = newDesc,
                                ingredients = ingredients, instructions = instructions,
                                servings = servings, prepTimeMinutes = prep
                            ))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Speichern")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
