package ch.nutrisnap.app.ui.screens.recipes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.MatchSource
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.RecipeGermanMetricConverter
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import ch.nutrisnap.app.domain.ParsedIngredient
import ch.nutrisnap.app.domain.INGREDIENT_UNITS
import ch.nutrisnap.app.domain.parseIngredientLine
import ch.nutrisnap.app.domain.joinIngredientLine
import ch.nutrisnap.app.domain.normalizeForCoverageMatch
import ch.nutrisnap.app.ui.components.MicronutrientTable
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_FRESH_RECIPE_DETAIL
import ch.nutrisnap.app.ui.theme.KEY_FRESH_UI
import ch.nutrisnap.app.ui.theme.MacroColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

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
    onViewIngredients: () -> Unit = {},
    onSplitComponents: () -> Unit = {},
    onRecalculateFromOverrides: () -> Unit = {},
    hasStoredOverrides: Boolean = false,
    onAddToShoppingList: (Recipe) -> Unit = {},
    onUpdateIngredients: (String) -> Unit = {},
    onRestructureIngredients: () -> Unit = {},
    onUpdateCookedWeight: (Float?) -> Unit = {},
    onScaleToBudget: () -> Unit = {},
    onTranslateGermanMetric: () -> Unit = {},
    isTranslating: Boolean = false,
    onEditComponents: () -> Unit = {},
    onStartCooking: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onAssignCollection: () -> Unit = {},
    onRetryImage: (() -> Unit)? = null,
    imageRefreshStatus: String? = null,
    /** Persistierte Matches – wenn componentGroup gesetzt, Zutaten gruppiert anzeigen. */
    ingredientMatches: List<ch.nutrisnap.app.data.model.IngredientMatch> = emptyList()
) {
    val context = LocalContext.current
    val prefs by context.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
    val freshRecipeDetail = (prefs?.get(KEY_FRESH_RECIPE_DETAIL) ?: false) ||
        (prefs?.get(KEY_FRESH_UI) ?: false)
    var servings   by remember(recipe.id) { mutableStateOf(recipe.servings) }
    var metricMode by remember { mutableStateOf(false) }
    val ratio      = servings.toFloat() / recipe.servings.coerceAtLeast(1).toFloat()

    // ── Zutaten-Bearbeitung ─────────────────────────────────────────────────
    var ingredientsEditMode by remember(recipe.id) { mutableStateOf(false) }
    var ingredientLines by remember(recipe.id) { mutableStateOf(recipe.ingredients.lines()) }
    var scanTargetIdx by remember { mutableStateOf<Int?>(null) }
    var showMoreOptions by remember { mutableStateOf(false) }

    // Swipe-to-dismiss aus: Scrollen im Sheet soll nicht schliessen.
    // Schliessen nur per System-Back, Scrim oder explizitem X-Button.
    // (sheetGesturesEnabled gibt es in der aktuellen Material3-Version noch nicht)
    var allowSheetDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            // Gesture-Hide blockieren, explizites Schliessen (Back/X/Scrim) erlauben
            if (newValue == SheetValue.Hidden) allowSheetDismiss else true
        }
    )
    fun requestDismiss() {
        allowSheetDismiss = true
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = { requestDismiss() },
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
                        shape = RoundedCornerShape(14.dp),
                        onRetryImage = onRetryImage,
                        imageRefreshStatus = imageRefreshStatus
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
                    IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Schliessen", modifier = Modifier.size(20.dp))
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
                    onViewIngredients = onViewIngredients,
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
                // Frühstück / Dessert / Getränk: kein Komponenten-Split (ein Gericht)
                val splitAllowed = recipeAllowsComponentSplit(recipe)
                if (splitAllowed) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = onEditComponents,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Komponenten trennen", fontSize = 13.sp)
                    }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!ingredientsEditMode && recipe.ingredients.isNotBlank()) {
                                TextButton(
                                    onClick = onRestructureIngredients,
                                    contentPadding = PaddingValues(4.dp)
                                ) {
                                    Icon(Icons.Default.AutoFixHigh, null, Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Abschnitte", fontSize = 12.sp)
                                }
                            }
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
                        }
                    })
                    Spacer(Modifier.height(8.dp))
                }

                if (ingredientsEditMode) {
                    // ── Strukturierte Zutaten-Bearbeitung: Zahl + Einheit + Name ──
                    itemsIndexed(ingredientLines) { idx, line ->
                        val d = line.trim().trimStart('•', '-', '*', ' ').trim()
                        val looksLikeHeader = d.isNotEmpty() && !d.first().isDigit() &&
                            !Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tsp|tbsp|cup|oz)\b""", RegexOption.IGNORE_CASE).containsMatchIn(d) &&
                            (d.endsWith(":") || d.length <= 48)
                        if (looksLikeHeader && !Regex("""\d""").containsMatchIn(d.take(4))) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = d.trimEnd(':'),
                                    onValueChange = { v ->
                                        val header = v.trim().trimEnd(':').ifBlank { "Abschnitt" } + ":"
                                        ingredientLines = ingredientLines.toMutableList().also { it[idx] = header }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                                    placeholder = { Text("Abschnittsname", fontSize = 11.sp) },
                                    shape = RoundedCornerShape(8.dp),
                                    leadingIcon = {
                                        Icon(Icons.Default.Title, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                )
                                IconButton(
                                    onClick = { ingredientLines = ingredientLines.toMutableList().also { it.removeAt(idx) } },
                                    Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, "Löschen", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            return@itemsIndexed
                        }
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
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = {
                                // Header-Zeile ohne Bullet/Menge → wird in der Ansicht als Abschnitt gerendert
                                ingredientLines = ingredientLines + "Abschnitt:"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Title, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Abschnittsüberschrift", fontSize = 13.sp)
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
                    // Wenn Text flach ist, aber Matches componentGroup haben → gruppiert anzeigen.
                    // Ausnahme: Frühstück/Dessert/Getränk nie in Beilage/Sauce splitten.
                    val rawLines = recipe.ingredients.lines().filter { it.isNotBlank() }
                    val textHasHeaders = rawLines.any { line ->
                        val d = line.trim()
                        !d.startsWith("•") && !d.startsWith("-") && d.length > 2 &&
                            !d.first().isDigit() && !d.startsWith(" ") &&
                            !Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl)\b""", RegexOption.IGNORE_CASE).containsMatchIn(d)
                    }
                    // Split: Kategorie ODER ≥2 Abschnitte im Zutaten-Text (auch Dessert)
                    val allowSplit = recipeAllowsComponentSplit(recipe)
                    val groupedFromMatches = allowSplit && !textHasHeaders &&
                        ingredientMatches.any { !it.componentGroup.isNullOrBlank() }
                    val displayBlocks: List<Pair<String?, String>> = if (groupedFromMatches) {
                        val order = linkedMapOf<String, MutableList<String>>()
                        for (m in ingredientMatches.filter { !it.isDeleted }) {
                            val g = m.componentGroup?.trim().orEmpty().ifBlank { "sauce" }
                            // side/sauce-Labels nur bei Gerichten, die Split erlauben
                            val label = when (g) {
                                "side" -> "Beilage"
                                "sauce" -> "Sauce / Fleisch"
                                else -> g
                            }
                            order.getOrPut(label) { mutableListOf() }.add(
                                m.ingredientRaw.ifBlank { m.ingredientName }
                            )
                        }
                        // Robuster Abgleich: nicht auf exakte Text-Gleichheit verlassen (Formatierung von
                        // recipe.ingredients und IngredientMatch.ingredientRaw kann leicht abweichen,
                        // z.B. "200g" vs "200 g"), sondern normalisiert per Namens-Substring prüfen.
                        // Sonst landet fälschlich alles doppelt unter "Weitere".
                        val coveredNames = ingredientMatches
                            .flatMap { listOf(it.ingredientRaw, it.ingredientName) }
                            .map { normalizeForCoverageMatch(it) }
                            .filter { it.isNotBlank() }
                        val rest = rawLines.filter { line ->
                            val norm = normalizeForCoverageMatch(line.trimStart('•', '-', ' '))
                            norm.isNotEmpty() && coveredNames.none { c -> c.length >= 3 && (norm.contains(c) || c.contains(norm)) }
                        }
                        if (rest.isNotEmpty()) order.getOrPut("Weitere") { mutableListOf() }.addAll(rest)
                        buildList {
                            for ((header, lines) in order) {
                                add(header to "")
                                for (line in lines) add(null to line)
                            }
                        }
                    } else {
                        rawLines.map { line ->
                            val d = line.trim()
                            val isHeader = !d.startsWith("•") && !d.startsWith("-") &&
                                d.length > 2 && !d.first().isDigit() && !d.startsWith(" ") &&
                                !Regex("""\d+[.,]?\d*\s*(g|kg|ml|l|el|tl)\b""", RegexOption.IGNORE_CASE).containsMatchIn(d)
                            if (isHeader) d.trimEnd(':') to "" else null to line
                        }
                    }
                    // Lookup echter Match-Status pro Zeile (statt reiner "hat Zahl"-Heuristik):
                    // grün = mit FoodItem verifiziert, orange = Menge erkannt aber ungematcht, grau = kein Match-Versuch.
                    // Normalisierter Substring-Abgleich statt exakter Text-Gleichheit, da sich die
                    // Formatierung von recipe.ingredients und IngredientMatch.ingredientRaw unterscheiden kann.
                    val activeMatches = ingredientMatches.filter { !it.isDeleted }
                    fun findMatchForLine(line: String): ch.nutrisnap.app.data.model.IngredientMatch? {
                        val norm = normalizeForCoverageMatch(line.trimStart('•', '-', ' '))
                        if (norm.isEmpty()) return null
                        return activeMatches.firstOrNull { m ->
                            val rawN = normalizeForCoverageMatch(m.ingredientRaw)
                            val nameN = normalizeForCoverageMatch(m.ingredientName)
                            (rawN.length >= 3 && (norm.contains(rawN) || rawN.contains(norm))) ||
                                (nameN.length >= 3 && (norm.contains(nameN) || nameN.contains(norm)))
                        }
                    }
                    itemsIndexed(
                        displayBlocks,
                        key = { index, pair -> "${index}\u0000${pair.first}\u0000${pair.second}" }
                    ) { _, (header, rawLine) ->
                        if (header != null) {
                            Spacer(Modifier.height(if (freshRecipeDetail) 14.dp else 10.dp))
                            if (freshRecipeDetail) {
                                Text(
                                    header.uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.8.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                HorizontalDivider(
                                    Modifier.padding(top = 4.dp, bottom = 2.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                )
                            } else {
                                Text(
                                    header,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else if (rawLine.isNotBlank()) {
                            val scaled = if (ratio != 1f) scaleNumbers(rawLine, ratio) else rawLine
                            val display = if (metricMode) convertToMetric(scaled) else scaled
                            val parsed = parseIngredientLine(display)
                            val hasAmount = parsed.amount.isNotBlank() &&
                                (parsed.amount.toFloatOrNull() != null || parsed.amount.any { it.isDigit() })
                            val match = findMatchForLine(rawLine)
                            // Grün = verifiziert, Orange = Menge da aber noch kein Produkt-Match
                            // (kein Fehler – nur Hinweis, über Verify nachziehen)
                            val (statusIcon, statusColor, statusLabel) = when {
                                match?.matchedFoodItemId != null ||
                                    (match != null && match.matchSource != MatchSource.UNMATCHED) ->
                                    Triple(Icons.Default.CheckCircle, MacroColors.calories, "Verifiziert")
                                hasAmount ->
                                    Triple(
                                        Icons.Default.RadioButtonUnchecked,
                                        MacroColors.carbs,
                                        "Menge erkannt – noch nicht verifiziert"
                                    )
                                else ->
                                    Triple(
                                        Icons.Default.RadioButtonUnchecked,
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        "Noch nicht gematcht"
                                    )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = if (freshRecipeDetail) 4.dp else 3.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    statusIcon,
                                    statusLabel,
                                    tint = statusColor,
                                    modifier = Modifier.size(16.dp).padding(top = 2.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                if (freshRecipeDetail && hasAmount) {
                                    // Ausgerichtete Spalten: Menge | Einheit | Name
                                    Text(
                                        parsed.amount,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.width(48.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        parsed.unit,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(44.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        parsed.name,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    Text(
                                        display.trimStart('•', '-', ' '),
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
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
internal fun NutritionAnalysisCard(
    recipe: Recipe,
    nutritionState: NutritionState,
    servings: Int,
    ratio: Float,
    onAnalyze: () -> Unit,
    onVerify: () -> Unit = {},
    onViewIngredients: () -> Unit = {},
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
                            TextButton(onClick = onViewIngredients, contentPadding = PaddingValues(2.dp)) {
                                Icon(Icons.Default.Visibility, null, Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Einsehen", fontSize = 11.sp)
                            }
                            TextButton(onClick = onVerify, contentPadding = PaddingValues(2.dp)) {
                                Icon(Icons.Default.QrCodeScanner, null, Modifier.size(13.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("Verify", fontSize = 11.sp)
                            }
                            val canSplit = recipeAllowsComponentSplit(recipe)
                            if (canSplit) {
                                TextButton(onClick = onSplitComponents, contentPadding = PaddingValues(2.dp)) {
                                    Text("Trennen", fontSize = 11.sp)
                                }
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

@Composable internal fun SectionHeader(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
        Text(text, fontWeight=FontWeight.Bold, fontSize=16.sp)
        trailing?.invoke()
    }
    HorizontalDivider(Modifier.padding(top=4.dp), thickness=1.dp, color=MaterialTheme.colorScheme.outlineVariant)
}
@Composable internal fun MetaBadge(text: String) {
    Surface(shape=RoundedCornerShape(20.dp), color=MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, fontSize=11.sp, modifier=Modifier.padding(horizontal=8.dp, vertical=3.dp),
            color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Kompakte Nährwert-Zeile pro Portion, angelehnt an swissmilk.ch ("1 Portion enthält: ...").
 *  Immer die Basis-Portion des Rezepts – unabhängig vom Portionen-Stepper. */
@Composable
internal fun NutrientSummaryStrip(recipe: Recipe) {
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
internal fun formatSmall(value: Float): String =
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

@Composable internal fun MacroItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(value, fontWeight=FontWeight.Bold, fontSize=18.sp, color=MaterialTheme.colorScheme.onPrimaryContainer)
        Text(unit, fontSize=10.sp, color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f))
        Text(label, fontSize=10.sp, color=MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha=0.7f))
    }
}
