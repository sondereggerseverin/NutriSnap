package ch.nutrisnap.app.ui.screens.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest

import android.content.Intent
import android.net.Uri
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
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
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.domain.RecipeGermanMetricConverter
import ch.nutrisnap.app.ui.theme.KEY_RECIPE_RATINGS
import ch.nutrisnap.app.ui.theme.KEY_FRESH_UI
import ch.nutrisnap.app.ui.theme.KEY_FRESH_RECIPE_CARDS
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
/**
 * Sofortige, suspend-freie Korrektur: wenn jede Komponente die vollen Rezept-kcal
 * trägt, proportional zum Kochgewicht aufteilen. Kein DB-Zugriff.
 */
private fun healComponentsProportional(
    recipe: Recipe,
    components: List<RecipeComponent>
): List<RecipeComponent> {
    if (components.size <= 1) return components
    val recipeTotal = recipe.totalCalories ?: 0f
    if (recipeTotal <= 0f) return components
    val duplicated = components.all { c ->
        c.totalCalories > 0f &&
            kotlin.math.abs(c.totalCalories - recipeTotal) / recipeTotal < 0.08f
    }
    val sumTooHigh = components.sumOf { it.totalCalories.toDouble() }.toFloat() > recipeTotal * 1.35f
    if (!duplicated && !sumTooHigh) return components
    val serv = recipe.servings.coerceAtLeast(1).toFloat()
    val sourceProt = (recipe.proteinPerServing ?: 0f) * serv
    val sourceCarbs = (recipe.carbsPerServing ?: 0f) * serv
    val sourceFat = (recipe.fatPerServing ?: 0f) * serv
    val sourceFiber = (recipe.fiberPerServing ?: 0f) * serv
    val wSum = components.sumOf { it.cookedWeightG.toDouble() }.toFloat().coerceAtLeast(1f)
    return components.map { c ->
        val f = if (c.cookedWeightG > 0f) c.cookedWeightG / wSum else 1f / components.size
        c.copy(
            totalCalories = recipeTotal * f,
            proteinG = sourceProt * f,
            carbsG = sourceCarbs * f,
            fatG = sourceFat * f,
            fiberG = sourceFiber * f
        )
    }
}

private fun convertToMetric(text: String): String =
    runCatching { RecipeGermanMetricConverter.convertUnitsToMetric(text) }.getOrDefault(text)

