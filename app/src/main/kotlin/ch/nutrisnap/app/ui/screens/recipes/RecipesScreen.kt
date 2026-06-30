package ch.nutrisnap.app.ui.screens.recipes

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.ui.components.EmptyState
import coil.compose.AsyncImage

// ── Unit conversions ──────────────────────────────────────────────────────────
private data class ConvEntry(val pattern: Regex, val toMetric: (Double) -> String)
private val CONVERSIONS = listOf(
    ConvEntry(Regex("""(\d+(?:[.,]\d+)?)\s*(?:cups?|Cup)"""))         { v -> "${(v*240).toInt()}ml" },
    ConvEntry(Regex("""(\d+(?:[.,]\d+)?)\s*(?:tbsp|Tbsp|EL)"""))      { v -> "${(v*15).toInt()}ml" },
    ConvEntry(Regex("""(\d+(?:[.,]\d+)?)\s*(?:tsp|Tsp|TL)"""))        { v -> "${(v*5).toInt()}ml" },
    ConvEntry(Regex("""(\d+(?:[.,]\d+)?)\s*(?:oz|Oz)\b"""))           { v -> "${(v*28.35).toInt()}g" },
    ConvEntry(Regex("""(\d+(?:[.,]\d+)?)\s*(?:lbs?|pounds?)"""))      { v -> "${(v*453.6).toInt()}g" },
    ConvEntry(Regex("""(\d+(?:[.,]\d+)?)\s*(?:fl\.?\s*oz)"""))        { v -> "${(v*29.57).toInt()}ml" }
)
private fun convertToMetric(text: String): String {
    var r = text
    CONVERSIONS.forEach { conv -> r = conv.pattern.replace(r) { mr ->
        mr.groupValues[1].replace(',','.').toDoubleOrNull()?.let { conv.toMetric(it) } ?: mr.value
    }}
    return r
}
private fun scaleNumbers(line: String, ratio: Float): String {
    if (ratio == 1f) return line
    return Regex("""(\d+(?:[./]\d+)?)""").replace(line) { mr ->
        val num = mr.value.toFloatOrNull()
            ?: mr.value.split("/").let { p -> if (p.size==2) (p[0].toFloatOrNull()?:return@replace mr.value)/(p[1].toFloatOrNull()?:return@replace mr.value) else return@replace mr.value }
        val s = num * ratio
        if (s == s.toLong().toFloat()) s.toLong().toString() else "%.1f".format(s)
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────
private fun Recipe.isIncomplete(): Boolean =
    (title == "Rezept" || title.startsWith("Rezept von")) && imageUrl.isNullOrBlank() && totalCalories == null

@Composable
fun RecipesScreen(
    vm: RecipesViewModel = viewModel(),
    diaryVm: ch.nutrisnap.app.ui.screens.diary.DiaryViewModel = viewModel(),
    sharedUrl: String? = null
) {
    val state by vm.uiState.collectAsState()
    var showImportSheet   by remember { mutableStateOf(false) }
    var selectedRecipe    by remember { mutableStateOf<Recipe?>(null) }
    var showVerifySheet    by remember { mutableStateOf(false) }
    var addToDiaryRecipe  by remember { mutableStateOf<Recipe?>(null) }
    var editRecipe        by remember { mutableStateOf<Recipe?>(null) }
    var hideIncomplete    by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUrl) { if (!sharedUrl.isNullOrBlank()) showImportSheet = true }
    LaunchedEffect(state.instagramBlocked) { if (state.instagramBlocked) showImportSheet = true }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.secondary) {
                Icon(Icons.Default.Link, "Rezept importieren", tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value = state.query, onValueChange = vm::setQuery,
                label = { Text("Rezepte durchsuchen") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true, shape = RoundedCornerShape(12.dp)
            )

            // ── Filter & Sortierung ──────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        null       to "Alle",
                        "instagram" to "📷 IG",
                        "tiktok"    to "🎵 TikTok",
                        "web"       to "🌐 Web",
                        "ki"        to "✨ KI"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = state.platformFilter == value,
                            onClick  = { vm.setPlatformFilter(value) },
                            label    = { Text(label, fontSize = 12.sp) }
                        )
                    }
                    val incompleteCount = state.recipes.count { it.isIncomplete() }
                    if (incompleteCount > 0) {
                        FilterChip(
                            selected = hideIncomplete,
                            onClick  = { hideIncomplete = !hideIncomplete },
                            label    = { Text("🧹 Ohne leere ($incompleteCount)", fontSize = 12.sp) }
                        )
                    }
                }
                IconButton(onClick = {
                    val next = when (state.sort) {
                        RecipeSort.NEWEST   -> RecipeSort.NAME
                        RecipeSort.NAME     -> RecipeSort.CALORIES
                        RecipeSort.CALORIES -> RecipeSort.NEWEST
                    }
                    vm.setSort(next)
                }) {
                    Icon(Icons.Default.Sort, "Sortierung: ${state.sort}",
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            val displayedRecipes = if (hideIncomplete) state.recipes.filterNot { it.isIncomplete() } else state.recipes
            if (state.recipes.isNotEmpty()) {
                Text(
                    "${displayedRecipes.size} Rezept${if (displayedRecipes.size == 1) "" else "e"} · " +
                        when (state.sort) {
                            RecipeSort.NEWEST   -> "neueste zuerst"
                            RecipeSort.NAME     -> "A–Z"
                            RecipeSort.CALORIES -> "meiste kcal zuerst"
                        },
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (displayedRecipes.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Default.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    message = if (hideIncomplete) "Keine vollständigen Rezepte" else "Noch keine Rezepte gespeichert",
                    sub = if (hideIncomplete) "Schalte den Filter aus, um alle zu sehen" else "Tippe auf + und füge einen Link ein"
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(displayedRecipes, key = { it.id }) { recipe ->
                        RecipeCard(recipe,
                            onClick      = { selectedRecipe = recipe },
                            onDelete     = { vm.deleteRecipe(recipe) },
                            onAddToDiary = { addToDiaryRecipe = recipe },
                            onEdit       = { editRecipe = recipe })
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ImportSheet(
            prefillUrl = if (state.instagramBlocked) state.blockedUrl else (sharedUrl ?: ""),
            isLoading = state.isImporting, error = state.importError,
            openAtManualCaption = state.instagramBlocked,
            onImport = { url -> vm.importFromUrl(url) },
            onDismiss = { showImportSheet = false; vm.clearError(); vm.clearInstagramBlocked() }
        )
    }

    state.lastImport?.let { recipe ->
        AlertDialog(
            onDismissRequest = vm::clearLastImport,
            icon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Rezept importiert!") },
            text = { Text(recipe.title) },
            confirmButton = { TextButton(onClick = { selectedRecipe = recipe; vm.clearLastImport() }) { Text("Ansehen") } },
            dismissButton = { TextButton(onClick = vm::clearLastImport) { Text("OK") } }
        )
    }

    // ── Ingredient Verify Sheet ──────────────────────────────────────────────
    val verifyRecipe = selectedRecipe
    val verifyResult = if (showVerifySheet) {
        (vm.uiState.value.nutritionState.result)?.takeIf {
            vm.uiState.value.nutritionState.recipeId == verifyRecipe?.id
        }
    } else null
    if (showVerifySheet && verifyRecipe != null && verifyResult != null) {
        IngredientVerifySheet(
            analysisResult = verifyResult,
            recipeName     = verifyRecipe.title,
            servings       = verifyRecipe.servings,
            onDismiss      = { showVerifySheet = false },
            onConfirm      = { kcal, prot, carbs, fat ->
                vm.applyVerifiedNutrition(verifyRecipe, kcal, prot, carbs, fat)
                showVerifySheet = false
            }
        )
    }

    selectedRecipe?.let { recipe ->
        // Always show latest version from state
        val live = state.recipes.find { it.id == recipe.id } ?: recipe
        RecipeDetailSheet(
            recipe       = live,
            nutritionState = state.nutritionState,
            onDismiss    = { selectedRecipe = null; vm.clearNutrition() },
            onAddToDiary = { r -> addToDiaryRecipe = r; selectedRecipe = null },
            onEdit       = { editRecipe = live; selectedRecipe = null },
            onAnalyze    = { vm.analyzeNutrition(live) },
            onVerify     = { showVerifySheet = true }
        )
    }

    addToDiaryRecipe?.let { recipe ->
        AddToDiarySheet(
            recipe = recipe,
            onConfirm = { servings, meal -> diaryVm.addRecipeAsMeal(recipe, servings, meal); addToDiaryRecipe = null },
            onDismiss = { addToDiaryRecipe = null }
        )
    }

    editRecipe?.let { recipe ->
        RecipeEditSheet(
            recipe    = recipe,
            onSave    = { updated -> vm.updateRecipe(updated); editRecipe = null },
            onDismiss = { editRecipe = null }
        )
    }
}

// ── Recipe Card ───────────────────────────────────────────────────────────────
@Composable
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit, onDelete: () -> Unit,
    onAddToDiary: () -> Unit, onEdit: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    val incomplete = recipe.isIncomplete()
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
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
                    Text(recipe.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            title = { Text("Rezept löschen?") }, text = { Text(recipe.title) },
            confirmButton = { TextButton(onClick = { onDelete(); showConfirm = false }) { Text("Löschen", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } })
    }
}

@Composable private fun PlatformChip(platform: String) {
    val (icon, label) = when (platform.lowercase()) {
        "instagram" -> Icons.Default.CameraAlt to "Instagram"
        "tiktok"    -> Icons.Default.VideoLibrary to "TikTok"
        "ki"        -> Icons.Default.AutoAwesome to "KI"
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
@Composable
private fun RecipeThumbnail(
    recipe:   Recipe,
    modifier: Modifier = Modifier,
    size:     androidx.compose.ui.unit.Dp? = null,
    shape:    RoundedCornerShape = RoundedCornerShape(10.dp)
) {
    val box = if (size != null) modifier.then(Modifier.size(size)) else modifier
    val url = recipe.imageUrl
    // Track load failure so CDN-expired/auth-gated TikTok URLs fall back to gradient
    var imageLoadFailed by remember(url) { mutableStateOf(false) }

    if (!url.isNullOrBlank() && !imageLoadFailed) {
        AsyncImage(
            model = url, contentDescription = recipe.title,
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
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size((size ?: 64.dp) * 0.4f))
        }
    }
}

private fun platformVisuals(platform: String?): Pair<List<Color>, androidx.compose.ui.graphics.vector.ImageVector> =
    when (platform?.lowercase()) {
        "instagram" -> listOf(Color(0xFFFEDA77), Color(0xFFDC2743), Color(0xFF962FBF)) to Icons.Default.CameraAlt
        "tiktok"    -> listOf(Color(0xFF25F4EE), Color(0xFF000000), Color(0xFFFE2C55)) to Icons.Default.VideoLibrary
        "ki"        -> listOf(Color(0xFFFF9B45), Color(0xFFD9633B)) to Icons.Default.AutoAwesome
        else        -> listOf(Color(0xFF2D6A4F), Color(0xFF40916C)) to Icons.Default.RestaurantMenu
    }

@Composable private fun MiniChip(text: String) =
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ── Import Sheet ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(prefillUrl: String, isLoading: Boolean, error: String?,
    openAtManualCaption: Boolean = false, onImport: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current; val vm: RecipesViewModel = viewModel()
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var showManual by remember(openAtManualCaption) { mutableStateOf(openAtManualCaption) }
    var manualTitle by remember { mutableStateOf("") }; var manualCaption by remember { mutableStateOf("") }
    val isInstagram = "instagram.com" in url || "instagr.am" in url
    // Jeder Fehler bei einem Instagram-Link -> sofort Caption-Fallback anbieten,
    // nicht nur wenn der ViewModel-State exakt "instagramBlocked" meldet.
    LaunchedEffect(error) {
        if (error != null && isInstagram) showManual = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal=16.dp).navigationBarsPadding().padding(bottom=8.dp)) {
            if (!showManual) {
                Text("Rezept importieren", fontWeight=FontWeight.Bold, fontSize=18.sp)
                Spacer(Modifier.height(4.dp))
                Text("Instagram-, TikTok- oder Webseiten-Link", fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value=url, onValueChange={url=it}, label={Text("URL")},
                    leadingIcon={Icon(Icons.Default.Link,null)}, modifier=Modifier.fillMaxWidth(), singleLine=true, isError=error!=null)
                if (error != null) Text(error, color=MaterialTheme.colorScheme.error, fontSize=13.sp, modifier=Modifier.padding(top=4.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick={onImport(url.trim())}, enabled=url.isNotBlank()&&!isLoading, modifier=Modifier.fillMaxWidth()) {
                    if (isLoading) { CircularProgressIndicator(Modifier.size(18.dp), color=MaterialTheme.colorScheme.onPrimary, strokeWidth=2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (isLoading) "Importiere…" else "Importieren")
                }
                if (isInstagram) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick={showManual=true}, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentPaste,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Caption manuell einfügen")
                    }
                }
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if (!openAtManualCaption) IconButton(onClick={showManual=false}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Zurück") }
                    Column(Modifier.weight(1f)) {
                        Text("Caption einfügen", fontWeight=FontWeight.Bold, fontSize=18.sp)
                        if (openAtManualCaption) Text("Instagram blockiert automatischen Import.", fontSize=12.sp, color=MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK}) }}, modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew,null,Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Instagram öffnen & Caption kopieren")
                }
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                OutlinedTextField(value=manualTitle, onValueChange={manualTitle=it}, label={Text("Titel (optional)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value=manualCaption, onValueChange={manualCaption=it}, label={Text("Caption einfügen")}, modifier=Modifier.fillMaxWidth().heightIn(min=140.dp), maxLines=12)
                Spacer(Modifier.height(12.dp))
                Button(onClick={ if(manualCaption.isNotBlank()){vm.saveManualRecipe(url.trim(),manualTitle.trim().ifBlank{null},manualCaption.trim());onDismiss()}}, enabled=manualCaption.isNotBlank(), modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Speichern")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Detail Sheet ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailSheet(
    recipe: Recipe,
    nutritionState: NutritionState,
    onDismiss: () -> Unit,
    onAddToDiary: (Recipe) -> Unit,
    onEdit: () -> Unit,
    onAnalyze: () -> Unit,
    onVerify: () -> Unit = {}
) {
    val context = LocalContext.current
    var servings   by remember(recipe.id) { mutableStateOf(recipe.servings) }
    var metricMode by remember { mutableStateOf(false) }
    val ratio      = servings.toFloat() / recipe.servings.coerceAtLeast(1).toFloat()

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.94f)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp, start = 16.dp, end = 16.dp)) {

            item {
                RecipeThumbnail(recipe = recipe, modifier = Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(14.dp))
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.Top) {
                    Text(recipe.title, fontWeight=FontWeight.Bold, fontSize=22.sp, lineHeight=28.sp, modifier=Modifier.weight(1f))
                    IconButton(onClick=onEdit) { Icon(Icons.Default.Edit, "Bearbeiten", tint=MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    recipe.prepTimeMinutes?.let { MetaBadge("⏱ $it min") }
                    recipe.platform?.let { MetaBadge("📌 $it") }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Portionen + Metric stepper
            item {
                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant),
                    shape=RoundedCornerShape(12.dp), modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text("Portionen", fontWeight=FontWeight.SemiBold, fontSize=14.sp)
                            Row(verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick={if(servings>1)servings--}, Modifier.size(32.dp)) { Icon(Icons.Default.Remove,"-",Modifier.size(16.dp)) }
                                Text("$servings", fontWeight=FontWeight.Bold, fontSize=18.sp,
                                    modifier=Modifier.widthIn(min=28.dp),
                                    style=LocalTextStyle.current.copy(textAlign=TextAlign.Center))
                                IconButton(onClick={servings++}, Modifier.size(32.dp)) { Icon(Icons.Default.Add,"+",Modifier.size(16.dp)) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Text("Metrische Einheiten", fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Switch(checked=metricMode, onCheckedChange={metricMode=it}, modifier=Modifier.height(24.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Nährwert-Analyse Block
            item {
                NutritionAnalysisCard(
                    recipe         = recipe,
                    nutritionState = nutritionState,
                    servings       = servings,
                    ratio          = ratio,
                    onAnalyze      = onAnalyze,
                    onVerify       = onVerify
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick={onAddToDiary(recipe.copy(servings=servings))}, modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlaylistAdd,null,Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ins Tagebuch ($servings Port.)")
                }
                Spacer(Modifier.height(12.dp))
            }

            // Description
            val desc = recipe.description.lines().filterNot{it.startsWith("📊")}.joinToString("\n").trim()
            if (desc.isNotBlank()) {
                item { SectionHeader("Beschreibung"); Spacer(Modifier.height(4.dp)); Text(desc, fontSize=14.sp, lineHeight=20.sp); Spacer(Modifier.height(16.dp)) }
            }

            // Ingredients
            if (recipe.ingredients.isNotBlank()) {
                item { SectionHeader("Zutaten"); Spacer(Modifier.height(8.dp)) }
                val rawLines = recipe.ingredients.lines()
                items(rawLines) { rawLine ->
                    if (rawLine.isBlank()) { Spacer(Modifier.height(4.dp)); return@items }
                    val scaled  = if (ratio != 1f) scaleNumbers(rawLine, ratio) else rawLine
                    val display = if (metricMode) convertToMetric(scaled) else scaled
                    val isHeader = !display.startsWith("•") && !display.startsWith("-") &&
                        display.isNotEmpty() && !display[0].isDigit() && !display.startsWith(" ") && display.length > 2
                    if (isHeader) {
                        Spacer(Modifier.height(10.dp))
                        Text(display.trimEnd(':'), fontWeight=FontWeight.SemiBold, fontSize=13.sp, color=MaterialTheme.colorScheme.primary)
                    } else {
                        Row(Modifier.fillMaxWidth().padding(vertical=3.dp), verticalAlignment=Alignment.Top) {
                            Text("•  ", fontSize=14.sp, color=MaterialTheme.colorScheme.secondary)
                            Text(display.trimStart('•','-',' '), fontSize=14.sp, lineHeight=20.sp, modifier=Modifier.weight(1f))
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            // Instructions
            if (recipe.instructions.isNotBlank()) {
                item { SectionHeader("Zubereitung"); Spacer(Modifier.height(8.dp)) }
                val steps = recipe.instructions.split(Regex("""\n+""")).map{it.trim()}
                    .filter{it.isNotBlank() && !it.matches(Regex("""\d+\.?"""))}
                items(steps.size) { idx ->
                    val step = steps[idx]; val isNum = step.matches(Regex("""^\d+[.)]\s.*"""))
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp), verticalAlignment=Alignment.Top) {
                        if (!isNum) {
                            Surface(shape=RoundedCornerShape(50), color=MaterialTheme.colorScheme.secondaryContainer, modifier=Modifier.size(22.dp)) {
                                Box(contentAlignment=Alignment.Center) { Text("${idx+1}", fontSize=11.sp, fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.onSecondaryContainer) }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(step, fontSize=14.sp, lineHeight=20.sp, modifier=Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            recipe.sourceUrl?.let { link ->
                item {
                    OutlinedButton(onClick={ runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK})}}, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.OpenInNew,null); Spacer(Modifier.width(6.dp)); Text("Original-Link öffnen")
                    }
                }
            }
        }
    }
}

// ── Nutrition Analysis Card ───────────────────────────────────────────────────
@Composable
private fun NutritionAnalysisCard(
    recipe: Recipe,
    nutritionState: NutritionState,
    servings: Int,
    ratio: Float,
    onAnalyze: () -> Unit,
    onVerify: () -> Unit = {}
) {
    val isForThis = nutritionState.recipeId == recipe.id
    val isAnalyzing = nutritionState.isAnalyzing && isForThis
    val result = nutritionState.result.takeIf { isForThis }

    // Use analyzed result if available, otherwise use stored macros
    val calsPerServ = result?.caloriesPerServing
        ?: recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val protPerServ = result?.proteinPerServing ?: recipe.proteinPerServing
    val carbPerServ = result?.carbsPerServing   ?: recipe.carbsPerServing
    val fatPerServ  = result?.fatPerServing     ?: recipe.fatPerServing

    val hasMacros = calsPerServ != null || protPerServ != null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("📊 Nährwerte", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (!isAnalyzing) {
                    TextButton(onClick = onAnalyze, contentPadding = PaddingValues(4.dp)) {
                        Icon(Icons.Default.Calculate, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (hasMacros) "Neu berechnen" else "Berechnen", fontSize = 11.sp)
                    }
                }
            }

            if (isAnalyzing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Suche Zutaten in Datenbank & schätze Rest per KI…", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
            } else if (hasMacros) {
                Spacer(Modifier.height(8.dp))
                // Per-serving row (scaled by servings stepper)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    calsPerServ?.let { MacroItem("Kalorien", "${(it*ratio).toInt()}", "kcal") }
                    protPerServ?.let { MacroItem("Protein",  "${(it*ratio).toInt()}", "g") }
                    carbPerServ?.let { MacroItem("Kohlenhy.", "${(it*ratio).toInt()}", "g") }
                    fatPerServ?.let  { MacroItem("Fett",     "${(it*ratio).toInt()}", "g") }
                }
                // Analysis details
                result?.let { r ->
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.2f))
                    Spacer(Modifier.height(4.dp))
                    val baseText = "${r.matchedCount}/${r.totalCount} Zutaten gefunden · Gesamt: ${r.totalCalories.toInt()} kcal"
                    Text(baseText, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f))
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onVerify,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zutaten einzeln verifizieren", fontSize = 13.sp)
                    }
                    if (r.estimatedCount > 0) {
                        Text("✨ ${r.estimatedCount} davon per KI geschätzt (kein DB-Treffer)",
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f))
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Tippe auf \"Berechnen\" um Nährwerte aus der OpenFoodFacts-Datenbank zu ermitteln.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    lineHeight = 16.sp)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ── Add to Diary ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToDiarySheet(recipe: Recipe, onConfirm: (Float, MealType) -> Unit, onDismiss: () -> Unit) {
    var servingsText by remember { mutableStateOf(recipe.servings.toString()) }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    val servings = servingsText.toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f
    val calsPerServ = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val estCals = calsPerServ?.let { it * servings }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal=16.dp).navigationBarsPadding().padding(bottom=16.dp)) {
            Text("Ins Tagebuch", fontWeight=FontWeight.Bold, fontSize=18.sp)
            Spacer(Modifier.height(4.dp))
            Text(recipe.title, fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value=servingsText, onValueChange={servingsText=it}, label={Text("Portionen")},
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal), modifier=Modifier.weight(1f), singleLine=true)
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={expanded=true}, modifier=Modifier.height(56.dp)) {
                        Text(selectedMeal.label()); Icon(Icons.Default.ArrowDropDown,null)
                    }
                    DropdownMenu(expanded=expanded, onDismissRequest={expanded=false}) {
                        MealType.values().forEach { meal ->
                            DropdownMenuItem(text={Text(meal.label())}, onClick={selectedMeal=meal;expanded=false})
                        }
                    }
                }
            }
            estCals?.let {
                Spacer(Modifier.height(8.dp))
                Text("≈ ${it.toInt()} kcal", fontWeight=FontWeight.SemiBold,
                    color=MaterialTheme.colorScheme.primary, fontSize=15.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onDismiss, Modifier.weight(1f)) { Text("Abbrechen") }
                Button(onClick={onConfirm(servings,selectedMeal)}, Modifier.weight(1f), enabled=servings>0) {
                    Icon(Icons.Default.Check,null,Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Hinzufügen")
                }
            }
        }
    }
}

@Composable private fun SectionHeader(text: String) {
    Text(text, fontWeight=FontWeight.Bold, fontSize=16.sp)
    HorizontalDivider(Modifier.padding(top=4.dp), thickness=1.dp, color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun MetaBadge(text: String) {
    Surface(shape=RoundedCornerShape(20.dp), color=MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, fontSize=11.sp, modifier=Modifier.padding(horizontal=8.dp, vertical=3.dp),
            color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
@Composable private fun MacroItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(value, fontWeight=FontWeight.Bold, fontSize=18.sp, color=MaterialTheme.colorScheme.onPrimaryContainer)
        Text(unit, fontSize=10.sp, color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f))
        Text(label, fontSize=10.sp, color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f))
    }
}
private fun MealType.label() = when(this) {
    MealType.BREAKFAST -> "Frühstück"; MealType.LUNCH -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"; MealType.SNACK -> "Snack"
}
