package ch.nutrisnap.app.ui.screens.recipes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.Recipe
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditSheet(
    recipe: Recipe,
    onSave: (Recipe) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var title        by remember { mutableStateOf(recipe.title) }
    var description  by remember { mutableStateOf(recipe.description
        .lines().filterNot { it.startsWith("📊") }.joinToString("\n").trim()) }
    var ingredients  by remember { mutableStateOf(recipe.ingredients) }
    var instructions by remember { mutableStateOf(recipe.instructions) }
    var servingsText by remember { mutableStateOf(recipe.servings.toString()) }
    var prepText     by remember { mutableStateOf(recipe.prepTimeMinutes?.toString() ?: "") }

    // Photo state — starts with existing imageUrl, can be replaced with local URI
    var imageUri     by remember { mutableStateOf<Uri?>(recipe.imageUrl?.let { Uri.parse(it) }) }
    var imageUrl     by remember { mutableStateOf(recipe.imageUrl) } // keeps remote URL if not replaced

    // Gallery picker
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read permission so the URI stays valid across restarts
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            imageUri = uri
            imageUrl = uri.toString() // overwrite — local URI used as imageUrl
        }
    }

    fun buildSaved(): Recipe {
        val servings  = servingsText.toIntOrNull()?.coerceAtLeast(1) ?: recipe.servings
        val prep      = prepText.toIntOrNull()?.takeIf { it > 0 }
        val macroLine = recipe.description.lines().firstOrNull { it.startsWith("📊") } ?: ""
        val newDesc   = buildString {
            if (description.isNotBlank()) append(description.trim())
            if (macroLine.isNotBlank()) { if (isNotEmpty()) append("\n\n"); append(macroLine) }
        }
        return recipe.copy(
            title           = title.ifBlank { recipe.title },
            description     = newDesc,
            ingredients     = ingredients,
            instructions    = instructions,
            servings        = servings,
            prepTimeMinutes = prep,
            imageUrl        = imageUrl
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.96f)) {
        Column(Modifier.fillMaxSize()) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                Text("Rezept bearbeiten", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = { onSave(buildSaved()) }) {
                    Text("Speichern", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                // ── Foto ─────────────────────────────────────────────────────
                item {
                    Text("Foto", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))

                    val currentUri = imageUri
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { photoPicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentUri != null) {
                            AsyncImage(
                                model = currentUri,
                                contentDescription = "Rezeptbild",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            // Overlay: tap to change
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.25f)),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .padding(8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.CameraAlt, null,
                                        tint = Color.White, modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Foto ändern", color = Color.White, fontSize = 13.sp)
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.AddPhotoAlternate, null,
                                    modifier = Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Foto aus Galerie wählen",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Remove photo button
                    if (currentUri != null) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { imageUri = null; imageUrl = null },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.DeleteOutline, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Foto entfernen", fontSize = 12.sp)
                        }
                    }
                }

                // ── Titel ────────────────────────────────────────────────────
                item {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Titel") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Title, null) }
                    )
                }

                // ── Portionen + Zeit ─────────────────────────────────────────
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = servingsText, onValueChange = { servingsText = it },
                            label = { Text("Portionen") }, modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon = { Icon(Icons.Default.People, null, Modifier.size(18.dp)) }
                        )
                        OutlinedTextField(
                            value = prepText, onValueChange = { prepText = it },
                            label = { Text("Zeit (min)") }, modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            leadingIcon = { Icon(Icons.Default.Timer, null, Modifier.size(18.dp)) }
                        )
                    }
                }

                // ── Beschreibung ──────────────────────────────────────────────
                item {
                    OutlinedTextField(
                        value = description, onValueChange = { description = it },
                        label = { Text("Beschreibung") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                        maxLines = 4
                    )
                }

                // ── Zutaten ───────────────────────────────────────────────────
                item {
                    Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Eine Zutat pro Zeile. Format: Menge + Einheit + Name",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("z.B. \"500g Chicken Breast\" oder \"2 Tsp Salt\"",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = ingredients, onValueChange = { ingredients = it },
                        label = { Text("Zutaten") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        maxLines = 30
                    )
                }

                // ── Zubereitung ───────────────────────────────────────────────
                item {
                    Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = instructions, onValueChange = { instructions = it },
                        label = { Text("Zubereitung") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        maxLines = 20
                    )
                }

                // ── Speichern Button ──────────────────────────────────────────
                item {
                    Button(
                        onClick = { onSave(buildSaved()) },
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
