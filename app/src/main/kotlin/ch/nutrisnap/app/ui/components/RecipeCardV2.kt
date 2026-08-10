package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
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
import coil.compose.AsyncImage

/** Portionen, die man aus der Liste tracken will (nicht recipe.servings!). */
private val PORTION_STEPS = listOf(1f, 1.5f, 2f, 3f)

/**
 * Kompakte Rezeptkarte für die Übersicht (viele Einträge).
 * Horizontal: Thumbnail links, Infos rechts. Makros immer pro Portion.
 * Chips 1 / 1.5 / 2 / 3 skalieren die Anzeige fürs Tracking.
 * Menü: Kopieren (zum Anpassen) + Löschen.
 */
@Composable
fun RecipeCardV2(
    recipe: Recipe,
    onClick: () -> Unit,
    onAddToDiary: (portions: Float) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var portions by remember(recipe.id) { mutableFloatStateOf(1f) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val baseServings = recipe.servings.coerceAtLeast(1)
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
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail links
            RecipeCardImage(
                recipe = recipe,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            Spacer(Modifier.width(12.dp))

            // Inhalt rechts
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        recipe.displayTitle(),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Mehr",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (recipe.isFavorite) "Favorit entfernen" else "Als Favorit")
                                },
                                onClick = {
                                    menuOpen = false
                                    onToggleFavorite()
                                },
                                leadingIcon = {
                                    Icon(
                                        if (recipe.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = if (recipe.isFavorite) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Kopieren zum Anpassen") },
                                onClick = {
                                    menuOpen = false
                                    onDuplicate()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Löschen", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    showDeleteConfirm = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // kcal + Makros in einer Zeile
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    kcal?.let {
                        Text(
                            "${it.toInt()} kcal",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MacroColors.calories
                        )
                    }
                    protein?.let { MacroInline("P", it, MacroColors.protein) }
                    fat?.let { MacroInline("F", it, MacroColors.fat) }
                    carbs?.let { MacroInline("KH", it, MacroColors.carbs) }
                    if (fiber != null && fiber > 0f) {
                        MacroInline("B", fiber, MacroColors.fiber)
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Portionen + Add
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PORTION_STEPS.forEach { step ->
                            val selected = portions == step
                            Surface(
                                onClick = { portions = step },
                                shape = RoundedCornerShape(6.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(width = 34.dp, height = 26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        if (step == step.toInt().toFloat()) step.toInt().toString()
                                        else step.toString(),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    FilledIconButton(
                        onClick = { onAddToDiary(portions) },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = "Ins Tagebuch",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Rezept löschen?") },
            text = { Text(recipe.displayTitle()) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun MacroInline(label: String, value: Float, color: Color) {
    Text(
        "${value.toInt()}$label",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color
    )
}

@Composable
fun RecipeCardImage(recipe: Recipe, modifier: Modifier = Modifier) {
    var loadFailed by remember(recipe.imageUrl) { mutableStateOf(false) }
    val url = recipe.imageUrl
    val showImage = !url.isNullOrBlank() && !loadFailed

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (showImage) {
            AsyncImage(
                model = if (url.startsWith("file://") || (url.startsWith("/") && !url.startsWith("http"))) {
                    val f = java.io.File(url.removePrefix("file://"))
                    if (f.exists()) f else url
                } else url,
                contentDescription = recipe.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { loadFailed = true }
            )
        } else {
            // Sichtbarer Fallback statt leerer dunkler Fläche
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color(0xFF2D6A4F).copy(alpha = 0.85f),
                                Color(0xFF40916C).copy(alpha = 0.7f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RestaurantMenu,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}
