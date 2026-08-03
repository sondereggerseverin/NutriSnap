package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

/** Portionen, die man aus der Liste tracken will (nicht recipe.servings!). */
private val PORTION_STEPS = listOf(1f, 1.5f, 2f, 3f)

/**
 * Rezeptkarte: immer **pro Portion** als Basis.
 * Chips 1 / 1.5 / 2 / 3 = „so viele Portionen tracken“, skaliert von proteinPerServing
 * bzw. totalCalories/servings — nie mehr versehentlich die Gesamt-Rezept-kcal.
 */
@Composable
fun RecipeCardV2(
    recipe: Recipe,
    onClick: () -> Unit,
    onAddToDiary: (portions: Float) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Wie viele Portionen der User tracken will (Default: 1)
    var portions by remember(recipe.id) { mutableFloatStateOf(1f) }
    var showConfirm by remember { mutableStateOf(false) }

    val baseServings = recipe.servings.coerceAtLeast(1)
    // Pro-Portion-Werte (proteinPerServing ist bereits pro Portion)
    val kcalPer = recipe.totalCalories?.div(baseServings)
    val protPer = recipe.proteinPerServing
    val fatPer = recipe.fatPerServing
    val carbsPer = recipe.carbsPerServing
    val fiberPer = recipe.fiberPerServing

    val kcal = kcalPer?.times(portions)
    val protein = protPer?.times(portions)
    val fat = fatPer?.times(portions)
    val carbs = carbsPer?.times(portions)
    val fiber = fiberPer?.times(portions)

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.xl),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            RecipeCardImage(recipe, modifier = Modifier.fillMaxWidth().height(160.dp))

            Column(Modifier.padding(NutriSpacing.md)) {
                Text(
                    recipe.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (baseServings > 1) {
                    Text(
                        "Rezept: $baseServings Portionen · Anzeige pro Port.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(Modifier.height(NutriSpacing.sm))

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        // Portionen-Auswahl nur zum Tracken
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PORTION_STEPS.forEach { step ->
                                val selected = portions == step
                                Surface(
                                    onClick = { portions = step },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(width = 40.dp, height = 32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            if (step == step.toInt().toFloat()) step.toInt().toString()
                                            else step.toString(),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(NutriSpacing.sm))

                        kcal?.let {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    "${it.toInt()} kcal",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = MacroColors.calories
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (portions == 1f) "/ Port." else "· ${fmtPortions(portions)} Port.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 3.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
                            protein?.let { MacroCompact("P", it, MacroColors.protein) }
                            fat?.let { MacroCompact("F", it, MacroColors.fat) }
                            carbs?.let { MacroCompact("KH", it, MacroColors.carbs) }
                            fiber?.let { MacroCompact("Balla.", it, MacroColors.fiber) }
                        }
                    }

                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledIconButton(
                            onClick = { onAddToDiary(portions) },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = "Ins Tagebuch")
                        }
                        // Link/Detail bleibt über onClick auf der Karte
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Rezept löschen?") },
            text = { Text(recipe.title) },
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

private fun fmtPortions(p: Float): String =
    if (p == p.toInt().toFloat()) p.toInt().toString() else p.toString()

@Composable
private fun MacroCompact(label: String, value: Float, color: Color) {
    Column {
        Text(
            "${value.toInt()}g",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = color
        )
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RecipeCardImage(recipe: Recipe, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(topStart = NutriRadius.xl, topEnd = NutriRadius.xl))) {
        if (!recipe.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = recipe.imageUrl,
                contentDescription = recipe.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RestaurantMenu,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
        // Gradient am unteren Bildrand für Lesbarkeit
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f))
                    )
                )
        )
    }
}