private fun scaleNumbers(line: String, ratio: Float): String {
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
private fun recipeStarsFromPrefs(prefsJson: String?, recipeId: Long): Int {
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
private fun RecipeStarsRow(stars: Int, modifier: Modifier = Modifier) {
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

private data class ParsedIngredient(val amount: String, val unit: String, val name: String)
/** Anzeige-Einheiten im Dropdown (kurz, lesbar). */
private val INGREDIENT_UNITS = listOf("g", "ml", "kg", "l", "EL", "TL", "Stück", "Prise", "Bund", "Dose", "Packung", "Scheibe", "Zehe")
private const val FRACTION_CHARS = "¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
private val UNICODE_FRACTION_VALUES = mapOf(
    '¼' to 0.25f, '½' to 0.5f, '¾' to 0.75f,
    '⅓' to 0.33f, '⅔' to 0.67f,
    '⅕' to 0.2f, '⅖' to 0.4f, '⅗' to 0.6f, '⅘' to 0.8f,
    '⅙' to 0.17f, '⅚' to 0.83f,
    '⅛' to 0.13f, '⅜' to 0.38f, '⅝' to 0.63f, '⅞' to 0.88f
)
/** Yazio/Import-Langformen → kurze Einheit. */
private val UNIT_ALIASES = mapOf(
    "g" to "g", "gram" to "g", "grams" to "g", "gramm" to "g", "gramme" to "g",
    "kg" to "kg", "kilogram" to "kg", "kilogramm" to "kg",
    "ml" to "ml", "milliliter" to "ml", "millilitre" to "ml", "milliliters" to "ml",
    "l" to "l", "liter" to "l", "litre" to "l",
    "tsp" to "TL", "teaspoon" to "TL", "tl" to "TL",
    "tbsp" to "EL", "tablespoon" to "EL", "el" to "EL",
    "stück" to "Stück", "stueck" to "Stück", "piece" to "Stück", "pieces" to "Stück",
    "cookie" to "Stück", "cookies" to "Stück", "pc" to "Stück", "pcs" to "Stück",
    "prise" to "Prise", "pinch" to "Prise",
    "bund" to "Bund", "dose" to "Dose", "packung" to "Packung",
    "scheibe" to "Scheibe", "slice" to "Scheibe", "zehe" to "Zehe"
)
private val UNIT_PATTERN = UNIT_ALIASES.keys.sortedByDescending { it.length }.joinToString("|") {
    Regex.escape(it)
}
private val INGREDIENT_AMOUNT_REGEX = Regex(
    "^((?:\\d+(?:[.,]\\d+)?\\s+)?[$FRACTION_CHARS]|(?:\\d+\\s+)?\\d+/\\d+|\\d+(?:[.,]\\d+)?)" +
        "\\s*($UNIT_PATTERN)?\\s+(.+)",
    RegexOption.IGNORE_CASE
)

private fun normalizeUnit(raw: String): String {
    if (raw.isBlank()) return "g"
    return UNIT_ALIASES[raw.trim().lowercase()] ?: raw.trim()
}

/** "Haferflocken (null)" aus Yazio-Import entfernen. */
private fun cleanIngredientName(raw: String): String =
    raw.trim()
        .replace(Regex("""\s*\(\s*null\s*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\(\s*\)"""), "")
        .trim()

/** Wandelt "1 ¼", "¼", "2/3", "1 1/8" oder "1.5" in einen reinen Dezimalstring um. */
private fun parseAmountToken(raw: String): String {
    val trimmed = raw.trim()
    val fractionChar = trimmed.lastOrNull { it in FRACTION_CHARS }
    if (fractionChar != null) {
        val wholePart = trimmed.dropLast(1).trim().replace(',', '.').toFloatOrNull() ?: 0f
        val value = wholePart + (UNICODE_FRACTION_VALUES[fractionChar] ?: 0f)
        return formatAmount(value)
    }
    if (trimmed.contains('/')) {
        val parts = trimmed.split(Regex("\\s+"))
        val slashParts = parts.last().split('/')
        val num = slashParts.getOrNull(0)?.toFloatOrNull()
        val den = slashParts.getOrNull(1)?.toFloatOrNull()
        if (num != null && den != null && den != 0f) {
            val wholePart = if (parts.size > 1) parts.dropLast(1).joinToString(" ").replace(',', '.').toFloatOrNull() ?: 0f else 0f
            return formatAmount(wholePart + num / den)
        }
    }
    return trimmed.replace(',', '.')
}

private fun formatAmount(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else "%.2f".format(value)

private fun parseIngredientLine(line: String): ParsedIngredient {
    val trimmed = line.trimStart('•', '-', ' ', '*')
    val m = INGREDIENT_AMOUNT_REGEX.find(trimmed)
    if (m != null) {
        return ParsedIngredient(
            amount = parseAmountToken(m.groupValues[1]),
            unit = normalizeUnit(m.groupValues[2]),
            name = cleanIngredientName(m.groupValues[3])
        )
    }
    val loose = Regex(
        """^(\d+(?:[.,]\d+)?)\s*($UNIT_PATTERN)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    ).find(trimmed)
    if (loose != null) {
        return ParsedIngredient(
            amount = loose.groupValues[1].replace(',', '.'),
            unit = normalizeUnit(loose.groupValues[2]),
            name = cleanIngredientName(loose.groupValues[3])
        )
    }
    return ParsedIngredient(amount = "", unit = "g", name = cleanIngredientName(trimmed))
}

private fun joinIngredientLine(parsed: ParsedIngredient): String {
    val amt = parsed.amount.trim()
    val unit = normalizeUnit(parsed.unit)
    val name = cleanIngredientName(parsed.name)
    return if (amt.isNotBlank()) "$amt $unit $name" else name
}

// ── Screen ────────────────────────────────────────────────────────────────────
private fun Recipe.isIncomplete(): Boolean {
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
    val state by vm.uiState.collectAsState()
    val collections by collectionsVm.collections.collectAsState()
    var showImportSheet   by remember { mutableStateOf(false) }
    var showCreateSheet   by remember { mutableStateOf(false) }
    var selectedRecipe    by remember { mutableStateOf<Recipe?>(null) }
    var showVerifySheet    by remember { mutableStateOf(false) }
    var pendingVerify      by remember { mutableStateOf(false) }
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
    val batchState by vm.batchState.collectAsState()
    val budgetScaleState by vm.budgetScaleState.collectAsState()
    val pendingTargetKcal by vm.pendingTargetKcal.collectAsState()
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
    val freshCards = (prefs?.get(KEY_FRESH_RECIPE_CARDS) == true) || (prefs?.get(KEY_FRESH_UI) == true)

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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallFloatingActionButton(onClick = { showCookSheet = true },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                    Icon(Icons.Default.Kitchen, "Was koche ich?")
                }
                Spacer(Modifier.height(8.dp))
                SmallFloatingActionButton(onClick = { showBatchSheet = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Default.PlaylistAdd, "Mehrere Rezepte importieren")
                }
                Spacer(Modifier.height(8.dp))
                SmallFloatingActionButton(onClick = { showImportSheet = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Default.Link, "Rezept importieren")
                }
                Spacer(Modifier.height(8.dp))
                FloatingActionButton(onClick = { showCreateSheet = true },
                    containerColor = MaterialTheme.colorScheme.secondary) {
                    Icon(Icons.Default.Add, "Freies Rezept erstellen", tint = MaterialTheme.colorScheme.onSecondary)
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.query, onValueChange = {
                        vm.setQuery(it)
                        if (it.isNotBlank()) vm.clearCookFilters()
                    },
                    label = { Text("Rezepte durchsuchen") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { showCookSheet = true }) {
                            Icon(Icons.Default.Kitchen, "Was koche ich?")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true, shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { showCollections = true }) {
                    Icon(Icons.Default.Folder, "Sammlungen", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // ── Kategorien ───────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = state.categoryFilter == null,
                    onClick = { vm.setCategoryFilter(null) },
                    label = { Text("Alle", fontSize = 12.sp) }
                )
                RecipeCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = state.categoryFilter == cat,
                        onClick = {
                            vm.setCategoryFilter(if (state.categoryFilter == cat) null else cat)
                        },
                        label = { Text("${cat.emoji} ${cat.label}", fontSize = 12.sp) }
                    )
                }
            }

            if (state.ingredientNeedles.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Zutaten: " + state.ingredientNeedles.joinToString(", "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { vm.clearCookFilters() }) { Text("Zurücksetzen", fontSize = 12.sp) }
                }
            }

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
                        "ki"        to "✨ KI",
                        "manual"    to "✏️ Frei"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = state.platformFilter == value,
                            onClick  = { vm.setPlatformFilter(value) },
                            label    = { Text(label, fontSize = 12.sp) }
                        )
                    }
                    val favCount = state.recipes.count { it.isFavorite }
                    if (favCount > 0 || favoritesOnly) {
                        FilterChip(
                            selected = favoritesOnly,
                            onClick  = {
                                favoritesOnly = !favoritesOnly
                                if (favoritesOnly) collectionFilterId = null
                            },
                            label    = { Text(if (favCount > 0) "★ Favoriten ($favCount)" else "★ Favoriten", fontSize = 12.sp) }
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
                                        "${col.emoji} ${col.name}" +
                                            if (colCount > 0) " ($colCount)" else "",
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
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
            val displayedRecipes = state.recipes
                .let { if (favoritesOnly) it.filter { r -> r.isFavorite } else it }
                .let { list ->
                    val cid = collectionFilterId
                    if (cid != null) list.filter { it.collectionId == cid } else list
                }
                .let { if (hideIncomplete) it.filterNot { r -> r.isIncomplete() } else it }
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
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp).let {
                        PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 80.dp)
                    },
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displayedRecipes, key = { it.id }) { recipe ->
                        if (recipe.isIncomplete()) {
                            // Unvollständige Importe: immer kompakte Warnzeile
                            RecipeCard(recipe,
                                onClick      = { selectedRecipe = recipe },
                                onDelete     = { vm.deleteRecipe(recipe) },
                                onAddToDiary = { addToDiaryRecipe = recipe },
                                onEdit       = { editRecipe = recipe })
                        } else if (freshCards) {
                            ch.nutrisnap.app.ui.components.RecipeCardV2(
                                recipe       = recipe,
                                onClick      = { selectedRecipe = recipe },
                                onAddToDiary = { _ -> addToDiaryRecipe = recipe },
                                onEdit       = { editRecipe = recipe },
                                onDelete     = { vm.deleteRecipe(recipe) },
                                onDuplicate  = { vm.duplicateRecipe(recipe) },
                                onToggleFavorite = { vm.toggleFavorite(recipe) }
                            )
                        } else {
                            // Klassische Karte (Toggle „Fresh Recipe Cards“ aus)
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
    // Nach Analyse automatisch Verify öffnen, falls angefordert
    LaunchedEffect(nutState.result, nutState.isAnalyzing, pendingVerify, verifyRecipe?.id) {
        if (pendingVerify && verifyRecipe != null && verifyResult != null && !nutState.isAnalyzing) {
            showVerifySheet = true
            pendingVerify = false
        }
    }

    val showVerifyNow = showVerifySheet && verifyRecipe != null && verifyResult != null
    if (showVerifyNow) {
        val existingComps by vm.getComponents(verifyRecipe!!.id).collectAsState(initial = emptyList())
        IngredientVerifySheet(
            analysisResult = verifyResult!!,
            recipeName     = verifyRecipe!!.displayTitle(),
            servings       = verifyRecipe.servings,
            initialOverrides = vm.getOverridesFor(verifyRecipe.id),
            onOverridesChanged = { vm.setOverridesFor(verifyRecipe.id, it) },
            onDismiss      = { showVerifySheet = false; pendingVerify = false },
            onConfirm      = { kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium, totalWeightG, ingredientsText ->
                vm.applyVerifiedNutrition(
                    verifyRecipe, kcal, prot, carbs, fat, fiber, sugar, satFat, salt, sodium,
                    totalIngredientWeightG = totalWeightG,
                    ingredientsText = ingredientsText
                )
                showVerifySheet = false
                pendingVerify = false
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
            }?.cookedWeightG
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
        val splitMatches by vm.getMatches(splitRecipe.id).collectAsState(initial = emptyList())
        val splitComps by vm.getComponents(splitRecipe.id).collectAsState(initial = emptyList())
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
        RecipeDetailSheet(
            recipe       = live,
            nutritionState = state.nutritionState,
            onDismiss    = { selectedRecipe = null; vm.clearNutrition() },
            onAddToDiary = { r -> addToDiaryRecipe = r; selectedRecipe = null },
            onEdit       = { editRecipe = live; selectedRecipe = null },
            onAnalyze    = { vm.analyzeNutrition(live) },
            onVerify     = {
                val hasResult = state.nutritionState.result != null &&
                    state.nutritionState.recipeId == live.id &&
                    !state.nutritionState.isAnalyzing
                if (hasResult) {
                    showVerifySheet = true
                } else {
                    pendingVerify = true
                    vm.analyzeNutrition(live)
                }
            },
            onSplitComponents = {
                splitRecipeId = live.id
                showSplitSheet = true
                selectedRecipe = null
            },
            onRecalculateFromOverrides = { vm.recalculateFromOverrides(live) },
            hasStoredOverrides = vm.getOverridesFor(live.id).isNotEmpty(),
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
            onUpdateIngredients = { newText -> vm.updateRecipe(live.copy(ingredients = newText)) },
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
        val components by vm.getComponents(recipe.id).collectAsState(initial = emptyList())
        // Persistente Heilung bei Duplikat-kcal (einmal pro Öffnen)
        LaunchedEffect(recipe.id, components) {
            if (components.size > 1) {
                val healed = vm.healComponentNutrition(recipe, components)
                if (healed != components) {
                    vm.setComponents(recipe.id, healed, updateRecipeTotals = false)
                }
            }
        }
        // Sofort nutzbare, ggf. proportional korrigierte Liste (ohne Suspend)
        val safeComponents = remember(recipe.id, components, recipe.totalCalories) {
            healComponentsProportional(recipe, components)
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
        val existing by vm.getComponents(recipe.id).collectAsState(initial = emptyList())
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

// ── Recipe Card ───────────────────────────────────────────────────────────────
@Composable
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit, onDelete: () -> Unit,
    onAddToDiary: () -> Unit, onEdit: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    val incomplete = recipe.isIncomplete()
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsState(initial = null)
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

@Composable private fun PlatformChip(platform: String) {
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
private fun coilModel(url: String): Any {
    val path = when {
        url.startsWith("file://") -> url.removePrefix("file://")
        url.startsWith("/") && !url.startsWith("http") -> url
        else -> return url
    }
    val f = java.io.File(path)
    return if (f.exists()) f else url
}

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
        "bild"      -> listOf(Color(0xFF5B8DEF), Color(0xFF3A6BC7)) to Icons.Default.Photo
        "manual"    -> listOf(Color(0xFF457B9D), Color(0xFF1D3557)) to Icons.Default.Edit
        else        -> listOf(Color(0xFF2D6A4F), Color(0xFF40916C)) to Icons.Default.RestaurantMenu
    }

@Composable private fun MiniChip(text: String) =
    Text(text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ── Import Sheet ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    prefillUrl: String,
    isLoading: Boolean,
    error: String?,
    importPhase: String? = null,
    openAtManualCaption: Boolean = false,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current; val vm: RecipesViewModel = viewModel()
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var showManual by remember(openAtManualCaption) { mutableStateOf(openAtManualCaption) }
    var manualTitle by remember { mutableStateOf("") }; var manualCaption by remember { mutableStateOf("") }
    var hybridScreenshot by remember { mutableStateOf<Bitmap?>(null) }
    val isInstagram = "instagram.com" in url.lowercase() || "instagr.am" in url.lowercase()

    fun decodePickedBitmap(uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    // Crop nach Galerie-Auswahl (reiner Bild-Import)
    val imageCropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (!result.isSuccessful) return@rememberLauncherForActivityResult
        val cropped = result.uriContent ?: return@rememberLauncherForActivityResult
        runCatching {
            val bitmap = decodePickedBitmap(cropped)
                ?: throw IllegalStateException("Bild konnte nicht geladen werden")
            vm.importFromImage(bitmap)
        }
    }

    // Reiner Bild-Import (ohne Link) → Zuschneiden → Import
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageCropLauncher.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                    activityTitle = "Rezept zuschneiden",
                    cropMenuCropButtonTitle = "Fertig",
                    allowFlipping = true,
                    allowRotation = true,
                    fixAspectRatio = false
                )
            )
        )
    }

    // Hybrid-Screenshot: zuschneiden, dann Bitmap behalten
    val hybridCropLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (!result.isSuccessful) return@rememberLauncherForActivityResult
        val cropped = result.uriContent ?: return@rememberLauncherForActivityResult
        hybridScreenshot = decodePickedBitmap(cropped)
    }

    val hybridScreenshotPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        hybridCropLauncher.launch(
            CropImageContractOptions(
                uri = uri,
                cropImageOptions = CropImageOptions(
                    guidelines = CropImageView.Guidelines.ON,
                    outputCompressFormat = Bitmap.CompressFormat.JPEG,
                    outputCompressQuality = 90,
                    activityTitle = "Screenshot zuschneiden",
                    cropMenuCropButtonTitle = "Fertig",
                    allowFlipping = true,
                    allowRotation = true,
                    fixAspectRatio = false
                )
            )
        )
    }

    LaunchedEffect(error) {
        if (error != null && isInstagram) showManual = true
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            if (!showManual) {
                Text(
                    "Rezept importieren",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Instagram, TikTok oder Webseite",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                // Instagram-specific import button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CameraAlt, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Rezepte aus Instagram importieren",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Link kopieren und unten einfügen",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rezept aus Bild importieren")
                }
                Text(
                    "Screenshot oder Foto einer Rezeptkarte auswählen",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Oder per Link", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(value=url, onValueChange={url=it}, label={Text("URL einfügen")},
                    leadingIcon={Icon(Icons.Default.Link,null)}, modifier=Modifier.fillMaxWidth(), singleLine=true, isError=error!=null)
                if (error != null) Text(error, color=MaterialTheme.colorScheme.error, fontSize=13.sp, modifier=Modifier.padding(top=4.dp))

                // Hybrid: bei Instagram-Link optional Rezept-Screenshot anhängen
                if (isInstagram && url.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Caption leer? Rezept-Screenshot anhängen",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Link liefert Bild + Quelle, Screenshot die Zutaten/Anleitung.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        hybridScreenshotPicker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    enabled = !isLoading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (hybridScreenshot != null) "Screenshot gewählt"
                                        else "Screenshot wählen"
                                    )
                                }
                                if (hybridScreenshot != null) {
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { hybridScreenshot = null },
                                        enabled = !isLoading
                                    ) {
                                        Icon(Icons.Default.Close, "Entfernen")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isInstagram && hybridScreenshot != null) {
                            vm.importHybridFromInstagram(url.trim(), hybridScreenshot)
                        } else {
                            onImport(url.trim())
                        }
                    },
                    enabled = url.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            isLoading && !importPhase.isNullOrBlank() -> importPhase
                            isLoading -> "Importiere…"
                            isInstagram && hybridScreenshot != null -> "Link + Screenshot importieren"
                            else -> "Importieren"
                        }
                    )
                }
                if (isLoading && !importPhase.isNullOrBlank()) {
                    Text(
                        importPhase!!,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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

// ── Batch-Import-Sheet ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchImportSheet(
    state: BatchImportState,
    onAddUrls: (List<String>) -> Unit,
    onRemoveItem: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    var pasteText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Rezepte importieren", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(4.dp))
            Text("Mehrere Links auf einmal importieren",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // Instagram multi-import card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlaylistAdd, null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Mehrere Rezepte auf einmal", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Links aus Zwischenablage einfügen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pasteText, onValueChange = { pasteText = it },
                label = { Text("Links einfügen (ein pro Zeile)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 8
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val urls = UrlExtractor.extractAll(pasteText)
                    if (urls.isNotEmpty()) { onAddUrls(urls); pasteText = "" }
                },
                enabled = UrlExtractor.extractAll(pasteText).isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Zur Warteschlange hinzufügen")
            }

            if (state.items.isNotEmpty()) {
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))

                // Progress bar
                val progress = if (state.items.isNotEmpty()) state.doneCount.toFloat() / state.items.size else 0f
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${state.doneCount}/${state.items.size} importiert",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.isRunning) {
                            Text(
                                "Analysiere ${state.items.size} Rezepte…",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(state.items, key = { it.url }) { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (icon, tint) = when (item.status) {
                                BatchStatus.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                                BatchStatus.RUNNING  -> Icons.Default.Sync      to MaterialTheme.colorScheme.primary
                                BatchStatus.DONE     -> Icons.Default.CheckCircle to MacroColors.calories
                                BatchStatus.ERROR    -> Icons.Default.Error     to MaterialTheme.colorScheme.error
                            }
                            Icon(icon, null, Modifier.size(18.dp), tint = tint)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.resultTitle ?: item.url.take(50),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (item.status == BatchStatus.DONE) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (item.error != null) {
                                    Text(item.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (item.status == BatchStatus.PENDING && !state.isRunning) {
                                IconButton(onClick = { onRemoveItem(item.url) }, Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, "Entfernen", Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStart,
                    enabled = !state.isRunning && state.items.any { it.status != BatchStatus.DONE },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isRunning) "Importiere…" else "Import starten")
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
    onVerify: () -> Unit = {},
    onSplitComponents: () -> Unit = {},
    onRecalculateFromOverrides: () -> Unit = {},
    hasStoredOverrides: Boolean = false,
    onAddToShoppingList: (Recipe) -> Unit = {},
    onUpdateIngredients: (String) -> Unit = {},
    onUpdateCookedWeight: (Float?) -> Unit = {},
    onScaleToBudget: () -> Unit = {},
    onTranslateGermanMetric: () -> Unit = {},
    isTranslating: Boolean = false,
    onEditComponents: () -> Unit = {},
    onStartCooking: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onAssignCollection: () -> Unit = {}
) {
    val context = LocalContext.current
    var servings   by remember(recipe.id) { mutableStateOf(recipe.servings) }
    var metricMode by remember { mutableStateOf(false) }
    val ratio      = servings.toFloat() / recipe.servings.coerceAtLeast(1).toFloat()

    // ── Zutaten-Bearbeitung ─────────────────────────────────────────────────
    var ingredientsEditMode by remember(recipe.id) { mutableStateOf(false) }
    var ingredientLines by remember(recipe.id) { mutableStateOf(recipe.ingredients.lines()) }
    var scanTargetIdx by remember { mutableStateOf<Int?>(null) }
    var showMoreOptions by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(0.94f)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            // ── Kompakter Kopf: Bild + Titel + Meta + 1-Zeilen-Makros ─────────
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RecipeThumbnail(
                        recipe = recipe,
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        shape = RoundedCornerShape(14.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        recipe.displayTitle(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (recipe.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (recipe.isFavorite) "Favorit entfernen" else "Als Favorit",
                            tint = if (recipe.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onAssignCollection, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = "Sammlung zuweisen",
                            tint = if (recipe.collectionId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, "Bearbeiten", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    val cat = recipe.category()
                    if (recipe.mealCategory.isNotBlank() || cat != ch.nutrisnap.app.data.model.RecipeCategory.OTHER) {
                        MetaBadge("${cat.emoji} ${cat.label}")
                    }
                    recipe.prepTimeMinutes?.let { MetaBadge("⏱ $it min") }
                    recipe.platform?.let { MetaBadge("📌 $it") }
                }
                Spacer(Modifier.height(6.dp))
                NutrientSummaryStrip(recipe)
                Spacer(Modifier.height(8.dp))
            }

            // ── Dichte Portionen-Zeile ───────────────────────────────────────
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Portionen", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                            modifier = Modifier.padding(horizontal = 2.dp)
                        ) {
                            IconButton(onClick = { if (servings > 1) servings-- }, Modifier.size(30.dp)) {
                                Icon(Icons.Default.Remove, "-", Modifier.size(15.dp))
                            }
                            Text(
                                "$servings",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                modifier = Modifier.widthIn(min = 24.dp),
                                style = LocalTextStyle.current.copy(textAlign = TextAlign.Center)
                            )
                            IconButton(onClick = { servings++ }, Modifier.size(30.dp)) {
                                Icon(Icons.Default.Add, "+", Modifier.size(15.dp))
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("metrisch", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Switch(
                            checked = metricMode,
                            onCheckedChange = { metricMode = it },
                            modifier = Modifier.height(22.dp).padding(start = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // ── Nährwerte + Aktionen (kompakt) ───────────────────────────────
            item {
                NutritionAnalysisCard(
                    recipe = recipe,
                    nutritionState = nutritionState,
                    servings = servings,
                    ratio = ratio,
                    onAnalyze = onAnalyze,
                    onVerify = onVerify,
                    onSplitComponents = onSplitComponents,
                    onRecalculateFromOverrides = onRecalculateFromOverrides,
                    hasStoredOverrides = hasStoredOverrides
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onAddToDiary(recipe.copy(servings = servings)) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlaylistAdd, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tagebuch", fontSize = 13.sp)
                    }
                    OutlinedButton(
                        onClick = { onAddToShoppingList(recipe.copy(servings = servings)) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.ShoppingCart, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Einkauf", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onStartCooking,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Icon(Icons.Default.RestaurantMenu, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Kochmodus starten", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onEditComponents,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Beilage / Sauce trennen", fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
            }

            // Beschreibung nur wenn sinnvoll und kurz halten
            run {
                val desc = recipe.displayDescription().lines()
                    .filterNot {
                        it.startsWith("📊") ||
                            it.trim().startsWith("Pro Stück:", ignoreCase = true) ||
                            it.trim().startsWith("Pro Portion:", ignoreCase = true)
                    }
                    .joinToString("\n").trim()
                if (desc.isNotBlank()) {
                    item {
                        Text(desc, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // Ingredients — Header/Toggle immer sichtbar, damit auch Rezepte ohne
            // bestehende Zutaten über "Bearbeiten" befüllt werden können.
            run {
                item {
                    SectionHeader("Zutaten", trailing = {
                        TextButton(
                            onClick = {
                                if (ingredientsEditMode) {
                                    val newText = ingredientLines.filter { it.isNotBlank() }.joinToString("\n")
                                    if (newText != recipe.ingredients) onUpdateIngredients(newText)
                                }
                                ingredientsEditMode = !ingredientsEditMode
                            },
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            Icon(if (ingredientsEditMode) Icons.Default.Check else Icons.Default.Edit, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (ingredientsEditMode) "Fertig" else "Bearbeiten", fontSize = 12.sp)
                        }
                    })
                    Spacer(Modifier.height(8.dp))
                }

                if (ingredientsEditMode) {
                    // ── Strukturierte Zutaten-Bearbeitung: Zahl + Einheit + Name ──
                    itemsIndexed(ingredientLines) { idx, line ->
                        val parsed = remember(line) { parseIngredientLine(line) }
                        var amount by remember(line) { mutableStateOf(parsed.amount) }
                        var selectedUnit by remember(line) { mutableStateOf(parsed.unit) }
                        var name by remember(line) { mutableStateOf(parsed.name) }
                        var unitExpanded by remember { mutableStateOf(false) }

                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Amount field
                                OutlinedTextField(
                                    value = amount,
                                    onValueChange = { v ->
                                        amount = v
                                        ingredientLines = ingredientLines.toMutableList().also {
                                            it[idx] = joinIngredientLine(ParsedIngredient(v, selectedUnit, name))
                                        }
                                    },
                                    modifier = Modifier.width(70.dp),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    placeholder = { Text("Menge", fontSize = 11.sp) },
                                    shape = RoundedCornerShape(8.dp)
                                )

                                // Unit dropdown
                                Box {
                                    OutlinedTextField(
                                        value = selectedUnit,
                                        onValueChange = {},
                                        readOnly = true,
                                        modifier = Modifier.width(92.dp).clickable { unitExpanded = true },
                                        singleLine = true,
                                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                        trailingIcon = {
                                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp))
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    DropdownMenu(
                                        expanded = unitExpanded,
                                        onDismissRequest = { unitExpanded = false }
                                    ) {
                                        INGREDIENT_UNITS.forEach { unit ->
                                            DropdownMenuItem(
                                                text = { Text(unit, fontSize = 13.sp) },
                                                onClick = {
                                                    selectedUnit = unit
                                                    unitExpanded = false
                                                    ingredientLines = ingredientLines.toMutableList().also {
                                                        it[idx] = joinIngredientLine(ParsedIngredient(amount, unit, name))
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                // Name field
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { v ->
                                        name = v
                                        ingredientLines = ingredientLines.toMutableList().also {
                                            it[idx] = joinIngredientLine(ParsedIngredient(amount, selectedUnit, v))
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                                    placeholder = { Text("Zutat", fontSize = 11.sp) },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }

                            // Action row: scan + delete
                            Row(
                                Modifier.fillMaxWidth().padding(top = 2.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(onClick = { scanTargetIdx = idx }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.QrCodeScanner, "Produkt scannen", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(
                                    onClick = { ingredientLines = ingredientLines.toMutableList().also { it.removeAt(idx) } },
                                    Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { ingredientLines = ingredientLines + "" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Zutat hinzufügen", fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                } else if (recipe.ingredients.isBlank()) {
                    item {
                        Text("Noch keine Zutaten – tippe auf „Bearbeiten“, um welche hinzuzufügen.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                    }
                } else {
                    // ── Ansicht mit Status-Icons ──
                    // itemsIndexed + Index-Key: identische Zutatenzeilen (z.B. 2× „• 1 shot Espresso“)
                    // würden sonst denselben LazyColumn-Key erzeugen → Crash beim Scrollen.
                    val rawLines = recipe.ingredients.lines()
                    itemsIndexed(
                        rawLines,
                        key = { index, line -> "${index}\u0000$line" }
                    ) { _, rawLine ->
                        if (rawLine.isBlank()) { Spacer(Modifier.height(4.dp)); return@itemsIndexed }
                        val scaled  = if (ratio != 1f) scaleNumbers(rawLine, ratio) else rawLine
                        val display = if (metricMode) convertToMetric(scaled) else scaled
                        val isHeader = !display.startsWith("•") && !display.startsWith("-") &&
                            display.length > 2 && !display.first().isDigit() && !display.startsWith(" ")
                        if (isHeader) {
                            Spacer(Modifier.height(10.dp))
                            Text(display.trimEnd(':'), fontWeight=FontWeight.SemiBold, fontSize=13.sp, color=MaterialTheme.colorScheme.primary)
                        } else {
                            Row(Modifier.fillMaxWidth().padding(vertical=3.dp), verticalAlignment=Alignment.Top) {
                                // Status icon: green check if has amount, red question if not
                                val parsed = parseIngredientLine(display)
                                val hasAmount = parsed.amount.isNotBlank() && parsed.amount.toFloatOrNull() != null
                                Icon(
                                    if (hasAmount) Icons.Default.CheckCircle else Icons.Default.HelpOutline,
                                    null,
                                    tint = if (hasAmount) MacroColors.calories else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(display.trimStart('•','-',' '), fontSize=14.sp, lineHeight=20.sp, modifier=Modifier.weight(1f))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }

            // Instructions
            if (recipe.instructions.isNotBlank()) {
                item { SectionHeader("Zubereitung"); Spacer(Modifier.height(8.dp)) }
                val steps = recipe.instructions.split(Regex("""\n+""")).map{it.trim()}
                    .filter{it.isNotBlank() && !it.matches(Regex("""\d+\.?"""))}
                items(steps.size) { idx ->
                    val step = steps[idx].replaceFirst(Regex("""^\d+[.)]\s*"""), "")
                    Row(Modifier.fillMaxWidth().padding(vertical=6.dp), verticalAlignment=Alignment.Top) {
                        Surface(shape=RoundedCornerShape(50), color=MaterialTheme.colorScheme.primaryContainer, modifier=Modifier.size(26.dp)) {
                            Box(contentAlignment=Alignment.Center) { Text("${idx+1}", fontSize=12.sp, fontWeight=FontWeight.Bold, color=MaterialTheme.colorScheme.onPrimaryContainer) }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(step, fontSize=14.sp, lineHeight=21.sp, modifier=Modifier.weight(1f))
                    }
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

            // ── Weitere Optionen (Gewicht, Deutsch, Budget) — einklappbar ────
            item {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showMoreOptions = !showMoreOptions },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    Icon(
                        if (showMoreOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (showMoreOptions) "Weniger Optionen" else "Mehr Optionen (Gewicht, Deutsch, Budget)",
                        fontSize = 13.sp
                    )
                }
            }
            if (showMoreOptions) {
                item {
                    var cookedText by remember(recipe.id, recipe.cookedWeightG) {
                        mutableStateOf(recipe.cookedWeightG?.takeIf { it > 0f }?.toInt()?.toString() ?: "")
                    }
                    val estimatedRaw = RecipeNutritionAnalyzer.estimateTotalGrams(recipe.ingredients).takeIf { it > 0f }
                    val rawTotal = recipe.totalIngredientWeightG?.takeIf { stored ->
                        estimatedRaw == null || stored <= estimatedRaw * 2.5f
                    } ?: estimatedRaw
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Gericht-Gewicht", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (rawTotal != null) {
                                Text(
                                    "Σ roh: ${rawTotal.toInt()} g · ≈ ${(rawTotal / recipe.servings.coerceAtLeast(1)).toInt()} g/Port.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedTextField(
                                value = cookedText,
                                onValueChange = { cookedText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                                label = { Text("Gewicht nach Kochen (g)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val v = cookedText.replace(',', '.').toFloatOrNull()
                                        onUpdateCookedWeight(v?.takeIf { it > 0f })
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Speichern", fontSize = 13.sp) }
                                if (recipe.cookedWeightG != null) {
                                    TextButton(onClick = {
                                        cookedText = ""
                                        onUpdateCookedWeight(null)
                                    }) { Text("Reset", fontSize = 13.sp) }
                                }
                            }
                            recipe.yieldWeightG()?.let { y ->
                                Text(
                                    "Tracking-Basis: ${y.toInt()} g (${if (recipe.cookedWeightG != null) "gekocht" else "roh"})",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            OutlinedButton(
                                onClick = onEditComponents,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Komponenten (Beilage / Sauce)…", fontSize = 13.sp)
                            }
                            Text(
                                "Getrennt abwiegen oder Meal-Prep gleichmässig aufteilen.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider()
                            OutlinedButton(
                                onClick = onTranslateGermanMetric,
                                enabled = !isTranslating,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isTranslating) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Übersetze…", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Alles auf Deutsch + metrisch", fontSize = 13.sp)
                                }
                            }
                            TextButton(
                                onClick = onScaleToBudget,
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(4.dp)
                            ) {
                                Icon(Icons.Default.PieChart, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Auf mein Restbudget anpassen", fontSize = 13.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // Barcode/Suche/Manuell für eine einzelne Zutaten-Zeile im Bearbeiten-Modus —
        // ersetzt die Zeile durch den exakt gescannten Produktnamen (Menge bleibt erhalten),
        // damit "Neu berechnen" die präzisen Nährwerte findet.
        scanTargetIdx?.let { idx ->
            val currentLine = ingredientLines.getOrNull(idx) ?: ""
            val parsedNameRaw = RecipeNutritionAnalyzer.parseIngredientLine(currentLine)?.name
            val parsedName = if (!parsedNameRaw.isNullOrBlank()) parsedNameRaw else currentLine
            IngredientIdentifySheet(
                ingredientName = parsedName,
                onDismiss = { scanTargetIdx = null },
                onFoodSelected = { food ->
                    val amountG = RecipeNutritionAnalyzer.parseIngredientLine(currentLine)?.amountG?.toInt() ?: 100
                    ingredientLines = ingredientLines.toMutableList().also {
                        it[idx] = "${amountG}g ${food.name}"
                    }
                    scanTargetIdx = null
                }
            )
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
    onVerify: () -> Unit = {},
    onSplitComponents: () -> Unit = {},
    onRecalculateFromOverrides: () -> Unit = {},
    hasStoredOverrides: Boolean = false
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
    val servDiv = recipe.servings.coerceAtLeast(1)
    val fiberPerServ  = result?.totalMicros?.get("fiber")?.let { it / servDiv } ?: recipe.fiberPerServing
    val sugarPerServ  = result?.totalMicros?.get("sugar")?.let { it / servDiv } ?: recipe.sugarPerServing
    val satFatPerServ = result?.totalMicros?.get("saturatedFat")?.let { it / servDiv } ?: recipe.saturatedFatPerServing
    val saltPerServ   = result?.totalMicros?.get("salt")?.let { it / servDiv } ?: recipe.saltPerServing
    val sodiumPerServ = result?.totalMicros?.get("sodium")?.let { it / servDiv } ?: recipe.sodiumPerServing

    val hasMacros = calsPerServ != null || protPerServ != null

    var showDetails by remember { mutableStateOf(false) }
    val hasDetails = fiberPerServ != null || sugarPerServ != null || satFatPerServ != null ||
        saltPerServ != null || result != null

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📊 Nährwerte",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (!isAnalyzing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasStoredOverrides && isForThis) {
                            TextButton(onClick = onRecalculateFromOverrides, contentPadding = PaddingValues(2.dp)) {
                                Text("Auswahl", fontSize = 11.sp)
                            }
                        }
                        if (hasMacros) {
                            TextButton(onClick = onVerify, contentPadding = PaddingValues(2.dp)) {
                                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Verify", fontSize = 11.sp)
                            }
                            TextButton(onClick = onSplitComponents, contentPadding = PaddingValues(2.dp)) {
                                Text("Trennen", fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = onAnalyze, contentPadding = PaddingValues(2.dp)) {
                            Text(if (hasMacros) "Neu" else "Berechnen", fontSize = 11.sp)
                        }
                    }
                }
            }

            if (isAnalyzing) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "Zutaten werden gesucht…",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            } else if (hasMacros) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    calsPerServ?.let { MacroItem("kcal", "${(it * ratio).toInt()}", "") }
                    protPerServ?.let { MacroItem("P", "${(it * ratio).toInt()}", "g") }
                    carbPerServ?.let { MacroItem("K", "${(it * ratio).toInt()}", "g") }
                    fatPerServ?.let { MacroItem("F", "${(it * ratio).toInt()}", "g") }
                }
                // Mikro + Details nur aufklappbar — spart Platz
                if (hasDetails) {
                    TextButton(
                        onClick = { showDetails = !showDetails },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            buildString {
                                fiberPerServ?.let { append("Ballast ${"%.0f".format(it * ratio)}g · ") }
                                append(if (showDetails) "weniger" else "Details")
                            }.trimEnd(' ', '·'),
                            fontSize = 11.sp
                        )
                    }
                    if (showDetails) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            fiberPerServ?.let {
                                SubNutrientRow("Ballaststoffe", "%.1f g".format(it * ratio), highlight = true)
                            }
                            if (result != null && !result.fiberComplete) {
                                Text(
                                    "Ballaststoffe unvollständig (DB oft ohne Fiber-Wert)",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            sugarPerServ?.let { SubNutrientRow("Zucker", "${(it * ratio).toInt()} g") }
                            satFatPerServ?.let { SubNutrientRow("ges. Fett", "${(it * ratio).toInt()} g") }
                            saltPerServ?.let {
                                val mg = sodiumPerServ?.let { na -> (na * ratio * 1000f).toInt() }
                                    ?: (it * ratio * 1000f / 2.5f).toInt()
                                SubNutrientRow("Salz", "${formatSmall(it * ratio)} g (Na ≈ $mg mg)")
                            }
                            result?.let { r ->
                                Text(
                                    "${r.matchedCount}/${r.totalCount} Zutaten gefunden" +
                                        if (r.estimatedCount > 0) " · ${r.estimatedCount} KI-geschätzt" else "",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    "„Berechnen“ für Nährwerte aus der Datenbank.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// (MicronutrientTable + MICRO_META wurden nach ui/components/Components.kt verschoben,
// damit sie auch von DiaryScreen für einzelne Tagebuch-Einträge genutzt werden können.)


private enum class DiaryQuantityUnit { SERVING, GRAM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToDiarySheet(
    recipe: Recipe,
    gramsPerServing: Float? = null,
    yieldTotalG: Float? = null,
    isCookedWeight: Boolean = false,
    onConfirm: (servings: Float, gramsIfGramMode: Float?, meal: MealType, date: java.time.LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var unit by remember { mutableStateOf(if (gramsPerServing != null) DiaryQuantityUnit.GRAM else DiaryQuantityUnit.SERVING) }
    var servingsText by remember { mutableStateOf("1") }
    var gramsText by remember {
        mutableStateOf(
            gramsPerServing?.toInt()?.toString()
                ?: yieldTotalG?.let { (it / recipe.servings.coerceAtLeast(1)).toInt().toString() }
                ?: ""
        )
    }
    var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
    var selectedDate by remember { mutableStateOf(java.time.LocalDate.now()) }

    // Immer in Portionen umrechnen, egal welche Einheit der Nutzer eingibt — die
    // Datenschicht (addRecipeAsMeal) erwartet weiterhin einen Portionsfaktor.
    val servings = when (unit) {
        DiaryQuantityUnit.SERVING -> servingsText.toFloatOrNull()?.coerceAtLeast(0.1f) ?: 1f
        DiaryQuantityUnit.GRAM -> {
            val grams = gramsText.toFloatOrNull()?.coerceAtLeast(1f) ?: (gramsPerServing ?: 1f)
            if (gramsPerServing != null && gramsPerServing > 0f) grams / gramsPerServing else 1f
        }
    }
    val calsPerServ = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val estCals = calsPerServ?.let { it * servings }

    val diarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = diarySheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Ins Tagebuch", fontWeight=FontWeight.Bold, fontSize=18.sp)
            Spacer(Modifier.height(4.dp))
            Text(recipe.displayTitle(), fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            if (yieldTotalG != null && yieldTotalG > 0f) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isCookedWeight)
                        "Gesamt (nach Kochen): ${yieldTotalG.toInt()} g · ${gramsPerServing?.toInt() ?: "–"} g/Portion"
                    else
                        "Σ Zutaten (roh): ${yieldTotalG.toInt()} g · ${gramsPerServing?.toInt() ?: "–"} g/Portion",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Tipp: Nudeln/Reis – „Gewicht nach Kochen“ im Rezept setzen für genaues Tracking.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (unit == DiaryQuantityUnit.SERVING) servingsText else gramsText,
                    onValueChange = { if (unit == DiaryQuantityUnit.SERVING) servingsText=it else gramsText=it },
                    label = { Text("Menge") },
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal), modifier=Modifier.weight(1f), singleLine=true)
                var unitExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={unitExpanded=true}, modifier=Modifier.height(56.dp)) {
                        Text(if (unit == DiaryQuantityUnit.SERVING) "Portion" else "Gramm"); Icon(Icons.Default.ArrowDropDown,null)
                    }
                    DropdownMenu(expanded=unitExpanded, onDismissRequest={unitExpanded=false}) {
                        DropdownMenuItem(text={Text("Portion")}, onClick={unit=DiaryQuantityUnit.SERVING;unitExpanded=false})
                        DropdownMenuItem(
                            text={Text("Gramm")},
                            enabled = gramsPerServing != null,
                            onClick={unit=DiaryQuantityUnit.GRAM;unitExpanded=false}
                        )
                    }
                }
            }
            if (gramsPerServing == null) {
                Spacer(Modifier.height(4.dp))
                Text("Gramm-Eingabe nicht verfügbar — Nährwerte noch nicht analysiert.",
                    fontSize=11.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                Text("Mahlzeit:", fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
                var mealExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={mealExpanded=true}) {
                        Text(selectedMeal.label()); Icon(Icons.Default.ArrowDropDown,null)
                    }
                    DropdownMenu(expanded=mealExpanded, onDismissRequest={mealExpanded=false}) {
                        MealType.values().forEach { meal ->
                            DropdownMenuItem(text={Text(meal.label())}, onClick={selectedMeal=meal;mealExpanded=false})
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Tag:", fontSize=13.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val today = java.time.LocalDate.now()
                listOf(
                    today to "Heute",
                    today.minusDays(1) to "Gestern",
                    today.minusDays(2) to "Vorgestern"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = selectedDate == d,
                        onClick = { selectedDate = d },
                        label = { Text(label, fontSize = 12.sp) }
                    )
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
                Button(onClick={
                    val gramsIfGramMode = if (unit == DiaryQuantityUnit.GRAM)
                        gramsText.replace(',', '.').toFloatOrNull()
                    else null
                    onConfirm(servings, gramsIfGramMode, selectedMeal, selectedDate)
                }, Modifier.weight(1f), enabled=servings>0) {
                    Icon(Icons.Default.Check,null,Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Hinzufügen")
                }
            }
        }
    }
}

@Composable private fun SectionHeader(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
        Text(text, fontWeight=FontWeight.Bold, fontSize=16.sp)
        trailing?.invoke()
    }
    HorizontalDivider(Modifier.padding(top=4.dp), thickness=1.dp, color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable private fun MetaBadge(text: String) {
    Surface(shape=RoundedCornerShape(20.dp), color=MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, fontSize=11.sp, modifier=Modifier.padding(horizontal=8.dp, vertical=3.dp),
            color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Kompakte Nährwert-Zeile pro Portion, angelehnt an swissmilk.ch ("1 Portion enthält: ...").
 *  Immer die Basis-Portion des Rezepts – unabhängig vom Portionen-Stepper. */
@Composable
private fun NutrientSummaryStrip(recipe: Recipe) {
    val calsPerServ = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) }
    val prot = recipe.proteinPerServing
    val carb = recipe.carbsPerServing
    val fat  = recipe.fatPerServing
    if (calsPerServ == null && prot == null && carb == null && fat == null) return

    val parts = buildList {
        calsPerServ?.let { add("${it.toInt()} kcal") }
        fat?.let { add("${it.toInt()} g Fett") }
        carb?.let { add("${it.toInt()} g Kohlenhydrate") }
        prot?.let { add("${it.toInt()} g Eiweiss") }
    }
    if (parts.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "1 Portion enthält: ${parts.joinToString(", ")}",
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}
private fun formatSmall(value: Float): String =
    if (value in 0.01f..0.99f) "< 1" else "%.1f".format(value)

@Composable
private fun SubNutrientRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label, fontSize = 12.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        )
        Text(
            value, fontSize = 12.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
            color = if (highlight) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        )
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

@Composable
private fun RecipeQuickRatingDialog(recipe: Recipe, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var stars by remember { mutableStateOf(0) }
    var tasteOk by remember { mutableStateOf(false) }
    var portionOk by remember { mutableStateOf(false) }
    var again by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wie war’s?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(recipe.displayTitle(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Sterne (fürs nächste Mal)", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 1..5) {
                        IconButton(onClick = { stars = i }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = "$i Sterne",
                                tint = if (i <= stars) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
                Text("Kurz-Feedback (optional)", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = tasteOk, onClick = { tasteOk = !tasteOk }, label = { Text("Schmeckt", fontSize = 11.sp) })
                    FilterChip(selected = portionOk, onClick = { portionOk = !portionOk }, label = { Text("Portion ok", fontSize = 11.sp) })
                    FilterChip(selected = again, onClick = { again = !again }, label = { Text("Nochmal", fontSize = 11.sp) })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = stars > 0,
                onClick = {
                    scope.launch {
                        context.notifDataStore.edit { prefs ->
                            val map = runCatching {
                                JSONObject(prefs[KEY_RECIPE_RATINGS] ?: "{}")
                            }.getOrElse { JSONObject() }
                            val obj = JSONObject()
                            obj.put("stars", stars)
                            obj.put("tasteOk", tasteOk)
                            obj.put("portionOk", portionOk)
                            obj.put("again", again)
                            obj.put("at", System.currentTimeMillis())
                            map.put(recipe.id.toString(), obj)
                            prefs[KEY_RECIPE_RATINGS] = map.toString()
                        }
                        onDismiss()
                    }
                }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Überspringen") }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookWithWhatIHaveSheet(
    onDismiss: () -> Unit,
    onSearch: (ingredients: String, category: RecipeCategory?, targetKcal: Float?) -> Unit
) {
    var ingredients by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<RecipeCategory?>(null) }
    var kcalText by remember { mutableStateOf("") }
    val cookSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = cookSheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Was koche ich?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Zutaten eingeben, die du hast oder nutzen willst — wir filtern deine Rezepte.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            OutlinedTextField(
                value = ingredients,
                onValueChange = { ingredients = it },
                label = { Text("Zutaten (z.B. Cottage Cheese, Banane)") },
                placeholder = { Text("Komma oder neue Zeile") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                minLines = 3
            )
            Text("Kategorie (optional)", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = category == null,
                    onClick = { category = null },
                    label = { Text("Egal") }
                )
                RecipeCategory.entries.filter { it != RecipeCategory.OTHER }.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = if (category == cat) null else cat },
                        label = { Text("${cat.emoji} ${cat.label}", fontSize = 12.sp) }
                    )
                }
            }
            OutlinedTextField(
                value = kcalText,
                onValueChange = { kcalText = it.filter { ch -> ch.isDigit() } },
                label = { Text("Ziel-kcal pro Portion (optional)") },
                placeholder = { Text("z.B. 500") },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Text(
                "Beim Öffnen eines Rezepts wird die Portion automatisch auf dieses Ziel skaliert.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Button(
                onClick = {
                    val kcal = kcalText.toFloatOrNull()
                    onSearch(ingredients, category, kcal)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                enabled = ingredients.isNotBlank() || category != null
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Rezepte finden")
            }
        }
    }
}
