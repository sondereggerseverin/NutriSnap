package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_RATINGS
import coil.compose.AsyncImage
import java.io.File

// ── Recipe Card ───────────────────────────────────────────────────────────────
@Composable
internal fun RecipeCard(recipe: Recipe, onClick: () -> Unit, onDelete: () -> Unit,
    onAddToDiary: () -> Unit, onEdit: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    val incomplete = recipe.isIncomplete()
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val stars = recipeStarsFromPrefs(prefs?.get(KEY_RECIPE_RATINGS), recipe.id)
    // Fresh-Layout läuft über RecipeCardV2; hier nur Warnzeile + klassische Karte
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (incomplete) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                              else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(if (incomplete) 0.dp else 2.dp)
    ) {
        if (incomplete) {
            // Kompakte Darstellung für leere Web-Importe ohne Caption/Bild/Kalorien
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(recipe.displayTitle(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Unvollständig – Caption fehlt, tippe zum Ergänzen", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = { showConfirm = true }, Modifier.size(32.dp)) {
                    Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        } else {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RecipeThumbnail(recipe = recipe, size = 72.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(recipe.displayTitle(), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (stars > 0) {
                        Spacer(Modifier.height(2.dp))
                        RecipeStarsRow(stars)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recipe.platform?.let { PlatformChip(it) }
                        recipe.totalCalories?.let { MiniChip("🔥 ${(it/recipe.servings.coerceAtLeast(1)).toInt()} kcal/Port.") }
                        recipe.prepTimeMinutes?.let { MiniChip("⏱ $it min") }
                    }
                    Text("${recipe.servings} Port.", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
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
            title = { Text("Rezept löschen?") }, text = { Text(recipe.displayTitle()) },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } })
    }
}

@Composable internal fun PlatformChip(platform: String) {
    val (icon, label) = when (platform.lowercase()) {
        "instagram" -> Icons.Default.CameraAlt to "Instagram"
        "tiktok"    -> Icons.Default.VideoLibrary to "TikTok"
        "ki"        -> Icons.Default.AutoAwesome to "KI"
        "manual"    -> Icons.Default.Edit to "Frei"
        else        -> Icons.Default.Language to "Web"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

/**
 * Recipe image with a graceful fallback. If [Recipe.imageUrl] is set, shows it
 * via AsyncImage; otherwise shows a platform-tinted gradient with a fork/knife
 * icon, so cards never look "empty" the way a missing-image gap used to.
 */

/** Coil-Modell: lokale file://- oder Absolute-Pfade als File, sonst URL-String. */
internal fun coilModel(url: String): Any {
    val path = when {
        url.startsWith("file://") -> url.removePrefix("file://")
        url.startsWith("/") && !url.startsWith("http") -> url
        else -> return url
    }
    val f = java.io.File(path)
    return if (f.exists()) f else url
}

@Composable
internal fun RecipeThumbnail(
    recipe:   Recipe,
    modifier: Modifier = Modifier,
    size:     androidx.compose.ui.unit.Dp? = null,
    shape:    RoundedCornerShape = RoundedCornerShape(10.dp),
    onRetryImage: (() -> Unit)? = null,
    imageRefreshStatus: String? = null
) {
    val box = if (size != null) modifier.then(Modifier.size(size)) else modifier
    val url = recipe.imageUrl
    // Track load failure so CDN-expired/auth-gated TikTok URLs fall back to gradient
    var imageLoadFailed by remember(url) { mutableStateOf(false) }

    if (!url.isNullOrBlank() && !imageLoadFailed) {
        AsyncImage(
            model = coilModel(url), contentDescription = recipe.displayTitle(),
            modifier = box.clip(shape),
            contentScale = ContentScale.Crop,
            onError = { imageLoadFailed = true }
        )
    } else {
        val (gradientColors, icon) = platformVisuals(recipe.platform)
        Box(
            modifier = box.clip(shape).background(Brush.linearGradient(gradientColors)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size((size ?: 64.dp) * 0.35f))
                if (onRetryImage != null && !recipe.sourceUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    when (imageRefreshStatus) {
                        "loading" -> CircularProgressIndicator(
                            Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp
                        )
                        "fail" -> TextButton(onClick = onRetryImage) {
                            Text("Kein Bild – erneut", color = Color.White, fontSize = 11.sp)
                        }
                        else -> TextButton(onClick = onRetryImage) {
                            Text("Bild nachladen", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

internal fun platformVisuals(platform: String?): Pair<List<Color>, androidx.compose.ui.graphics.vector.ImageVector> =
    when (platform?.lowercase()) {
        "instagram" -> listOf(Color(0xFFFEDA77), Color(0xFFDC2743), Color(0xFF962FBF)) to Icons.Default.CameraAlt
        "tiktok"    -> listOf(Color(0xFF25F4EE), Color(0xFF000000), Color(0xFFFE2C55)) to Icons.Default.VideoLibrary
        "ki"        -> listOf(Color(0xFFFF9B45), Color(0xFFD9633B)) to Icons.Default.AutoAwesome
        "bild"      -> listOf(Color(0xFF5B8DEF), Color(0xFF3A6BC7)) to Icons.Default.Photo
        "manual"    -> listOf(Color(0xFF457B9D), Color(0xFF1D3557)) to Icons.Default.Edit
        else        -> listOf(Color(0xFF2D6A4F), Color(0xFF40916C)) to Icons.Default.RestaurantMenu
    }

@Composable internal fun MiniChip(text: String) =
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)



// (MicronutrientTable + MICRO_META wurden nach ui/components/Components.kt verschoben,
// damit sie auch von DiaryScreen für einzelne Tagebuch-Einträge genutzt werden können.)


