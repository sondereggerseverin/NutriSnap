package ch.nutrisnap.app.ui.screens.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest

import android.content.Intent
import android.net.Uri

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_GRID_DENSITY
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_GRID_COLUMNS
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


@Composable
private fun FabMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 2.dp
        ) {
            Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = containerColor,
            contentColor = contentColor
        ) {
            Icon(icon, label, tint = contentColor)
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var fabExpanded       by remember { mutableStateOf(false) }
    var cookingRecipe     by remember { mutableStateOf<Recipe?>(null) }
    var showCollections   by remember { mutableStateOf(false) }
    var showFilterSheet   by remember { mutableStateOf(false) }
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
    // 6 (Standard, ~3 Zeilen) oder 4 (größere Kacheln wie früher, ~2 Zeilen)
    val gridDensity = when (val d = prefs?.get(KEY_RECIPE_GRID_DENSITY)) {
        4 -> 4
        else -> 6 // inkl. Migration von altem Wert 8
    }
    // Phone: 2 oder 3 Spalten aus Settings; Tablet behält Breakpoint-Spalten
    val gridColumns = if (window.isTablet) {
        window.recipeGridColumns(classicList = false)
    } else {
        when (prefs?.get(KEY_RECIPE_GRID_COLUMNS)) {
            3 -> 3
            else -> 2
        }
    }

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
    LaunchedEffect(state.needsScreenshot) { if (state.needsScreenshot) showImportSheet = true }
    LaunchedEffect(state.lastImport, state.needsScreenshot) {
        if (state.lastImport != null && !state.needsScreenshot) showImportSheet = false
    }
    LaunchedEffect(sharedBatchUrls) {
        if (sharedBatchUrls.isNotEmpty()) { vm.addBatchUrls(sharedBatchUrls); showBatchSheet = true }
    }

    Scaffold(
        // Hub-Tabs sitzen bereits über diesem Screen – kein Statusleisten-/Top-Inset,
        // sonst entsteht der tote Streifen zwischen Segment-Control und Suchfeld.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            val scheme = MaterialTheme.colorScheme
            val rotation by animateFloatAsState(if (fabExpanded) 45f else 0f, label = "fabRotation")
            Column(horizontalAlignment = Alignment.End) {
                AnimatedVisibility(
                    visible = fabExpanded,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        FabMenuItem(
                            icon = Icons.Default.Kitchen,
                            label = "Was koche ich?",
                            containerColor = scheme.primaryContainer,
                            contentColor = scheme.onPrimaryContainer,
                            onClick = { fabExpanded = false; showCookSheet = true }
                        )
                        Spacer(Modifier.height(10.dp))
                        FabMenuItem(
                            icon = Icons.Default.PlaylistAdd,
                            label = "Mehrere importieren",
                            containerColor = scheme.secondaryContainer,
                            contentColor = scheme.onSecondaryContainer,
                            onClick = { fabExpanded = false; showBatchSheet = true }
                        )
                        Spacer(Modifier.height(10.dp))
                        FabMenuItem(
                            icon = Icons.Default.Link,
                            label = "Rezept importieren",
                            containerColor = scheme.secondaryContainer,
                            contentColor = scheme.onSecondaryContainer,
                            onClick = { fabExpanded = false; showImportSheet = true }
                        )
                        Spacer(Modifier.height(10.dp))
                        FabMenuItem(
                            icon = Icons.Default.Edit,
                            label = "Freies Rezept erstellen",
                            containerColor = scheme.primary,
                            contentColor = scheme.onPrimary,
                            onClick = { fabExpanded = false; showCreateSheet = true }
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
                FloatingActionButton(
                    onClick = { fabExpanded = !fabExpanded },
                    containerColor = scheme.primary,
                    contentColor = scheme.onPrimary
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = if (fabExpanded) "Menü schliessen" else "Rezept-Aktionen",
                        modifier = Modifier.rotate(rotation)
                    )
                }
            }
        }
    ) { padding ->
        // fillMaxSize + weight(1f) am Grid/List: sonst misst Column die Lazy-Liste
        // mit unbounded height → alle 251 Items werden gemessen → ANR in RectList/MeasureAndLayout.
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Suchfeld als Pill: Placeholder sitzt direkt neben dem Such-Icon (links-
            // bündig) statt zentriert mit grosser Lücke dazwischen. Kompaktere Höhe
            // spart oben Platz für das Grid darunter.
            OutlinedTextField(
                value = state.query,
                onValueChange = {
                    vm.setQuery(it)
                    if (it.isNotBlank()) vm.clearCookFilters()
                },
                placeholder = {
                    Text(
                        "Rezepte durchsuchen…",
                        fontSize = 13.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    IconButton(onClick = { showCollections = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Folder,
                            "Sammlungen",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 0.dp)
                    .heightIn(max = 44.dp),
                singleLine = true,
                shape = RoundedCornerShape(percent = 50),
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
            )

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

            // Primärleiste: Alle · ★ Favoriten · Filter · Sort (kein endloses Swipen)
            val favCount = state.recipes.count { it.isFavorite }
            val incompleteCount = state.recipes.count { it.isIncomplete() }
            val hasActiveFilters = state.categoryFilter != null ||
                state.platformFilter != null ||
                state.cookedFilter != CookedFilter.ALL ||
                collectionFilterId != null ||
                hideIncomplete
            val activeFilterCount = listOf(
                state.categoryFilter != null,
                state.platformFilter != null,
                state.cookedFilter != CookedFilter.ALL,
                collectionFilterId != null,
                hideIncomplete
            ).count { it }
            val displayedRecipes = state.recipes
                .let { if (favoritesOnly) it.filter { r -> r.isFavorite } else it }
                .let { list ->
                    val cid = collectionFilterId
                    if (cid != null) list.filter { it.collectionId == cid } else list
                }
                .let { if (hideIncomplete) it.filterNot { r -> r.isIncomplete() } else it }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !favoritesOnly && !hasActiveFilters,
                        onClick = {
                            vm.setCategoryFilter(null)
                            vm.setPlatformFilter(null)
                            vm.setCookedFilter(CookedFilter.ALL)
                            favoritesOnly = false
                            collectionFilterId = null
                            hideIncomplete = false
                        },
                        label = { Text("Alle", fontSize = 12.sp) },
                        modifier = Modifier.height(30.dp)
                    )
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = {
                            favoritesOnly = !favoritesOnly
                            if (favoritesOnly) collectionFilterId = null
                        },
                        label = {
                            Text(
                                if (favCount > 0) "★ $favCount" else "★ Favoriten",
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.height(30.dp)
                    )
                    FilterChip(
                        selected = hasActiveFilters,
                        onClick = { showFilterSheet = true },
                        label = {
                            Text(
                                if (activeFilterCount > 0) "Filter · $activeFilterCount" else "Filter",
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = if (hasActiveFilters) {
                            {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        },
                        modifier = Modifier.height(30.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.recipes.isNotEmpty()) {
                    Text(
                        "${displayedRecipes.size} · " +
                            when (state.sort) {
                                RecipeSort.NEWEST   -> "neueste"
                                RecipeSort.NAME     -> "A–Z"
                                RecipeSort.CALORIES -> "kcal"
                            },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 2.dp)
                    )
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

            // Aktive Filter als abwählbare Chips (ohne Scrollen der ganzen Kategorie-Leiste)
            if (hasActiveFilters) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.categoryFilter?.let { cat ->
                        InputChip(
                            selected = true,
                            onClick = { vm.setCategoryFilter(null) },
                            label = { Text("${cat.emoji} ${cat.label}", fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    state.platformFilter?.let { pf ->
                        val label = when (pf) {
                            "instagram" -> "📷 IG"
                            "tiktok" -> "🎵 TikTok"
                            "web" -> "🌐 Web"
                            "ki" -> "✨ KI"
                            "manual" -> "✏️ Frei"
                            else -> pf
                        }
                        InputChip(
                            selected = true,
                            onClick = { vm.setPlatformFilter(null) },
                            label = { Text(label, fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    if (state.cookedFilter == CookedFilter.COOKED) {
                        InputChip(
                            selected = true,
                            onClick = { vm.setCookedFilter(CookedFilter.ALL) },
                            label = { Text("Schon gekocht", fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    if (state.cookedFilter == CookedFilter.NOT_COOKED) {
                        InputChip(
                            selected = true,
                            onClick = { vm.setCookedFilter(CookedFilter.ALL) },
                            label = { Text("Noch nicht", fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    collectionFilterId?.let { cid ->
                        val col = collections.find { it.id == cid }
                        if (col != null) {
                            InputChip(
                                selected = true,
                                onClick = { collectionFilterId = null },
                                label = { Text("${col.emoji} ${col.name}", fontSize = 11.sp) },
                                trailingIcon = {
                                    Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                                },
                                modifier = Modifier.height(28.dp)
                            )
                        }
                    }
                    if (hideIncomplete) {
                        InputChip(
                            selected = true,
                            onClick = { hideIncomplete = false },
                            label = { Text("🧹 Unvollständig aus", fontSize = 11.sp) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    TextButton(
                        onClick = {
                            vm.setCategoryFilter(null)
                            vm.setPlatformFilter(null)
                            vm.setCookedFilter(CookedFilter.ALL)
                            collectionFilterId = null
                            hideIncomplete = false
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Zurücksetzen", fontSize = 11.sp)
                    }
                }
            }

            if (showFilterSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showFilterSheet = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 32.dp)
                    ) {
                        Text(
                            "Filter",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(16.dp))

                        Text("Kategorie", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RecipeCategory.entries.forEach { cat ->
                                FilterChip(
                                    selected = state.categoryFilter == cat,
                                    onClick = {
                                        vm.setCategoryFilter(
                                            if (state.categoryFilter == cat) null else cat
                                        )
                                    },
                                    label = { Text("${cat.emoji} ${cat.label}", fontSize = 12.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Quelle", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "instagram" to "📷 IG",
                                "tiktok" to "🎵 TikTok",
                                "web" to "🌐 Web",
                                "ki" to "✨ KI",
                                "manual" to "✏️ Frei"
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = state.platformFilter == value,
                                    onClick = {
                                        vm.setPlatformFilter(
                                            if (state.platformFilter == value) null else value
                                        )
                                    },
                                    label = { Text(label, fontSize = 12.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Status", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = state.cookedFilter == CookedFilter.COOKED,
                                onClick = {
                                    vm.setCookedFilter(
                                        if (state.cookedFilter == CookedFilter.COOKED) CookedFilter.ALL
                                        else CookedFilter.COOKED
                                    )
                                },
                                label = { Text("Schon gekocht", fontSize = 12.sp) }
                            )
                            FilterChip(
                                selected = state.cookedFilter == CookedFilter.NOT_COOKED,
                                onClick = {
                                    vm.setCookedFilter(
                                        if (state.cookedFilter == CookedFilter.NOT_COOKED) CookedFilter.ALL
                                        else CookedFilter.NOT_COOKED
                                    )
                                },
                                label = { Text("Noch nicht", fontSize = 12.sp) }
                            )
                            if (incompleteCount > 0) {
                                FilterChip(
                                    selected = hideIncomplete,
                                    onClick = { hideIncomplete = !hideIncomplete },
                                    label = { Text("🧹 Unvollständig aus ($incompleteCount)", fontSize = 12.sp) }
                                )
                            }
                        }

                        if (collections.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Text("Sammlungen", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                collections.forEach { col ->
                                    val colCount = state.recipes.count { it.collectionId == col.id }
                                    FilterChip(
                                        selected = collectionFilterId == col.id,
                                        onClick = {
                                            collectionFilterId =
                                                if (collectionFilterId == col.id) null else col.id
                                            if (collectionFilterId != null) favoritesOnly = false
                                        },
                                        label = {
                                            Text(
                                                "${col.emoji} ${col.name}" +
                                                    if (colCount > 0) " ($colCount)" else "",
                                                fontSize = 12.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    vm.setCategoryFilter(null)
                                    vm.setPlatformFilter(null)
                                    vm.setCookedFilter(CookedFilter.ALL)
                                    collectionFilterId = null
                                    hideIncomplete = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Zurücksetzen")
                            }
                            Button(
                                onClick = { showFilterSheet = false },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Fertig")
                            }
                        }
                    }
                }
            }

            if (displayedRecipes.isEmpty()) {
                EmptyState(
                    icon = { Icon(Icons.Default.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    message = if (hideIncomplete) "Keine vollständigen Rezepte" else "Noch keine Rezepte gespeichert",
                    sub = if (hideIncomplete) "Schalte den Filter aus, um alle zu sehen" else "Tippe auf + und füge einen Link ein"
                )
            } else if (useGrid) {
                // Kachelhöhe ergibt sich aus 4:3-Foto + Info-Block – keine erzwungene
                // Zeilenzahl mehr, dafür konsistenter Fotocrop unabhängig von der Bildschirmgrösse.
                // 3-Spalten braucht etwas mehr Luft, sonst wirken Kacheln gequetscht
                val gap = when {
                    window.isTablet -> 12.dp
                    gridColumns >= 3 -> 8.dp
                    gridDensity <= 4 -> 8.dp
                    else -> 6.dp
                }
                val hPad = if (window.isTablet) 16.dp else if (gridColumns >= 3) 10.dp else 10.dp

                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(
                        start = hPad, end = hPad, top = gap,
                        // FAB + Bottom-Nav: genug Platz, dass letzte Zeile nicht unter dem + verschwindet
                        bottom = 100.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
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
                            onToggleFavorite = { vm.toggleFavorite(recipe) },
                            density = gridDensity
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
        AnimatedVisibility(
            visible = fabExpanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { fabExpanded = false }
            )
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
            prefillUrl = when {
                state.instagramBlocked || state.needsScreenshot -> state.blockedUrl
                else -> sharedUrl ?: ""
            },
            isLoading = state.isImporting,
            importPhase = state.importPhase,
            error = state.importError
                ?: if (state.needsScreenshot)
                    "Zutaten unvollständig – bitte Screenshot(s) der Caption anhängen"
                else null,
            openAtManualCaption = state.instagramBlocked && !state.needsScreenshot,
            onImproveImport = if (state.canImproveImport || state.needsScreenshot) {
                { url ->
                    showImportSheet = false
                    vm.clearNeedsScreenshot()
                    vm.clearError()
                    vm.reimportHighQuality(url)
                }
            } else null,
            onImport = { url -> vm.importFromUrl(url) },
            onDismiss = {
                showImportSheet = false
                vm.clearError()
                vm.clearInstagramBlocked()
                vm.clearNeedsScreenshot()
            }
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

    // Sichtbares Loading auch wenn Import-Sheet zu ist (z. B. Re-Import aus Detail)
    if (state.isImporting && !showImportSheet) {
        AlertDialog(
            onDismissRequest = {},
            icon = {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            },
            title = { Text("Import läuft…") },
            text = {
                Text(
                    state.importPhase ?: "Rezept wird analysiert…",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {}
        )
    }

    state.lastImport?.let { recipe ->
        // Während Re-Import keinen Erfolgs-Dialog darüber legen
        if (!state.isImporting) {
            AlertDialog(
                onDismissRequest = vm::clearLastImport,
                icon = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("Rezept importiert!") },
                text = {
                    Column {
                        Text(recipe.displayTitle())
                        if (state.canImproveImport) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Unzufrieden mit dem Ergebnis? Gründlicherer Import dauert länger, liefert aber oft bessere Zutaten.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { selectedRecipe = recipe; vm.clearLastImport() }) {
                        Text("Ansehen")
                    }
                },
                dismissButton = {
                    Row {
                        if (state.canImproveImport) {
                            val improveUrl = recipe.sourceUrl.orEmpty()
                            TextButton(
                                onClick = {
                                    vm.clearLastImport()
                                    vm.reimportHighQuality(improveUrl)
                                }
                            ) { Text("Gründlicher") }
                        }
                        TextButton(onClick = vm::clearLastImport) { Text("OK") }
                    }
                }
            )
        }
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
            allowComponentSplit = recipeAllowsComponentSplit(verifyRecipe)
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
            onRestructureIngredients = { vm.restructureIngredientSections(live) },
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
            ingredientMatches = liveMatches,
            onReimportHighQuality = live.sourceUrl?.takeIf { url ->
                val u = url.lowercase()
                val p = (live.platform ?: "").lowercase()
                p in setOf("instagram", "tiktok") ||
                    "instagram.com" in u || "instagr.am" in u ||
                    "tiktok.com" in u || "vm.tiktok.com" in u
            }?.let { url ->
                {
                    selectedRecipe = null
                    vm.reimportHighQuality(url)
                }
            }
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
            onSave = { stars, nextTime ->
                vm.saveCookFeedback(recipe, stars, nextTime, alsoIncrementCook = true)
                rateAfterDiary = null
            },
            onSkip = {
                vm.recordRecipeCooked(recipe)
                rateAfterDiary = null
            }
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

