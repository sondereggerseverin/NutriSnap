package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.components.EmptyState


import coil.compose.AsyncImage

@Composable
fun RecipesScreen(
    vm: RecipesViewModel = viewModel(),
    diaryVm: ch.nutrisnap.app.ui.screens.diary.DiaryViewModel = viewModel(),
    sharedUrl: String? = null
) {
    val state by vm.uiState.collectAsState()
    var showImportSheet by remember { mutableStateOf(false) }
    var selectedRecipe  by remember { mutableStateOf<Recipe?>(null) }
    var addToDiaryRecipe by remember { mutableStateOf<Recipe?>(null) }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) showImportSheet = true
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.secondary) {
                Icon(Icons.Default.Link, "Rezept importieren",
                    tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value         = state.query,
                onValueChange = vm::setQuery,
                label         = { Text("Rezepte durchsuchen") },
                leadingIcon   = { Icon(Icons.Default.Search, null) },
                modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine    = true,
                shape         = RoundedCornerShape(12.dp)
            )

            if (state.recipes.isEmpty()) {
                EmptyState(
                    icon    = { Icon(Icons.Default.MenuBook, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    message = "Noch keine Rezepte gespeichert",
                    sub     = "Importiere Rezepte per Instagram-Link oder einer anderen URL"
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(state.recipes, key = { it.id }) { recipe ->
                        RecipeCard(
                            recipe        = recipe,
                            onClick       = { selectedRecipe = recipe },
                            onDelete      = { vm.deleteRecipe(recipe) },
                            onAddToDiary  = { addToDiaryRecipe = recipe }
                        )
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ImportUrlSheet(
            prefillUrl = sharedUrl ?: "",
            isLoading  = state.isImporting,
            error      = state.importError,
            onImport   = { url -> vm.importFromUrl(url) },
            onDismiss  = { showImportSheet = false; vm.clearError() }
        )
    }

    state.lastImport?.let { recipe ->
        AlertDialog(
            onDismissRequest = vm::clearLastImport,
            icon    = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title   = { Text("Rezept importiert!") },
            text    = { Text(recipe.title) },
            confirmButton = {
                TextButton(onClick = { selectedRecipe = recipe; vm.clearLastImport() }) { Text("Ansehen") }
            },
            dismissButton = { TextButton(onClick = vm::clearLastImport) { Text("OK") } }
        )
    }

    selectedRecipe?.let { recipe ->
        RecipeDetailSheet(
            recipe       = recipe,
            onDismiss    = { selectedRecipe = null },
            onAddToDiary = { addToDiaryRecipe = recipe; selectedRecipe = null }
        )
    }

    addToDiaryRecipe?.let { recipe ->
        AddRecipeToDiarySheet(
            recipe    = recipe,
            onConfirm = { servings, meal -> diaryVm.addRecipeAsMeal(recipe, servings, meal); addToDiaryRecipe = null },
            onDismiss = { addToDiaryRecipe = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeCard(
    recipe: Recipe,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onAddToDiary: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(onClick = onClick),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            recipe.imageUrl?.let { url ->
                AsyncImage(
                    model              = url,
                    contentDescription = recipe.title,
                    modifier           = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale       = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (recipe.platform != null) {
                    Spacer(Modifier.height(4.dp))
                    PlatformChip(recipe.platform)
                }
                recipe.prepTimeMinutes?.let {
                    Text("⏱ $it min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                recipe.totalCalories?.let {
                    Text("~${it.toInt()} kcal", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onAddToDiary) {
                    Icon(Icons.Default.PlaylistAdd, "Ins Tagebuch", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { showConfirm = true }) {
                    Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title            = { Text("Rezept löschen?") },
            text             = { Text(recipe.title) },
            confirmButton    = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun PlatformChip(platform: String) {
    val (icon, label) = when (platform.lowercase()) {
        "instagram" -> Icons.Default.CameraAlt to "Instagram"
        "tiktok"    -> Icons.Default.VideoLibrary to "TikTok"
        "youtube"   -> Icons.Default.PlayCircle to "YouTube"
        else        -> Icons.Default.Language to "Web"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

// ── Add Recipe to Diary Sheet ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecipeToDiarySheet(
    recipe: Recipe,
    onConfirm: (Float, MealType) -> Unit,
    onDismiss: () -> Unit
) {
    var servingsText by remember { mutableStateOf("1") }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    val servings = servingsText.toFloatOrNull() ?: 1f
    val estimatedCals = (recipe.totalCalories ?: 0f) * servings

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Ins Tagebuch hinzufügen", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            Text(recipe.title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value           = servingsText,
                    onValueChange   = { servingsText = it },
                    label           = { Text("Portionen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.weight(1f),
                    singleLine      = true
                )
                MealDropdown(selected = selectedMeal) { selectedMeal = it }
            }

            if (estimatedCals > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "≈ ${estimatedCals.toInt()} kcal",
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.primary
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Keine Kaloriendaten für dieses Rezept",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Abbrechen") }
                Button(
                    onClick  = { onConfirm(servings, selectedMeal) },
                    modifier = Modifier.weight(1f),
                    enabled  = servings > 0
                ) {
                    Icon(Icons.Default.PlaylistAdd, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MealDropdown(selected: MealType, onSelect: (MealType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label())
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MealType.values().forEach { meal ->
                DropdownMenuItem(
                    text    = { Text(meal.label()) },
                    onClick = { onSelect(meal); expanded = false }
                )
            }
        }
    }
}

// ── Import URL Sheet ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportUrlSheet(
    prefillUrl: String,
    isLoading:  Boolean,
    error:      String?,
    onImport:   (String) -> Unit,
    onDismiss:  () -> Unit
) {
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).navigationBarsPadding()) {
            Text("Rezept importieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text("Füge einen Instagram-, TikTok- oder Webseiten-Link ein.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value         = url,
                onValueChange = { url = it },
                label         = { Text("URL") },
                leadingIcon   = { Icon(Icons.Default.Link, null) },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                isError       = error != null
            )
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick  = { onImport(url.trim()) },
                enabled  = url.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isLoading) "Importiere…" else "Importieren")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Recipe Detail ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailSheet(recipe: Recipe, onDismiss: () -> Unit, onAddToDiary: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.92f)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            recipe.imageUrl?.let { url ->
                item {
                    AsyncImage(
                        model = url, contentDescription = recipe.title,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    recipe.prepTimeMinutes?.let {
                        Text("⏱ $it min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("👥 ${recipe.servings} Portionen", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recipe.platform?.let {
                        Text("📌 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                recipe.totalCalories?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("~${it.toInt()} kcal gesamt", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                // ── Add to diary button ───────────────────────────────────
                Button(onClick = onAddToDiary, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlaylistAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Heute ins Tagebuch")
                }
                Spacer(Modifier.height(16.dp))
            }
            if (recipe.description.isNotBlank()) {
                item {
                    Text("Beschreibung", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(recipe.description, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (recipe.ingredients.isNotBlank()) {
                item {
                    Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(recipe.ingredients, fontSize = 14.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (recipe.instructions.isNotBlank()) {
                item {
                    Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(recipe.instructions, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
            recipe.sourceUrl?.let {
                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { /* open URL */ }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Original-Link öffnen")
                    }
                }
            }
        }
    }
}

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
