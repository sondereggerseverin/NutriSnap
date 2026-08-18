package ch.nutrisnap.app.ui.screens.recipes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.ui.components.ComposeCropScreen
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditSheet(
    recipe: Recipe,
    onSave: (Recipe) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var title        by remember { mutableStateOf(recipe.displayTitle()) }
    var description  by remember { mutableStateOf(recipe.description
        .lines().filterNot { it.startsWith("📊") }.joinToString("\n").trim()) }
    var ingredients  by remember { mutableStateOf(recipe.ingredients) }
    var instructions by remember { mutableStateOf(recipe.instructions) }
    var servingsText by remember { mutableStateOf(recipe.servings.toString()) }
    var prepText     by remember { mutableStateOf(recipe.prepTimeMinutes?.toString() ?: "") }
    var category     by remember {
        mutableStateOf(
            recipe.category().takeUnless { recipe.mealCategory.isBlank() }
                ?: RecipeCategory.guess(recipe.title, recipe.ingredients, recipe.description)
        )
    }

    // Photo state — starts with existing imageUrl, can be replaced with local URI
    var imageUri     by remember { mutableStateOf<Uri?>(recipe.imageUrl?.let { Uri.parse(it) }) }
    var imageUrl     by remember { mutableStateOf(recipe.imageUrl) } // keeps remote URL if not replaced
    /** Galerie-URI → ComposeCropScreen (skaliert intern, Speichern-Button sichtbar). */
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }

    // Galerie → Crop-Screen (Skalierung passiert dort auf IO-Thread)
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingCropUri = uri
    }

    // Crop-Screen mit immer sichtbarem „Speichern“
    pendingCropUri?.let { cropUri ->
        ComposeCropScreen(
            imageUri = cropUri,
            title = "Foto zuschneiden",
            onCropped = { cropped ->
                imageUri = cropped
                imageUrl = cropped.toString()
                pendingCropUri = null
            },
            onCancel = { pendingCropUri = null }
        )
        return
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
            title           = title.ifBlank { recipe.displayTitle() },
            description     = newDesc,
            ingredients     = ingredients,
            instructions    = instructions,
            servings        = servings,
            prepTimeMinutes = prep,
            imageUrl        = imageUrl,
            mealCategory    = category.name
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
            Text(
                text = "Kategorie",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RecipeCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = {
                            Text(cat.emoji + " " + cat.label, style = MaterialTheme.typography.labelMedium)
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .imePadding()
                    .navigationBarsPadding(),
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
                            .clickable {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
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

/** Eine manuell erfasste Zutat (Menge in g + Name) fürs freie Erstellen. */
private data class ManualIngredientLine(
    val id: Long,
    val amountG: String = "",
    val name: String = ""
)

/**
 * Freies Rezept erstellen: Name, Portionen, Zutaten einzeln (Menge g + Name).
 * Speichert ohne Import/URL – Nährwerte können danach per „Verifizieren“ berechnet werden.
 *
 * Column+verticalScroll statt LazyColumn: bei Tastatur bleibt das Sheet stabil und
 * die Zutatenzeilen bleiben sichtbar (LazyColumn + ModalBottomSheet + IME war instabil).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualRecipeCreateSheet(
    onSave: (title: String, ingredients: String, instructions: String, servings: Int, mealCategory: String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var servingsText by remember { mutableStateOf("1") }
    var instructions by remember { mutableStateOf("") }
    var nextId by remember { mutableStateOf(1L) }
    var lines by remember {
        mutableStateOf(listOf(ManualIngredientLine(id = 0L)))
    }
    var category by remember { mutableStateOf(RecipeCategory.OTHER) }
    val scrollState = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun formatIngredients(): String = lines.mapNotNull { line ->
        val name = line.name.trim()
        if (name.isBlank()) return@mapNotNull null
        val amount = line.amountG.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
        if (amount != null) "${amount.toInt().coerceAtLeast(1)} g $name" else name
    }.joinToString("\n")

    val canSave = title.isNotBlank() && lines.any { it.name.isNotBlank() }

    fun doSave() {
        if (!canSave) return
        onSave(
            title.trim(),
            formatIngredients(),
            instructions.trim(),
            servingsText.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            category.name
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.96f)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                Text("Freies Rezept", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                TextButton(onClick = { doSave() }, enabled = canSave) {
                    Text("Speichern", fontWeight = FontWeight.Bold)
                }
            }
            HorizontalDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Ohne Link oder Foto – Name vergeben und Zutaten einzeln eintragen. " +
                        "Nährwerte danach über „Verifizieren“ berechnen.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Rezeptname") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Title, null) }
                )

                OutlinedTextField(
                    value = servingsText,
                    onValueChange = { servingsText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("Portionen") },
                    modifier = Modifier.fillMaxWidth(0.45f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.People, null, Modifier.size(18.dp)) }
                )

                Text("Kategorie", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RecipeCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = {
                                Text(
                                    cat.emoji + " " + cat.label,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }

                Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "Menge in g und Name – pro Zeile eine Zutat",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                lines.forEach { line ->
                    key(line.id) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = line.amountG,
                                onValueChange = { newVal ->
                                    lines = lines.map {
                                        if (it.id == line.id) it.copy(
                                            amountG = newVal.filter { c ->
                                                c.isDigit() || c == ',' || c == '.'
                                            }.take(6)
                                        ) else it
                                    }
                                },
                                label = { Text("g") },
                                modifier = Modifier.width(88.dp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            OutlinedTextField(
                                value = line.name,
                                onValueChange = { newVal ->
                                    lines = lines.map {
                                        if (it.id == line.id) it.copy(name = newVal) else it
                                    }
                                },
                                label = { Text("Zutat") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("z.B. Haferflocken") }
                            )
                            IconButton(
                                onClick = {
                                    lines = if (lines.size <= 1) {
                                        val id = nextId
                                        nextId = nextId + 1
                                        listOf(ManualIngredientLine(id = id))
                                    } else {
                                        lines.filter { it.id != line.id }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Zutat entfernen",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val id = nextId
                        nextId = nextId + 1
                        lines = lines + ManualIngredientLine(id = id)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Zutat hinzufügen")
                }

                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Zubereitung (optional)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    maxLines = 12
                )

                Button(
                    onClick = { doSave() },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rezept speichern")
                }
                // Extra Platz, damit die letzte Zeile über der Tastatur bleibt
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}
