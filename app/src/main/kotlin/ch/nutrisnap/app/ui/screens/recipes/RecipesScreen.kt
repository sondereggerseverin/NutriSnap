package ch.nutrisnap.app.ui.screens.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest

import android.content.Intent
import android.net.Uri

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.SheetValue
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.data.model.MatchSource
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.domain.ParsedIngredient
import ch.nutrisnap.app.domain.INGREDIENT_UNITS
import ch.nutrisnap.app.domain.parseIngredientLine
import ch.nutrisnap.app.domain.joinIngredientLine
import ch.nutrisnap.app.domain.normalizeForCoverageMatch
import ch.nutrisnap.app.domain.RecipeGermanMetricConverter
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_RATINGS
import ch.nutrisnap.app.ui.theme.KEY_FRESH_UI
import ch.nutrisnap.app.ui.theme.KEY_FRESH_RECIPE_CARDS
import ch.nutrisnap.app.ui.theme.KEY_FRESH_RECIPE_DETAIL
import ch.nutrisnap.app.ui.theme.KEY_CLASSIC_RECIPE_LIST
import ch.nutrisnap.app.ui.components.RecipeGridCard
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import androidx.datastore.preferences.core.edit
import androidx.compose.material.icons.filled.Star
import kotlinx.coroutines.launch
import org.json.JSONObject
import ch.nutrisnap.app.domain.UrlExtractor
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.MicronutrientTable
import ch.nutrisnap.app.ui.theme.MacroColors
import coil.compose.AsyncImage

// ── Unit conversions (Brüche + cups/tbsp/°F → metrisch) ───────────────────────
/* healComponentsProportional entfernt – ersetzt durch enrichComponentsFromMatches */

internal fun convertToMetric(text: String): String =
    runCatching { RecipeGermanMetricConverter.convertUnitsToMetric(text) }.getOrDefault(text)

internal fun scaleNumbers(line: String, ratio: Float): String {
    if (ratio == 1f) return line
    return Regex("""(\d+(?:[./]\d+)?)""").replace(line) { mr ->
        val num = mr.value.toFloatOrNull()
            ?: mr.value.split("/").let { p -> if (p.size==2) (p[0].toFloatOrNull()?:return@replace mr.value)/(p[1].toFloatOrNull()?:return@replace mr.value) else return@replace mr.value }
        val s = num * ratio
        if (s == s.toLong().toFloat()) s.toLong().toString() else "%.1f".format(s)
    }
}

// ── Structured ingredient parsing ─────────────────────────────────────────────
/** Sterne 1–5 aus DataStore (KEY_RECIPE_RATINGS), 0 = noch nicht bewertet. */
internal fun recipeStarsFromPrefs(prefsJson: String?, recipeId: Long): Int {
    if (prefsJson.isNullOrBlank()) return 0
    return runCatching {
        val root = org.json.JSONObject(prefsJson)
        val entry = root.opt(recipeId.toString()) ?: return 0
        when (entry) {
            is org.json.JSONObject -> entry.optInt("stars", 0)
            is Number -> entry.toInt()
            else -> 0
        }
    }.getOrDefault(0)
}

@Composable
internal fun RecipeStarsRow(stars: Int, modifier: Modifier = Modifier) {
    if (stars <= 0) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(5) { i ->
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (i < stars) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
            )
        }
    }
}


// ── Screen ────────────────────────────────────────────────────────────────────
internal fun Recipe.isIncomplete(): Boolean {
    val t = title.trim()
    val isHtml = t.startsWith("<!DOCTYPE", true) || t.startsWith("<html", true) ||
        "<script" in t.lowercase() || t.length > 120 && t.count { it == '<' } >= 3
    if (isHtml) return true
    val ingredientsHtml = ingredients.trimStart().startsWith("<!DOCTYPE", true) ||
        ingredients.trimStart().startsWith("<html", true)
    if (ingredientsHtml) return true
    return (t == "Rezept" || t.startsWith("Rezept von")) && imageUrl.isNullOrBlank() && totalCalories == null
}

