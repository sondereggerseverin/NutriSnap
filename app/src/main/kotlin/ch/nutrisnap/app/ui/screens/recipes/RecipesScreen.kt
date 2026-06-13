package ch.nutrisnap.app.ui.screens.recipes

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.domain.AnalyzedIngredient
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.domain.RecipeNutritionResult
import ch.nutrisnap.app.ui.components.EmptyState
import coil.compose.AsyncImage

// ── Main Screen ───────────────────────────────────────────────────────────────

@Composable
fun RecipesScreen(
    vm:        RecipesViewModel = viewModel(),
    diaryVm:   ch.nutrisnap.app.ui.screens.diary.DiaryViewModel = viewModel(),
    sharedUrl: String? = null
) {
    val state        by vm.uiState.collectAsState()
    val analysisState by vm.analysisState.collectAsState()

    var showImportSheet  by remember { mutableStateOf(false) }
    var selectedRecipe   by remember { mutableStateOf<Recipe?>(null) }
    var addToDiaryRecipe by remember { mutableStateOf<Recipe?>(null) }
    var editRecipe       by remember { mutableStateOf<Recipe?>(null) }

    LaunchedEffect(sharedUrl)              { if (!sharedUrl.isNullOrBlank()) showImportSheet = true }
    LaunchedEffect(state.instagramBlocked) { if (state.instagramBlocked) showImportSheet = true }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick        = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Icon(Icons.Default.Link, "Rezept importieren", tint = MaterialTheme.colorScheme.onSecondary)
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
                    icon    = { Icon(Icons.Default.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    message = "Noch keine Rezepte gespeichert",
                    sub     = "Tippe auf + und füge einen Link ein"
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(state.recipes, key = { it.id }) { recipe ->
                        RecipeCard(
                            recipe       = recipe,
                            onClick      = { selectedRecipe = recipe },
                            onDelete     = { vm.deleteRecipe(recipe) },
                            onAddToDiary = { addToDiaryRecipe = recipe },
                            onEdit       = { editRecipe = recipe }
                        )
                    }
                }
            }
        }
    }

    // ── Import sheet ──────────────────────────────────────────────────────────
    if (showImportSheet) {
        ImportSheet(
            prefillUrl          = if (state.instagramBlocked) state.blockedUrl else (sharedUrl ?: ""),
            isLoading           = state.isImporting,
            error               = state.importError,
            openAtManualCaption = state.instagramBlocked,
            onImport            = vm::importFromUrl,
            onDismiss           = { showImportSheet = false; vm.clearError(); vm.clearInstagramBlocked() }
        )
    }

    // ── Last import dialog ────────────────────────────────────────────────────
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

    // ── Detail sheet ──────────────────────────────────────────────────────────
    selectedRecipe?.let { recipe ->
        RecipeDetailSheet(
            recipe       = recipe,
            analysisState = analysisState,
            onDismiss    = { selectedRecipe = null; vm.resetAnalysis() },
            onAddToDiary = { r -> addToDiaryRecipe = r; selectedRecipe = null },
            onEdit       = { editRecipe = recipe; selectedRecipe = null },
            onAnalyze    = { vm.analyzeNutrition(recipe) },
            onSave       = { updated -> vm.updateRecipe(updated); selectedRecipe = updated }
        )
    }

    // ── Edit sheet ────────────────────────────────────────────────────────────
    editRecipe?.let { recipe ->
        RecipeEditSheet(
            recipe    = recipe,
            onSave    = { updated -> vm.updateRecipe(updated); editRecipe = null },
            onDismiss = { editRecipe = null }
        )
    }

    // ── Add to diary sheet ────────────────────────────────────────────────────
    addToDiaryRecipe?.let { recipe ->
        AddToDiarySheet(
            recipe    = recipe,
            onConfirm = { servings, meal -> diaryVm.addRecipeAsMeal(recipe, servings, meal); addToDiaryRecipe = null },
            onDismiss = { addToDiaryRecipe = null }
        )
    }
}

// ── Recipe Card ───────────────────────────────────────────────────────────────

