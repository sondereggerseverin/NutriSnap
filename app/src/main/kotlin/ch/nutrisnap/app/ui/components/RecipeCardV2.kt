package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import coil.compose.AsyncImage

private val PORTION_STEPS = listOf(1f, 1.5f, 2f, 3f)

/**
 * Rezeptkarte im FreshBatch-Stil: großes Bild, prominenter Portionen-Multiplier,
 * kompakte Makro-Zeile inkl. Ballaststoffe. Die detaillierte Nährwert-Ansicht
 * (Vitamine, Mineralstoffe) bleibt Teil des bestehenden RecipeDetailSheet und
 * wird hier bewusst nicht dupliziert.
 */
@Composable
fun RecipeCardV2(
    recipe: Recipe,
    onClick: () -> Unit,
    onAddToDiary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    initialServings: Float = recipe.servings.toFloat().coerceAtLeast(1f)
) {
    var servings by remember(recipe.id) { mutableStateOf(initialServings) }
    var showConfirm by remember { mutableStateOf(false) }
    val baseServings = recipe.servings.coerceAtLeast(1)
    val ratio = servings / baseServings.toFloat()

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            RecipeCardImage(recipe, modifier = Modifier.fillMaxWidth().height(160.dp))

            Column(Modifier.padding(NutriSpacing.lg)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        recipe.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    recipe.prepTimeMinutes?.let {
                        Text(
                            "⏱ $it min", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = NutriSpacing.sm, top = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(NutriSpacing.sm))
                PortionSelector(
                    servings = servings,
                    baseServings = baseServings,
                    onServingsChange = { servings = it }
                )

                Spacer(Modifier.height(NutriSpacing.md))
                RecipeMacroRow(recipe, ratio)

                Text(
                    "Die Makros gelten pro Portion",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = NutriSpacing.xs)
                )

                Spacer(Modifier.height(NutriSpacing.sm))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onAddToDiary, Modifier.size(36.dp)) {
                        Icon(Icons.Default.PlaylistAdd, "Ins Tagebuch", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onEdit, Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, "Bearbeiten", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showConfirm = true }, Modifier.size(36.dp)) {
                        Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(onDismissRequest = { showConfirm = false },
            title = { Text("Rezept löschen?") }, text = { Text(recipe.title) },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } })
    }
}

@Composable
private fun RecipeCardImage(recipe: Recipe, modifier: Modifier = Modifier) {
    val url = recipe.imageUrl
    var loadFailed by remember(url) { mutableStateOf(false) }

    if (!url.isNullOrBlank() && !loadFailed) {
        AsyncImage(
            model = url,
            contentDescription = recipe.title,
            modifier = modifier.clip(RoundedCornerShape(topStart = NutriRadius.xl, topEnd = NutriRadius.xl)),
            contentScale = ContentScale.Crop,
            onError = { loadFailed = true }
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(topStart = NutriRadius.xl, topEnd = NutriRadius.xl))
                .background(Brush.linearGradient(listOf(Color(0xFF2D6A4F), Color(0xFF40916C)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.RestaurantMenu, contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(40.dp)
            )
        }
    }
}

@Composable
private fun PortionSelector(servings: Float, baseServings: Int, onServingsChange: (Float) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
        PORTION_STEPS.forEach { step ->
            val selected = servings == step
            val label = if (step % 1f == 0f) "${step.toInt()}" else "%.1f".format(step)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(NutriRadius.sm))
                    .selectable(selected = selected, onClick = { onServingsChange(step) })
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = NutriSpacing.md, vertical = 6.dp)
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RecipeMacroRow(recipe: Recipe, ratio: Float) {
    val baseServings = recipe.servings.coerceAtLeast(1)
    val kcal = recipe.totalCalories?.let { it / baseServings * ratio }
    val protein = recipe.proteinPerServing?.let { it * ratio }
    val fat = recipe.fatPerServing?.let { it * ratio }
    val carbs = recipe.carbsPerServing?.let { it * ratio }
    val fiber = recipe.fiberPerServing?.let { it * ratio }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NutriSpacing.md)
    ) {
        protein?.let { MacroCompact("P", it, "g", MacroColors.protein) }
        kcal?.let { MacroCompact("", it, "kcal", MacroColors.calories) }
        fat?.let { MacroCompact("F", it, "g", MacroColors.fat) }
        carbs?.let { MacroCompact("KH", it, "g", MacroColors.carbs) }
        fiber?.let { MacroCompact("Balla.", it, "g", MacroColors.fiber) }
    }
}

@Composable
private fun MacroCompact(label: String, value: Float, unit: String, color: Color) {
    Column {
        Text(
            "${value.toInt()}$unit",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = color
        )
        if (label.isNotBlank()) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