@Composable
fun RecipesScreen(
    vm: RecipesViewModel = viewModel(),
    diaryVm: ch.nutrisnap.app.ui.screens.diary.DiaryViewModel = viewModel(),
    shoppingVm: ch.nutrisnap.app.ui.screens.shopping.ShoppingListViewModel = viewModel(),
    freezerVm: FreezerViewModel = viewModel(),
    collectionsVm: RecipeCollectionsViewModel = viewModel(),
    sharedUrl: String? = null,
    sharedBatchUrls: List<String> = emptyList(),
    sharedRecipeJson: String? = null
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val collections by collectionsVm.collections.collectAsStateWithLifecycle()
    var showImportSheet   by remember { mutableStateOf(false) }
    var showCreateSheet   by remember { mutableStateOf(false) }
    var selectedRecipe    by remember { mutableStateOf<Recipe?>(null) }
    var showVerifySheet    by remember { mutableStateOf(false) }
    var verifyReadOnly     by remember { mutableStateOf(false) }
    var pendingVerify      by remember { mutableStateOf(false) }
    var pendingViewOnly    by remember { mutableStateOf(false) }
    var showSplitSheet     by remember { mutableStateOf(false) }
    var splitRecipeId      by remember { mutableStateOf<Long?>(null) }
    var addToDiaryRecipe  by remember { mutableStateOf<Recipe?>(null) }
    var rateAfterDiary   by remember { mutableStateOf<Recipe?>(null) }
    var editRecipe        by remember { mutableStateOf<Recipe?>(null) }
    var editComponentsRecipe by remember { mutableStateOf<Recipe?>(null) }
    var hideIncomplete    by remember { mutableStateOf(false) }
    var favoritesOnly     by remember { mutableStateOf(false) }
    var collectionFilterId by remember { mutableStateOf<Long?>(null) }
    var showBatchSheet    by remember { mutableStateOf(false) }
    var showCookSheet     by remember { mutableStateOf(false) }
    var cookingRecipe     by remember { mutableStateOf<Recipe?>(null) }
    var showCollections   by remember { mutableStateOf(false) }
    var assignCollectionRecipe by remember { mutableStateOf<Recipe?>(null) }
    val batchState by vm.batchState.collectAsStateWithLifecycle()
    val budgetScaleState by vm.budgetScaleState.collectAsStateWithLifecycle()
    val pendingTargetKcal by vm.pendingTargetKcal.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val classicList = prefs?.get(KEY_CLASSIC_RECIPE_LIST) == true
    val freshCards = (prefs?.get(KEY_FRESH_RECIPE_CARDS) == true) || (prefs?.get(KEY_FRESH_UI) == true)
    val useGrid = !classicList
    val window = ch.nutrisnap.app.ui.rememberWindowInfo()
    val gridColumns = window.recipeGridColumns(classicList = false)

    // Ziel-kcal aus „Was koche ich?“ einmalig anwenden, sobald ein Rezept geöffnet wird
    LaunchedEffect(selectedRecipe?.id, pendingTargetKcal) {
        val recipe = selectedRecipe ?: return@LaunchedEffect
        val target = pendingTargetKcal ?: return@LaunchedEffect
        vm.clearPendingTargetKcal()
        vm.scaleToTargetKcal(recipe, target)
    }

    // Vollbild-Kochmodus (überlagert die Liste)
    cookingRecipe?.let { recipe ->
        CookingModeScreen(
            recipe = recipe,
            onBack = { cookingRecipe = null }
        )
        return
    }

    // Sammlungs-Ordner (überlagert die Liste)
    if (showCollections) {
        RecipeCollectionsScreen(
            onOpenRecipe = { recipe ->
                showCollections = false
                selectedRecipe = recipe
            },
            onBack = { showCollections = false }
        )
        return
    }

    LaunchedEffect(sharedUrl) { if (!sharedUrl.isNullOrBlank()) showImportSheet = true }
    LaunchedEffect(sharedRecipeJson) { if (!sharedRecipeJson.isNullOrBlank()) vm.importFromSharedJson(sharedRecipeJson) }
    LaunchedEffect(state.instagramBlocked) { if (state.instagramBlocked) showImportSheet = true }
    LaunchedEffect(state.lastImport) { if (state.lastImport != null) showImportSheet = false }
    LaunchedEffect(sharedBatchUrls) {
        if (sharedBatchUrls.isNotEmpty()) { vm.addBatchUrls(sharedBatchUrls); showBatchSheet = true }
    }

    Scaffold(
        floatingActionButton = {
            val scheme = MaterialTheme.colorScheme
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallFloatingActionButton(
                    onClick = { showCookSheet = true },
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Kitchen, "Was koche ich?", tint = scheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                SmallFloatingActionButton(
                    onClick = { showBatchSheet = true },
                    containerColor = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.PlaylistAdd, "Mehrere Rezepte importieren", tint = scheme.onSecondaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                SmallFloatingActionButton(
                    onClick = { showImportSheet = true },
                    containerColor = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.Link, "Rezept importieren", tint = scheme.onSecondaryContainer)
                }
                Spacer(Modifier.height(8.dp))
                FloatingActionButton(
                    onClick = { showCreateSheet = true },
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary
                ) {
                    Icon(Icons.Default.Add, "Freies Rezept erstellen", tint = scheme.onPrimary)
                }
            }
        }
    ) { padding ->
        // fillMaxSize + weight(1f) am Grid/List: sonst misst Column die Lazy-Liste
        // mit unbounded height → alle 251 Items werden gemessen → ANR in RectList/MeasureAndLayout.
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Kompakte Suche (ohne Label → weniger Höhe)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = {
                        vm.setQuery(it)
                        if (it.isNotBlank()) vm.clearCookFilters()
                    },
                    placeholder = { Text("Suchen…", fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                )
                IconButton(
                    onClick = { showCollections = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Folder, "Sammlungen", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }

            if (state.ingredientNeedles.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Zutaten: " + state.ingredientNeedles.joinToString(", "),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { vm.clearCookFilters() }) { Text("Reset", fontSize = 11.sp) }
                }
            }

            // Eine Zeile: Kategorie + Plattform + Favoriten + Sammlungen + Sort
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                    FilterChip(
                        selected = state.categoryFilter == null && state.platformFilter == null && !favoritesOnly && collectionFilterId == null,
                        onClick = {
                            vm.setCategoryFilter(null)
                            vm.setPlatformFilter(null)
                            favoritesOnly = false
                            collectionFilterId = null
                        },
                        label = { Text("Alle", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp)
                    )
                    RecipeCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = state.categoryFilter == cat,
                            onClick = {
                                vm.setCategoryFilter(if (state.categoryFilter == cat) null else cat)
                            },
                            label = { Text("${cat.emoji} ${cat.label}", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    listOf(
                        "instagram" to "📷 IG",
                        "tiktok" to "🎵 TikTok",
                        "web" to "🌐 Web",
                        "ki" to "✨ KI",
                        "manual" to "✏️ Frei"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = state.platformFilter == value,
                            onClick = { vm.setPlatformFilter(if (state.platformFilter == value) null else value) },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    val favCount = state.recipes.count { it.isFavorite }
                    if (favCount > 0 || favoritesOnly) {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = {
                                favoritesOnly = !favoritesOnly
                                if (favoritesOnly) collectionFilterId = null
                            },
                            label = { Text(if (favCount > 0) "★ $favCount" else "★", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    collections.forEach { col ->
                        val colCount = state.recipes.count { it.collectionId == col.id }
                        if (colCount > 0 || collectionFilterId == col.id) {
                            FilterChip(
                                selected = collectionFilterId == col.id,
                                onClick = {
                                    collectionFilterId =
                                        if (collectionFilterId == col.id) null else col.id
                                    if (collectionFilterId != null) favoritesOnly = false
                                },
                                label = {
                                    Text(
                                        "${col.emoji}${if (colCount > 0) colCount else ""}",
                                        fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                    val incompleteCount = state.recipes.count { it.isIncomplete() }
                    if (incompleteCount > 0) {
                        FilterChip(
                            selected = hideIncomplete,
                            onClick = { hideIncomplete = !hideIncomplete },
                            label = { Text("🧹 $incompleteCount", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val next = when (state.sort) {
                            RecipeSort.NEWEST -> RecipeSort.NAME
                            RecipeSort.NAME -> RecipeSort.CALORIES
                            RecipeSort.CALORIES -> RecipeSort.NEWEST
                        }
                        vm.setSort(next)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Sort,
                        "Sortierung: ${state.sort}",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val displayedRecipes = state.recipes
                .let { if (favoritesOnly) it.filter { r -> r.isFavorite } else it }
                .let { list ->
                    val cid = collectionFilterId
                    if (cid != null) list.filter { it.collectionId == cid } else list
                }
                .let { if (hideIncomplete) it.filterNot { r -> r.isIncomplete() } else it }
            if (state.recipes.isNotEmpty()) {
                Text(
                    "${displayedRecipes.size} · " +
                        when (state.sort) {
                            RecipeSort.NEWEST   -> "neueste"
                            RecipeSort.NAME     -> "A–Z"
                            RecipeSort.CALORIES -> "kcal"
                        },
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            if (displayedRecipes.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Default.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    message = if (hideIncomplete) "Keine vollständigen Rezepte" else "Noch keine Rezepte gespeichert",
                    sub = if (hideIncomplete) "Schalte den Filter aus, um alle zu sehen" else "Tippe auf + und füge einen Link ein"
                )
            } else if (useGrid) {
                // Grid: Phone 2, Tablet 3–4 Spalten
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(
                        start = if (window.isTablet) 16.dp else 10.dp,
                        end = if (window.isTablet) 16.dp else 10.dp,
                        top = 4.dp,
                        bottom = 80.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(if (window.isTablet) 12.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (window.isTablet) 12.dp else 8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    gridItems(displayedRecipes, key = { it.id }) { recipe ->
                        RecipeGridCard(
                            recipe = recipe,
                            onClick = { selectedRecipe = recipe },
                            onAddToDiary = { addToDiaryRecipe = recipe },
                            onEdit = { editRecipe = recipe },
                            onDelete = { vm.deleteRecipe(recipe) },
                            onDuplicate = { vm.duplicateRecipe(recipe) },
                            onToggleFavorite = { vm.toggleFavorite(recipe) }
                        )
                    }
                }
            } else {
                // Klassische 1-Spalten-Liste (ursprüngliches Design); auf Tablet zentriert begrenzt
                val listPadH = if (window.isTablet) 24.dp else 16.dp
                LazyColumn(
                    contentPadding = PaddingValues(start = listPadH, end = listPadH, top = 6.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    items(displayedRecipes, key = { it.id }) { recipe ->
                        if (recipe.isIncomplete()) {
                            RecipeCard(
                                recipe,
                                onClick = { selectedRecipe = recipe },
                                onDelete = { vm.deleteRecipe(recipe) },
                                onAddToDiary = { addToDiaryRecipe = recipe },
                                onEdit = { editRecipe = recipe }
                            )
                        } else if (freshCards) {
                            ch.nutrisnap.app.ui.components.RecipeCardV2(
                                recipe = recipe,
                                onClick = { selectedRecipe = recipe },
                                onAddToDiary = { _ -> addToDiaryRecipe = recipe },
                                onEdit = { editRecipe = recipe },
                                onDelete = { vm.deleteRecipe(recipe) },
                                onDuplicate = { vm.duplicateRecipe(recipe) },
                                onToggleFavorite = { vm.toggleFavorite(recipe) }
                            )
                        } else {
                            RecipeCard(
                                recipe,
                                onClick = { selectedRecipe = recipe },
                                onDelete = { vm.deleteRecipe(recipe) },
                                onAddToDiary = { addToDiaryRecipe = recipe },
                                onEdit = { editRecipe = recipe }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        ManualRecipeCreateSheet(
            onSave = { title, ingredients, instructions, servings, mealCategory ->
                vm.createManualRecipe(title, ingredients, instructions, servings, mealCategory)
                showCreateSheet = false
            },
            onDismiss = { showCreateSheet = false }
        )
    }

    if (showImportSheet) {
        ImportSheet(
            prefillUrl = if (state.instagramBlocked) state.blockedUrl else (sharedUrl ?: ""),
            isLoading = state.isImporting,
            importPhase = state.importPhase,
            error = state.importError,
            openAtManualCaption = state.instagramBlocked,
            onImport = { url -> vm.importFromUrl(url) },
            onDismiss = { showImportSheet = false; vm.clearError(); vm.clearInstagramBlocked() }
        )
    }

    if (showCookSheet) {
        CookWithWhatIHaveSheet(
            onDismiss = { showCookSheet = false },
            onSearch = { ingredients, category, targetKcal ->
                vm.searchByIngredients(ingredients, category, targetKcal)
                showCookSheet = false
            }
        )
    }

    if (showBatchSheet) {
        BatchImportSheet(
            state = batchState,
            onAddUrls = vm::addBatchUrls,
            onRemoveItem = vm::removeBatchItem,
            onStart = vm::runBatchImport,
            onDismiss = {
                showBatchSheet = false
                if (!batchState.isRunning && batchState.items.all { it.status == BatchStatus.DONE }) vm.clearBatch()
            }
        )
    }

    state.lastImport?.let { recipe ->
        AlertDialog(
            onDismissRequest = vm::clearLastImport,
            icon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Rezept importiert!") },
            text = { Text(recipe.displayTitle()) },
            confirmButton = { TextButton(onClick = { selectedRecipe = recipe; vm.clearLastImport() }) { Text("Ansehen") } },
            dismissButton = { TextButton(onClick = vm::clearLastImport) { Text("OK") } }
        )
    }

    // ── Ingredient Verify Sheet ──────────────────────────────────────────────
    val verifyRecipe = selectedRecipe
    val nutState = state.nutritionState
    val verifyResult = nutState.result?.takeIf {
        nutState.recipeId == verifyRecipe?.id && !nutState.isAnalyzing
    }
    // Nach Analyse automatisch Verify/Einsehen öffnen, falls angefordert
    LaunchedEffect(nutState.result, nutState.isAnalyzing, pendingVerify, pendingViewOnly, verifyRecipe?.id) {
        if ((pendingVerify || pendingViewOnly) && verifyRecipe != null && verifyResult != null && !nutState.isAnalyzing) {
            verifyReadOnly = pendingViewOnly
            showVerifySheet = true
            pendingVerify = false
            pendingViewOnly = false
        }
    }

    val showVerifyNow = showVerifySheet && verifyRecipe != null && verifyResult != null
    if (showVerifyNow) {
        val existingComps by vm.getComponents(verifyRecipe!!.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val storedMatches by vm.getMatches(verifyRecipe.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val sessionOv = vm.getOverridesFor(verifyRecipe.id)
        val initialOv = if (sessionOv.isNotEmpty()) sessionOv else matchesToOverrides(storedMatches)
        IngredientVerifySheet(
            analysisResult = verifyResult!!,
            recipeName     = verifyRecipe!!.displayTitle(),
            servings       = verifyRecipe.servings,
            initialOverrides = initialOv,
            onOverridesChanged = { vm.setOverridesFor(verifyRecipe.id, it) },
            onDismiss      = { showVerifySheet = false; pendingVerify = false; pendingViewOnly = false; verifyReadOnly = false },
            readOnly       = verifyReadOnly,
            onConfirm      = { kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium, totalWeightG, ingredientsText ->
                vm.applyVerifiedNutrition(
                    verifyRecipe, kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium,
                    totalIngredientWeightG = totalWeightG,
                    ingredientsText = ingredientsText
                )
                showVerifySheet = false
                pendingVerify = false
                pendingViewOnly = false
                verifyReadOnly = false
            },
            onConfirmComponents = { comps ->
                vm.setComponents(verifyRecipe.id, comps)
            },
            recipeIdForComponents = verifyRecipe.id,
            onSaveMatches = { matches ->
                vm.replaceMatchesForRecipe(verifyRecipe.id, matches)
            },
            initialSideWeightG = existingComps.firstOrNull {
                it.name.contains("beilage", ignoreCase = true)
            }?.cookedWeightG,
            initialSauceWeightG = existingComps.firstOrNull {
                it.name.contains("sauce", ignoreCase = true) ||
                    it.name.contains("fleisch", ignoreCase = true)
            }?.cookedWeightG,
            recipeIngredients = verifyRecipe.ingredients,
            allowComponentSplit = verifyRecipe.withGuessedCategoryIfEmpty().category().allowsComponentSplit &&
                RecipeCategory.guess(
                    verifyRecipe.title, verifyRecipe.ingredients, verifyRecipe.description
                ).allowsComponentSplit
        )
    }

    // Beilage/Sauce-Trennung (eigenes Sheet, unabhängig von Verify)
    val splitRecipe = if (showSplitSheet && splitRecipeId != null) {
        state.recipes.find { it.id == splitRecipeId }
    } else null
    LaunchedEffect(showSplitSheet, splitRecipeId, splitRecipe) {
        if (showSplitSheet && splitRecipeId != null && splitRecipe == null) {
            showSplitSheet = false
            splitRecipeId = null
        }
    }
    if (showSplitSheet && splitRecipe != null) {
        val splitMatches by vm.getMatches(splitRecipe.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val splitComps by vm.getComponents(splitRecipe.id).collectAsStateWithLifecycle(initialValue = emptyList())
        ComponentSplitSheet(
            recipe = splitRecipe,
            matches = splitMatches,
            initialComponents = splitComps,
            onSave = { comps, matches ->
                vm.replaceMatchesForRecipe(splitRecipe.id, matches)
                vm.setComponents(splitRecipe.id, comps)
            },
            onDismiss = {
                showSplitSheet = false
                splitRecipeId = null
            }
        )
    }

    // Detail-Sheet nicht gleichzeitig mit Verify-Sheet (doppeltes ModalBottomSheet = Crash)
    if (!showVerifyNow && !showSplitSheet) selectedRecipe?.let { recipe ->
        // Always show latest version from state
        val live = state.recipes.find { it.id == recipe.id } ?: recipe
        val liveMatches by vm.getMatches(live.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val imageRefresh by vm.imageRefreshState.collectAsStateWithLifecycle()
        // Caption-Klumpen einmalig in saubere Zeilen zerlegen und speichern
        LaunchedEffect(live.id, live.ingredients) {
            vm.repairMashedIngredientsIfNeeded(live)
        }
        RecipeDetailSheet(
            recipe       = live,
            nutritionState = state.nutritionState,
            onDismiss    = { selectedRecipe = null }, // Nutrition behalten → Re-Verify ändert keine Matches
            onAddToDiary = { r -> addToDiaryRecipe = r; selectedRecipe = null },
            onEdit       = { editRecipe = live; selectedRecipe = null },
            onAnalyze    = { vm.analyzeNutrition(live, persist = true) },
            onVerify     = {
                verifyReadOnly = false
                val hasResult = state.nutritionState.result != null &&
                    state.nutritionState.recipeId == live.id &&
                    !state.nutritionState.isAnalyzing
                // Bereits verifizierte Zutaten sind vorhanden -> direkt daraus anzeigen,
                // statt jedes Mal neu (und ggf. mit abweichenden Treffern) zu suchen.
                val fromStored = if (!hasResult) {
                    ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.fromStoredMatches(live, liveMatches)
                } else null
                when {
                    hasResult -> showVerifySheet = true
                    fromStored != null -> {
                        vm.setNutritionFromStoredMatches(fromStored, live.id)
                        showVerifySheet = true
                    }
                    else -> {
                        // Analyse nur in-memory – Persistenz erst bei explizitem «Nährwerte übernehmen»
                        pendingVerify = true
                        vm.analyzeNutrition(live, persist = false)
                    }
                }
            },
            onViewIngredients = {
                verifyReadOnly = true
                val hasResult = state.nutritionState.result != null &&
                    state.nutritionState.recipeId == live.id &&
                    !state.nutritionState.isAnalyzing
                // Nur ansehen: nie neu suchen, wenn schon verifizierte Matches existieren.
                val fromStored = if (!hasResult) {
                    ch.nutrisnap.app.domain.RecipeNutritionAnalyzer.fromStoredMatches(live, liveMatches)
                } else null
                when {
                    hasResult -> showVerifySheet = true
                    fromStored != null -> {
                        vm.setNutritionFromStoredMatches(fromStored, live.id)
                        showVerifySheet = true
                    }
                    else -> {
                        // Analyse nur in-memory, NIEMALS verifizierte Nährwerte überschreiben
                        pendingViewOnly = true
                        vm.analyzeNutrition(live, persist = false)
                    }
                }
            },
            onSplitComponents = {
                splitRecipeId = live.id
                showSplitSheet = true
                selectedRecipe = null
            },
            onRecalculateFromOverrides = { vm.recalculateFromOverrides(live) },
            hasStoredOverrides = vm.getOverridesFor(live.id).isNotEmpty() ||
                matchesHaveOverrides(liveMatches),
            onAddToShoppingList = { r ->
                val ratio = r.servings.toFloat() / live.servings.coerceAtLeast(1).toFloat()
                val names = live.ingredients.lines().mapNotNull { rawLine ->
                    if (rawLine.isBlank()) return@mapNotNull null
                    val scaled = if (ratio != 1f) scaleNumbers(rawLine, ratio) else rawLine
                    val isHeader = !scaled.startsWith("•") && !scaled.startsWith("-") &&
                        scaled.length > 2 && !scaled.first().isDigit() && !scaled.startsWith(" ")
                    if (isHeader) null else scaled.trimStart('•', '-', ' ').trim().takeIf { it.isNotBlank() }
                }
                shoppingVm.addRecipeIngredients(live.displayTitle(), names.map { Triple(it, null, null) })
                selectedRecipe = null
            },
            onUpdateIngredients = { newText -> vm.updateIngredientsAndSyncMatches(live, newText) },
            onUpdateCookedWeight = { w -> vm.updateRecipe(live.copy(cookedWeightG = w)) },
            onScaleToBudget = { vm.scaleToRemainingBudget(live) },
            onTranslateGermanMetric = { vm.translateToGermanMetric(live) },
            isTranslating = state.isTranslating,
            onEditComponents = {
                editComponentsRecipe = live
                selectedRecipe = null
            },
            onStartCooking = {
                cookingRecipe = live
                selectedRecipe = null
            },
            onToggleFavorite = { vm.toggleFavorite(live) },
            onAssignCollection = {
                assignCollectionRecipe = live
            },
            onRetryImage = { vm.refreshRecipeImage(live) },
            imageRefreshStatus = imageRefresh.takeIf { it.first == live.id }?.second,
            ingredientMatches = liveMatches
        )
    }

    assignCollectionRecipe?.let { recipe ->
        AssignToCollectionDialog(
            recipe = recipe,
            onDismiss = { assignCollectionRecipe = null }
        )
    }

    // Feature 1: Ergebnis der Restbudget-Skalierung
    if (budgetScaleState.isLoading || budgetScaleState.result != null || budgetScaleState.error != null) {
        AlertDialog(
            onDismissRequest = { vm.clearBudgetScale() },
            title = { Text("Auf Restbudget anpassen") },
            text = {
                when {
                    budgetScaleState.isLoading -> Text("Berechne …")
                    budgetScaleState.error != null -> Text(budgetScaleState.error!!)
                    budgetScaleState.result != null -> {
                        val r = budgetScaleState.result!!
                        Column {
                            Text("Noch ${r.remainingKcal.toInt()} kcal übrig heute.")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${(r.scaleFactor * 100).toInt()}% der Portion " +
                                    "(${String.format("%.1f", r.scaledServings)} statt ${r.recipe.servings} Portionen) " +
                                    "≈ ${r.scaledKcal.toInt()} kcal",
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Protein ${r.scaledProtein.toInt()}g · Carbs ${r.scaledCarbs.toInt()}g · Fett ${r.scaledFat.toInt()}g",
                                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(10.dp))
                            Text("Zutaten (angepasst):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            r.ingredients.forEach { line ->
                                val text = if (line.parsed && line.scaledAmountG != null)
                                    "${line.scaledAmountG.toInt()}g ${line.name ?: line.originalLine}"
                                else line.originalLine
                                Text("• $text", fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.clearBudgetScale() }) { Text("OK") } }
        )
    }

    addToDiaryRecipe?.let { recipe ->
        val components by vm.getComponents(recipe.id).collectAsStateWithLifecycle(initialValue = emptyList())
        val diaryMatches by vm.getMatches(recipe.id).collectAsStateWithLifecycle(initialValue = emptyList())
        // Persistente Korrektur: Nährwerte aus Matches ableiten (einmal pro Öffnen)
        LaunchedEffect(recipe.id, components, diaryMatches) {
            if (components.size > 1) {
                val healed = vm.healComponentNutrition(recipe, components)
                if (healed != components) {
                    vm.setComponents(recipe.id, healed, updateRecipeTotals = false)
                }
            }
        }
        val safeComponents = remember(recipe.id, components, diaryMatches, recipe.totalCalories) {
            enrichComponentsFromMatches(recipe, components, diaryMatches)
        }

        if (components.isNotEmpty()) {
            MultiComponentAddToDiarySheet(
                recipe = recipe,
                components = safeComponents,
                onConfirm = { gramsMap, meal, date ->
                    diaryVm.addRecipeComponentsAsMeal(recipe, safeComponents, gramsMap, meal, date)
                    rateAfterDiary = recipe
                    addToDiaryRecipe = null
                },
                onDismiss = { addToDiaryRecipe = null },
                onFreeze = { gramsMap, qty ->
                    freezerVm.freezeFromComponents(recipe, safeComponents, gramsMap, qty)
                    addToDiaryRecipe = null
                }
            )
        } else {
            // 1) Gekochtes Gewicht oder gespeicherte Zutatensumme, 2) Fallback: aus Text schätzen
            val estimatedRaw = RecipeNutritionAnalyzer.estimateTotalGrams(recipe.ingredients).takeIf { it > 0f }
            val saneStoredRaw = recipe.totalIngredientWeightG?.takeIf { stored ->
                estimatedRaw == null || stored <= estimatedRaw * 2.5f
            }
            val totalYield = recipe.cookedWeightG?.takeIf { it > 0f }
                ?: saneStoredRaw
                ?: estimatedRaw
            val gramsPerServing = totalYield?.div(recipe.servings.coerceAtLeast(1))

            AddToDiarySheet(
                recipe = recipe,
                gramsPerServing = gramsPerServing,
                yieldTotalG = totalYield,
                isCookedWeight = recipe.cookedWeightG != null && (recipe.cookedWeightG ?: 0f) > 0f,
                onConfirm = { servings, grams, meal, date ->
                    diaryVm.addRecipeAsMeal(recipe, servings, meal, grams, date)
                    rateAfterDiary = recipe
                    addToDiaryRecipe = null
                },
                onDismiss = { addToDiaryRecipe = null }
            )
        }
    }

    rateAfterDiary?.let { recipe ->
        RecipeQuickRatingDialog(
            recipe = recipe,
            onDismiss = { rateAfterDiary = null }
        )
    }

    editRecipe?.let { recipe ->
        RecipeEditSheet(
            recipe    = recipe,
            onSave    = { updated -> vm.updateRecipe(updated); editRecipe = null },
            onDismiss = { editRecipe = null }
        )
    }

    editComponentsRecipe?.let { recipe ->
        val existing by vm.getComponents(recipe.id).collectAsStateWithLifecycle(initialValue = emptyList())
        var suggested by remember(recipe.id) { mutableStateOf<List<RecipeComponent>>(emptyList()) }
        var suggestKey by remember { mutableStateOf(0) }
        LaunchedEffect(recipe.id, suggestKey) {
            suggested = vm.suggestComponentsFromMatches(recipe)
        }
        RecipeComponentsEditorSheet(
            recipe = recipe,
            initial = existing,
            suggested = suggested,
            onSave = { list ->
                vm.setComponents(recipe.id, list)
                editComponentsRecipe = null
            },
            onDismiss = { editComponentsRecipe = null },
            onRequestSuggest = { suggestKey++ }
        )
    }
}