@Composable
private fun RecipeCard(
    recipe:      Recipe,
    onClick:     () -> Unit,
    onDelete:    () -> Unit,
    onAddToDiary:() -> Unit,
    onEdit:      () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            recipe.imageUrl?.let { url ->
                AsyncImage(
                    model = url, contentDescription = recipe.title,
                    modifier     = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recipe.platform?.let { PlatformChip(it) }
                    recipe.totalCalories?.let { MiniChip("🔥 ${it.toInt()} kcal") }
                    recipe.prepTimeMinutes?.let { MiniChip("⏱ $it min") }
                }
                recipe.servings.takeIf { it > 1 }?.let {
                    Text("$it Portionen", fontSize = 11.sp,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onAddToDiary) {
                    Icon(Icons.Default.PlaylistAdd, "Ins Tagebuch", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, "Bearbeiten", Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.secondary)
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
            title   = { Text("Rezept löschen?") },
            text    = { Text(recipe.title) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable private fun PlatformChip(platform: String) {
    val (icon, label) = when (platform.lowercase()) {
        "instagram" -> Icons.Default.CameraAlt      to "Instagram"
        "tiktok"    -> Icons.Default.VideoLibrary   to "TikTok"
        "youtube"   -> Icons.Default.PlayCircle     to "YouTube"
        else        -> Icons.Default.Language       to "Web"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}
@Composable private fun MiniChip(text: String) {
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Recipe Detail Sheet ───────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailSheet(
    recipe:        Recipe,
    analysisState: NutritionAnalysisState,
    onDismiss:     () -> Unit,
    onAddToDiary:  (Recipe) -> Unit,
    onEdit:        () -> Unit,
    onAnalyze:     () -> Unit,
    onSave:        (Recipe) -> Unit
) {
    val context    = LocalContext.current
    var servings   by remember(recipe.id) { mutableStateOf(recipe.servings) }
    var metricMode by remember { mutableStateOf(false) }

    // Use DB values OR analysis result for macros
    val nutrition = when (val s = analysisState) {
        is NutritionAnalysisState.Done -> s.result
        else -> null
    }
    val calsPerServing  = nutrition?.calsPerServing  ?: recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val protPerServing  = nutrition?.protPerServing  ?: recipe.proteinPerServing
    val carbsPerServing = nutrition?.carbsPerServing ?: recipe.carbsPerServing
    val fatPerServing   = nutrition?.fatPerServing   ?: recipe.fatPerServing

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.96f)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp, start = 16.dp, end = 16.dp)) {

            // Hero
            recipe.imageUrl?.let { url ->
                item {
                    AsyncImage(model = url, contentDescription = recipe.title,
                        modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop)
                    Spacer(Modifier.height(14.dp))
                }
            }

            // Title row + edit button
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 21.sp,
                        lineHeight = 27.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, "Bearbeiten", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recipe.prepTimeMinutes?.let { MetaBadge("⏱ $it min") }
                    recipe.platform?.let { MetaBadge("📌 $it") }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Servings editor
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape  = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Portionen", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = { if (servings > 1) servings-- }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Remove, "-", Modifier.size(16.dp))
                                }
                                Text("$servings", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                    modifier = Modifier.widthIn(min = 28.dp),
                                    textAlign = TextAlign.Center)
                                IconButton(onClick = { servings++ }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Add, "+", Modifier.size(16.dp))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Metrische Einheiten", fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked = metricMode, onCheckedChange = { metricMode = it },
                                modifier = Modifier.height(24.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ── Nutrition Analysis Section ─────────────────────────────────────
            item {
                NutritionSection(
                    recipe        = recipe,
                    servings      = servings,
                    analysisState = analysisState,
                    calsPerServing  = calsPerServing,
                    protPerServing  = protPerServing,
                    carbsPerServing = carbsPerServing,
                    fatPerServing   = fatPerServing,
                    onAnalyze     = onAnalyze,
                    onAddToDiary  = { onAddToDiary(recipe.copy(servings = servings)) }
                )
                Spacer(Modifier.height(12.dp))
            }

            // Description
            val desc = recipe.description.lines()
                .filterNot { it.startsWith("📊") }.joinToString("\n").trim()
            if (desc.isNotBlank()) {
                item {
                    SectionHeader("Beschreibung")
                    Spacer(Modifier.height(4.dp))
                    Text(desc, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                }
            }

            // Ingredients with scaling
            if (recipe.ingredients.isNotBlank()) {
                item { SectionHeader("Zutaten"); Spacer(Modifier.height(8.dp)) }
                val ratio    = servings.toFloat() / recipe.servings.coerceAtLeast(1).toFloat()
                val rawLines = recipe.ingredients.lines()

                // Show analyzed breakdown if available
                if (analysisState is NutritionAnalysisState.Done) {
                    val analyzed = analysisState.result.ingredients
                    items(analyzed) { ai ->
                        AnalyzedIngredientRow(ai, ratio, metricMode)
                    }
                } else {
                    items(rawLines) { rawLine ->
                        if (rawLine.isBlank()) { Spacer(Modifier.height(4.dp)); return@items }
                        val scaled  = if (ratio != 1f) scaleNumbers(rawLine, ratio) else rawLine
                        val display = if (metricMode) RecipeNutritionAnalyzer.convertToMetric(scaled) else scaled
                        val isHeader = !display.startsWith("•") && !display.startsWith("-") &&
                            display.isNotEmpty() && !display[0].isDigit() && !display.startsWith(" ") &&
                            display.length > 2 && !display.contains(Regex("\\d"))
                        if (isHeader) {
                            Spacer(Modifier.height(10.dp))
                            Text(display.trimEnd(':'), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary)
                        } else {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                                Text("•  ", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                                Text(display.trimStart('•', '-', ' '), fontSize = 14.sp, lineHeight = 20.sp,
                                    modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Instructions
            if (recipe.instructions.isNotBlank()) {
                item { SectionHeader("Zubereitung"); Spacer(Modifier.height(8.dp)) }
                val steps = recipe.instructions.split(Regex("""\n+"""))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.matches(Regex("""\d+\.?""")) }
                items(steps.size) { idx ->
                    val step      = steps[idx]
                    val isNumbered = step.matches(Regex("""^\d+[.)]\s.*"""))
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        if (!isNumbered) {
                            Surface(shape = RoundedCornerShape(50),
                                color    = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(22.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("${idx + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(step, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Source link
            recipe.sourceUrl?.let { link ->
                item {
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Original-Link öffnen")
                    }
                }
            }
        }
    }
}

// ── Nutrition Section ─────────────────────────────────────────────────────────

@Composable
private fun NutritionSection(
    recipe:         Recipe,
    servings:       Int,
    analysisState:  NutritionAnalysisState,
    calsPerServing: Float?,
    protPerServing: Float?,
    carbsPerServing:Float?,
    fatPerServing:  Float?,
    onAnalyze:      () -> Unit,
    onAddToDiary:   () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // Macro card if we have data
        if (calsPerServing != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape  = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("📊 Nährwerte pro Portion", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (analysisState is NutritionAnalysisState.Done) {
                            val found = analysisState.result.ingredients.count { it.found }
                            val total = analysisState.result.ingredients.size
                            Text("$found/$total gefunden", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MacroItem("Kalorien",  "${(calsPerServing  * servings / recipe.servings.coerceAtLeast(1)).toInt()}", "kcal")
                        protPerServing?.let  { MacroItem("Protein",   "${(it * servings / recipe.servings.coerceAtLeast(1)).toInt()}", "g") }
                        carbsPerServing?.let { MacroItem("Kohlenhy.", "${(it * servings / recipe.servings.coerceAtLeast(1)).toInt()}", "g") }
                        fatPerServing?.let   { MacroItem("Fett",      "${(it * servings / recipe.servings.coerceAtLeast(1)).toInt()}", "g") }
                    }
                }
            }
        }

        // Analysis state feedback
        when (analysisState) {
            is NutritionAnalysisState.Loading -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Zutaten werden analysiert…", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            is NutritionAnalysisState.Error -> {
                Text(analysisState.msg, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            else -> {}
        }

        // Analyse / Diary buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recipe.ingredients.isNotBlank() && analysisState !is NutritionAnalysisState.Loading) {
                OutlinedButton(
                    onClick = onAnalyze,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Science, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (calsPerServing != null) "Neu berechnen" else "Nährwerte berechnen", fontSize = 13.sp)
                }
            }
            Button(
                onClick  = onAddToDiary,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlaylistAdd, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ins Tagebuch", fontSize = 13.sp)
            }
        }
    }
}

// ── Analyzed Ingredient Row ───────────────────────────────────────────────────

@Composable
private fun AnalyzedIngredientRow(ai: AnalyzedIngredient, ratio: Float, metricMode: Boolean) {
    val scaled  = if (ratio != 1f) scaleNumbers(ai.parsed.raw, ratio) else ai.parsed.raw
    val display = if (metricMode) RecipeNutritionAnalyzer.convertToMetric(scaled) else scaled

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Found indicator
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (ai.found) Color(0xFF2D6A4F) else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(50)
                )
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(display.trimStart('•', '-', ' '), fontSize = 13.sp, lineHeight = 18.sp)
            if (ai.found && ai.calories > 0f) {
                Text("${ai.calories.toInt()} kcal · P ${ai.protein.toInt()}g · K ${ai.carbs.toInt()}g · F ${ai.fat.toInt()}g",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!ai.found) {
                Text("Nicht in Datenbank gefunden", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Recipe Edit Sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditSheet(recipe: Recipe, onSave: (Recipe) -> Unit, onDismiss: () -> Unit) {
    var title        by remember(recipe.id) { mutableStateOf(recipe.title) }
    var description  by remember(recipe.id) { mutableStateOf(recipe.description) }
    var ingredients  by remember(recipe.id) { mutableStateOf(recipe.ingredients) }
    var instructions by remember(recipe.id) { mutableStateOf(recipe.instructions) }
    var servingsText by remember(recipe.id) { mutableStateOf(recipe.servings.toString()) }
    var prepText     by remember(recipe.id) { mutableStateOf(recipe.prepTimeMinutes?.toString() ?: "") }
    var tags         by remember(recipe.id) { mutableStateOf(recipe.tags) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.96f)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Rezept bearbeiten", fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Schliessen") }
            }

            OutlinedTextField(
                value         = title,
                onValueChange = { title = it },
                label         = { Text("Titel *") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = servingsText,
                    onValueChange = { servingsText = it },
                    label         = { Text("Portionen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier      = Modifier.weight(1f),
                    singleLine    = true
                )
                OutlinedTextField(
                    value         = prepText,
                    onValueChange = { prepText = it },
                    label         = { Text("Zubereitungszeit (min)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier      = Modifier.weight(1f),
                    singleLine    = true
                )
            }

            // Ingredients editor
            Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Eine Zutat pro Zeile, z.B. «200g Hühnerbrust»",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value         = ingredients,
                onValueChange = { ingredients = it },
                label         = { Text("Zutaten") },
                modifier      = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                maxLines      = 20
            )

            // Instructions editor
            Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            OutlinedTextField(
                value         = instructions,
                onValueChange = { instructions = it },
                label         = { Text("Zubereitungsschritte") },
                modifier      = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                maxLines      = 20
            )

            OutlinedTextField(
                value         = description,
                onValueChange = { description = it },
                label         = { Text("Beschreibung (optional)") },
                modifier      = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                maxLines      = 8
            )

            OutlinedTextField(
                value         = tags,
                onValueChange = { tags = it },
                label         = { Text("Tags (kommagetrennt)") },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Abbrechen") }
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            onSave(recipe.copy(
                                title          = title.trim(),
                                description    = description.trim(),
                                ingredients    = ingredients.trim(),
                                instructions   = instructions.trim(),
                                servings       = servingsText.toIntOrNull()?.coerceAtLeast(1) ?: recipe.servings,
                                prepTimeMinutes = prepText.toIntOrNull(),
                                tags           = tags.trim()
                            ))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled  = title.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Speichern")
                }
            }
        }
    }
}

// ── Import Sheet ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    prefillUrl:          String,
    isLoading:           Boolean,
    error:               String?,
    openAtManualCaption: Boolean = false,
    onImport:            (String) -> Unit,
    onDismiss:           () -> Unit
) {
    val context     = LocalContext.current
    val vm: RecipesViewModel = viewModel()
    var url          by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var showManual   by remember(openAtManualCaption) { mutableStateOf(openAtManualCaption) }
    var manualTitle  by remember { mutableStateOf("") }
    var manualCaption by remember { mutableStateOf("") }
    val isInstagram  = "instagram.com" in url || "instagr.am" in url

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            if (!showManual) {
                Text("Rezept importieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Instagram-, TikTok- oder Webseiten-Link einfügen.", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") },
                    leadingIcon = { Icon(Icons.Default.Link, null) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, isError = error != null)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onImport(url.trim()) }, enabled = url.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isLoading) "Importiere…" else "Importieren")
                }
                if (isInstagram) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Caption manuell einfügen")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!openAtManualCaption) {
                        IconButton(onClick = { showManual = false }) { Icon(Icons.Default.ArrowBack, "Zurück") }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Caption einfügen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (openAtManualCaption) Text("Instagram hat den automatischen Import blockiert.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Instagram öffnen & Caption kopieren")
                }
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = manualTitle, onValueChange = { manualTitle = it },
                    label = { Text("Titel (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = manualCaption, onValueChange = { manualCaption = it },
                    label = { Text("Caption / Rezepttext hier einfügen") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp), maxLines = 12)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick  = { if (manualCaption.isNotBlank()) { vm.saveManualRecipe(url.trim(), manualTitle.trim().ifBlank { null }, manualCaption.trim()); onDismiss() } },
                    enabled  = manualCaption.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rezept speichern")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Add to Diary Sheet ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToDiarySheet(recipe: Recipe, onConfirm: (Float, MealType) -> Unit, onDismiss: () -> Unit) {
    var servingsText by remember { mutableStateOf(recipe.servings.toString()) }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    val servings     = servingsText.toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f
    val calsPerServ  = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val estimatedCals = calsPerServ?.let { it * servings }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Ins Tagebuch", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text(recipe.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value         = servingsText,
                    onValueChange = { servingsText = it },
                    label         = { Text("Portionen") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier      = Modifier.weight(1f),
                    singleLine    = true
                )
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.height(56.dp)) {
                        Text(selectedMeal.label()); Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        MealType.values().forEach { meal ->
                            DropdownMenuItem(text = { Text(meal.label()) },
                                onClick = { selectedMeal = meal; expanded = false })
                        }
                    }
                }
            }
            estimatedCals?.let {
                Spacer(Modifier.height(8.dp))
                Text("≈ ${it.toInt()} kcal", fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, Modifier.weight(1f)) { Text("Abbrechen") }
                Button(onClick = { onConfirm(servings, selectedMeal) }, Modifier.weight(1f),
                    enabled = servings > 0) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hinzufügen")
                }
            }
        }
    }
}

// ── Shared Composables ────────────────────────────────────────────────────────

@Composable private fun SectionHeader(text: String) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun MetaBadge(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable private fun MacroItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(unit, fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Text(label, fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

private fun scaleNumbers(line: String, ratio: Float): String {
    if (ratio == 1f) return line
    return Regex("""(\d+(?:[./]\d+)?)""").replace(line) { mr ->
        val num = mr.value.toFloatOrNull()
            ?: mr.value.split("/").let { p ->
                if (p.size == 2) (p[0].toFloatOrNull() ?: return@replace mr.value) /
                        (p[1].toFloatOrNull() ?: return@replace mr.value)
                else return@replace mr.value
            }
        val s = num * ratio
        if (s == s.toLong().toFloat()) s.toLong().toString() else "%.1f".format(s)
    }
}

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
