package ch.nutrisnap.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.platform.LocalContext
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_TOGGLE_TOUCH_RECIPE_MENU
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import coil.compose.AsyncImage
import coil.request.ImageRequest

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
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val largerMenu = prefs?.get(KEY_TOGGLE_TOUCH_RECIPE_MENU) ?: true
    val menuBtnSize = if (largerMenu) 40.dp else 28.dp
    val menuIconSize = if (largerMenu) 22.dp else 18.dp

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
                            modifier = Modifier.size(menuBtnSize)
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Mehr",
                                modifier = Modifier.size(menuIconSize),
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
                                        if (recipe.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
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
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
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
                                        Icons.Filled.DeleteOutline,
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
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            Icons.Filled.PlaylistAdd,
                            contentDescription = "Ins Tagebuch",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
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

/**
 * Foto-Kachel für das 2/3-Spalten-Grid: Bild oben in fester 4:3-Ratio (passt zu den
 * meisten importierten Fotos – 1:1 oder 4:3 Querformat – und croppt dadurch konsistent,
 * unabhängig von Bildschirmgrösse/Spaltenzahl), Titel + kcal pro Portion darunter auf
 * Kartenfläche statt als Overlay – bleibt lesbar egal wie hell/dunkel das Foto ist und
 * ergibt eine einheitliche Kachelhöhe. Aktionen über Overflow-Menü und schnellem „+ Tagebuch“.
 */
@Composable
fun RecipeGridCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onAddToDiary: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    /** Grössere Kacheln mit mehr Abstand/Schrift (Einstellungen) vs. kompakt (Standard). */
    density: Int = 6,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val baseServings = recipe.servings.coerceAtLeast(1)
    val kcalPer = recipe.totalCalories?.div(baseServings)
    val protPer = recipe.proteinPerServing
    val carbsPer = recipe.carbsPerServing
    val fatPer = recipe.fatPerServing
    // 4 = größere Kacheln (mehr Details), 6 = kompakt (Standard)
    val largeTiles = density <= 4
    val titleSize = if (largeTiles) 14.sp else 13.sp
    val titleLineHeight = if (largeTiles) 18.sp else 16.sp
    val kcalSize = if (largeTiles) 13.sp else 12.sp
    val macroSize = if (largeTiles) 11.sp else 10.sp
    val contentPad = if (largeTiles) 10.dp else 8.dp
    val btnSize = if (largeTiles) 30.dp else 26.dp
    val btnIconSize = if (largeTiles) 16.dp else 14.dp

    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Foto: feste 4:3-Ratio statt bildschirmabhängiger Höhe → gleichmässiger Crop
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                RecipeCardImage(
                    recipe = recipe,
                    modifier = Modifier.fillMaxSize()
                )
                if (recipe.isFavorite) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(btnSize)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.38f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(btnIconSize),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledIconButton(
                        onClick = onAddToDiary,
                        modifier = Modifier.size(btnSize),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            Icons.Filled.PlaylistAdd,
                            contentDescription = "Ins Tagebuch",
                            modifier = Modifier.size(btnIconSize),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Box {
                        FilledIconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(btnSize),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.38f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "Mehr",
                                modifier = Modifier.size(btnIconSize)
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
                                        if (recipe.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                        null,
                                        Modifier.size(18.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Bearbeiten") },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.RestaurantMenu, null, Modifier.size(18.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Kopieren zum Anpassen") },
                                onClick = {
                                    menuOpen = false
                                    onDuplicate()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
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
                                        Icons.Filled.DeleteOutline,
                                        null,
                                        Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            }

            // Info-Block auf Kartenfläche (nicht auf dem Foto) – immer lesbar, immer gleich hoch
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = contentPad, vertical = contentPad)
            ) {
                Text(
                    recipe.displayTitle(),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = titleSize,
                    lineHeight = titleLineHeight,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (largeTiles) 8.dp else 6.dp)
                ) {
                    if (kcalPer != null) {
                        Text(
                            "${kcalPer.toInt()} kcal",
                            fontWeight = FontWeight.Bold,
                            fontSize = kcalSize,
                            color = MacroColors.calories,
                            maxLines = 1
                        )
                        protPer?.takeIf { it > 0f }?.let {
                            Text(
                                "${it.toInt()}P",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = macroSize,
                                color = MacroColors.protein,
                                maxLines = 1
                            )
                        }
                        carbsPer?.takeIf { it > 0f }?.let {
                            Text(
                                "${it.toInt()}KH",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = macroSize,
                                color = MacroColors.carbs,
                                maxLines = 1
                            )
                        }
                        fatPer?.takeIf { it > 0f }?.let {
                            Text(
                                "${it.toInt()}F",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = macroSize,
                                color = MacroColors.fat,
                                maxLines = 1
                            )
                        }
                    } else {
                        Text(
                            "Keine Angabe",
                            fontSize = macroSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1
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
fun RecipeCardImage(recipe: Recipe, modifier: Modifier = Modifier) {
    var loadFailed by remember(recipe.imageUrl) { mutableStateOf(false) }
    val url = recipe.imageUrl
    val showImage = !url.isNullOrBlank() && !loadFailed
    val context = LocalContext.current

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (showImage) {
            // Thumbnails auf ~400px begrenzen – volle Rezeptfotos sonst zu teuer fürs Grid
            val data: Any = if (url.startsWith("file://") || (url.startsWith("/") && !url.startsWith("http"))) {
                val f = java.io.File(url.removePrefix("file://"))
                if (f.exists()) f else url
            } else url
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(data)
                    .size(400)
                    .crossfade(false)
                    .build(),
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
                    Icons.Filled.RestaurantMenu,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}
