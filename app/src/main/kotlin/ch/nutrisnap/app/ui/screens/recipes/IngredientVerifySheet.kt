package ch.nutrisnap.app.ui.screens.recipes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.FoodSource
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


// ── Main Sheet ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientVerifySheet(
    analysisResult: RecipeNutritionAnalyzer.AnalysisResult,
    recipeName: String,
    servings: Int,
    /** Zuletzt gespeicherte manuelle Anpassungen für dieses Rezept (ViewModel-Zustand,
     *  überlebt Schließen des Sheets und "Neu berechnen"). */
    initialOverrides: Map<String, IngredientOverride> = emptyMap(),
    /** Wird bei JEDER manuellen Änderung sofort aufgerufen, damit das ViewModel
     *  den Stand hält — nicht erst bei "Nährwerte übernehmen". */
    onOverridesChanged: (Map<String, IngredientOverride>) -> Unit = {},
    onDismiss: () -> Unit,
    onConfirm: (
        totalKcal: Float, protein: Float, carbs: Float, fat: Float,
        fiber: Float?, sugar: Float?, saturatedFat: Float?, salt: Float?, sodium: Float?,
        totalIngredientWeightG: Float?,
        /** Aktuelle Zutatenliste aus Verifizierung (gescannte Namen + Mengen). */
        ingredientsText: String
    ) -> Unit,
    /**
     * Optional: Komponenten (Beilage/Sauce) mit Kochgewicht + berechneten Nährwerten.
     * Wird nur aufgerufen, wenn mindestens ein Kochgewicht > 0 eingetragen ist.
     */
    onConfirmComponents: ((List<RecipeComponent>) -> Unit)? = null,
    /** recipeId für gebaute RecipeComponent-Objekte (0 = egal, wird vom Caller gesetzt). */
    recipeIdForComponents: Long = 0L,
    /** Persistiert verifizierte Zutaten als IngredientMatch (für späteren Komponenten-Split). */
    onSaveMatches: ((List<IngredientMatch>) -> Unit)? = null,
    /** Bereits gespeicherte Kochgewichte (Beilage / Sauce) zum Vorausfüllen. */
    initialSideWeightG: Float? = null,
    initialSauceWeightG: Float? = null,
    /** Original-Zutaten-Text des Rezepts (für Abschnitts-Header → componentGroup). */
    recipeIngredients: String = "",
    /** Nur ansehen: keine Edits, kein Override-Schreiben, kein Übernehmen. */
    readOnly: Boolean = false,
    /**
     * false bei Frühstück/Dessert/Getränk: keine Beilage/Sauce-Zuordnung,
     * Zutaten bleiben flach (ein Gericht).
     */
    allowComponentSplit: Boolean = true
) {
    var overrides by remember { mutableStateOf(initialOverrides) }
    var verifyStates by remember {
        mutableStateOf(mergeIngredientOverrides(analysisResult.ingredients, initialOverrides))
    }
    // Abschnitte aus Rezept-Text – Namen 1:1 behalten (kein Collapse auf side/sauce).
    // side/sauce nur als Fallback, wenn Split erlaubt und keine Abschnitte im Text stehen.
    val sectionByLine = remember(recipeIngredients, allowComponentSplit) {
        val sections = parseIngredientSections(recipeIngredients)
        val map = mutableMapOf<String, String>()
        if (allowComponentSplit && sections.size >= 2) {
            for ((sectionName, lines) in sections) {
                val key = sectionName.trim().ifBlank { "Sonstiges" }
                for (line in lines) {
                    map[line.trim().lowercase()] = key
                }
            }
        }
        map
    }
    // Zutat → Abschnittsname | "side" | "sauce" | null (kein Split)
    var groups by remember {
        mutableStateOf(
            mergeIngredientOverrides(analysisResult.ingredients, initialOverrides).associate { s ->
                val line = s.result.line
                line to resolveComponentGroup(
                    line = line,
                    parsedName = s.result.parsed?.name,
                    foodName = s.effectiveFood?.name,
                    sectionByLine = sectionByLine,
                    overrideGroup = initialOverrides[line]?.componentGroup,
                    existingGroup = null,
                    allowComponentSplit = allowComponentSplit
                )
            }
        )
    }
    // Verfügbare Gruppen für →-Button: Abschnitte aus Text, sonst side/sauce (nur wenn Split erlaubt)
    val availableGroupKeys = remember(sectionByLine, groups, allowComponentSplit) {
        if (!allowComponentSplit) emptyList()
        else {
            val fromSections = sectionByLine.values.distinct()
            when {
                fromSections.size >= 2 -> fromSections
                groups.values.filterNotNull().distinct().size >= 2 ->
                    groups.values.filterNotNull().distinct()
                else -> listOf("side", "sauce")
            }
        }
    }
    var sideWeightText by remember {
        mutableStateOf(initialSideWeightG?.takeIf { it > 0f }?.toInt()?.toString() ?: "")
    }
    var sauceWeightText by remember {
        mutableStateOf(initialSauceWeightG?.takeIf { it > 0f }?.toInt()?.toString() ?: "")
    }
    // Merkt sich, für welches AnalysisResult verifyStates zuletzt aufgebaut wurde.
    // Verhindert, dass ein neues Analyse-Ergebnis (z. B. durch "Neu berechnen" in der
    // Rezeptkarte) manuelle Anpassungen überschreibt — diese werden aus `overrides`
    // (ViewModel-gestützt, überlebt auch das Schließen dieses Sheets) neu gemerged.
    var lastMergedResult by remember { mutableStateOf(analysisResult) }
    LaunchedEffect(analysisResult) {
        if (analysisResult !== lastMergedResult) {
            verifyStates = mergeIngredientOverrides(analysisResult.ingredients, overrides)
            lastMergedResult = analysisResult
        }
    }

    // Gruppen stabil halten: bei neuen/geänderten Zeilen Abschnitte aus dem Rezepttext
    // und bestehende Overrides bevorzugen — nicht blind auf side/sauce zurückfallen
    // (sonst werden nach dem Löschen einer Zutat alle Komponenten neu gemischt).
    LaunchedEffect(verifyStates.map { it.result.line }, sectionByLine, allowComponentSplit) {
        val lines = verifyStates.map { it.result.line }.toSet()
        val prev = groups
        groups = verifyStates.associate { s ->
            val line = s.result.line
            line to resolveComponentGroup(
                line = line,
                parsedName = s.result.parsed?.name,
                foodName = s.effectiveFood?.name,
                sectionByLine = sectionByLine,
                overrideGroup = overrides[line]?.componentGroup,
                existingGroup = prev[line],
                allowComponentSplit = allowComponentSplit
            )
        }.filterKeys { it in lines }
    }

    fun updateOverride(line: String, ov: IngredientOverride?) {
        if (readOnly) return
        overrides = if (ov == null) overrides - line else overrides + (line to ov)
        onOverridesChanged(overrides)
    }

    fun setGroup(line: String, group: String) {
        groups = groups + (line to group)
        val existing = overrides[line]
        val state = verifyStates.firstOrNull { it.result.line == line }
        val base = existing ?: state?.toOverride() ?: IngredientOverride()
        updateOverride(line, base.copy(componentGroup = group))
    }

    /** Index der zu scannenden Zutat; -1 = neue Zutat per Scan hinzufügen. */
    var scanTarget by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    // Aufklapp-Status pro Zutat (Zeilen-Key) + Ziel für "direkt in Ballaststoffe-Eingabe springen"
    var expandedLines by remember { mutableStateOf(setOf<String>()) }
    var fiberEditTarget by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun jumpToFiberEdit(line: String) {
        expandedLines = expandedLines + line
        fiberEditTarget = line
        val idx = verifyStates.indexOfFirst { it.result.line == line }
        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx + 1) } // +1: Header-Item davor
    }

    // Recalculate totals
    val totalKcal = verifyStates.sumOf { it.effectiveCalories.toDouble() }.toFloat()
    val totalProt = verifyStates.sumOf { it.effectiveProtein.toDouble() }.toFloat()
    val totalCarbs = verifyStates.sumOf { it.effectiveCarbs.toDouble() }.toFloat()
    val totalFat = verifyStates.sumOf { it.effectiveFat.toDouble() }.toFloat()
    val verifiedCount = verifyStates.count { it.isVerified }

    // Ballaststoffe & Co.: null nur wenn KEINE Zutat überhaupt Daten dazu hatte,
    // sonst (ggf. unvollständige) Summe — nie stillschweigend 0.
    fun microTotal(key: String): Float? =
        verifyStates.mapNotNull { it.effectiveMicros[key] }.takeIf { it.isNotEmpty() }?.sum()
    val totalFiber  = microTotal("fiber")
    val totalSugar  = microTotal("sugar")
    val totalSatFat = microTotal("saturatedFat")
    val totalSalt   = microTotal("salt")
    val totalSodium = microTotal("sodium")
    // Warnung nur bei Zutaten, die typischerweise Ballaststoffe haben (Getreide, Samen,
    // Gemüse, Obst …). Milch, Fleisch, Öl, reines Proteinpulver nicht bemängeln —
    // sonst steht bei fast jedem Rezept eine lange rote Liste.
    val missingFiberStates = verifyStates.filter { s ->
        s.isVerified &&
            !s.effectiveMicros.containsKey("fiber") &&
            expectsDietaryFiber(s.result.parsed?.name ?: s.result.line)
    }
    val fiberComplete = missingFiberStates.isEmpty()

    // Nested ModalBottomSheets crashen oft (Verify + Identify). Deshalb:
    // entweder Identify ODER Verify, nie beides gleichzeitig.
    val scanIdx = scanTarget
    if (scanIdx != null) {
        val isAddNew = scanIdx < 0
        IngredientIdentifySheet(
            ingredientName = if (isAddNew) "Neue Zutat"
            else verifyStates.getOrNull(scanIdx)?.result?.parsed?.name
                ?: verifyStates.getOrNull(scanIdx)?.result?.line
                ?: "Zutat",
            onDismiss = { scanTarget = null },
            onFoodSelected = { food ->
                if (isAddNew) {
                    val amountG = 100f
                    // Lesbarer Key statt "added_TIMESTAMP_…" (landet sonst im Rezepttext)
                    val baseName = food.name.trim().ifBlank { "Zutat" }
                    val line = "${amountG.toInt()} g $baseName"
                    val result = RecipeNutritionAnalyzer.IngredientResult(
                        line = line,
                        parsed = RecipeNutritionAnalyzer.ParsedIngredient(amountG, baseName),
                        foodItem = food,
                        calories = amountG / 100f * (food.calories ?: 0f),
                        protein = amountG / 100f * (food.protein ?: 0f),
                        carbs = amountG / 100f * (food.carbs ?: 0f),
                        fat = amountG / 100f * (food.fat ?: 0f),
                        matched = true
                    )
                    val state = IngredientVerifyState(
                        result = result,
                        override = food,
                        amountOverride = amountG
                    )
                    verifyStates = verifyStates + state
                    updateOverride(line, state.toOverride())
                } else if (scanIdx in verifyStates.indices) {
                    val updated = verifyStates.toMutableList().also {
                        it[scanIdx] = it[scanIdx].copy(override = food)
                    }
                    verifyStates = updated
                    updateOverride(updated[scanIdx].result.line, updated[scanIdx].toOverride())
                }
                scanTarget = null
            }
        )
        return
    }

    // Swipe-to-dismiss aus: Scrollen soll das Sheet nicht schliessen (nur Back / X).
    var allowSheetDismiss by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
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
        modifier = Modifier.fillMaxHeight(0.95f)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        ) {
            // Header
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (readOnly) "Zutaten einsehen" else "Zutaten verifizieren",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { requestDismiss() }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Schliessen")
                        }
                    }
                    Text(
                        "$recipeName · $servings Portion${if (servings != 1) "en" else ""}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))

                    // Summary card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("${safeInt(totalKcal)} kcal", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("$verifiedCount/${verifyStates.size} verifiziert",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MacroChip("P", "${safeInt(totalProt)}g")
                                MacroChip("K", "${safeInt(totalCarbs)}g")
                                MacroChip("F", "${safeInt(totalFat)}g")
                            }
                        }
                    }
                    val totalWeightG = verifyStates.sumOf { it.effectiveAmountG.toDouble() }.toFloat()
                    if (totalWeightG > 0f) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Σ Zutaten: ${totalWeightG.toInt()} g  ·  ≈ ${(totalWeightG / servings.coerceAtLeast(1)).toInt()} g/Portion",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Nach dem Kochen abweichend? Im Rezept „Gewicht nach Kochen“ setzen.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    totalFiber?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ballaststoffe: ${"%.1f".format(it)} g" +
                                if (!fiberComplete) " (teilweise geschätzt)" else "",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Nur bei pflanzlichen Zutaten ohne Fiber-Wert – max. 3 Zeilen, kein Drama
                    if (missingFiberStates.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Ballaststoffe fehlen bei:",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        missingFiberStates.take(3).forEach { s ->
                            val label = s.result.line
                                .replace(Regex("""added_\d+_"""), "")
                                .trimStart('•', '-', ' ')
                            Text(
                                "• $label",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { jumpToFiberEdit(s.result.line) }
                                    .padding(vertical = 1.dp)
                            )
                        }
                        if (missingFiberStates.size > 3) {
                            Text(
                                "… und ${missingFiberStates.size - 3} weitere",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── Zutaten (flach, ohne Beilage/Sauce) ─────────────────────────
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(Modifier.size(28.dp))
                    Text(
                        "Menge",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp)
                    )
                    Text(
                        "Zutat",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "kcal",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp)
                    )
                    Spacer(Modifier.width(if (readOnly) 18.dp else 46.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            }
            // Key muss eindeutig sein: result.line kann doppelt vorkommen
            // (z.B. zweimal „Salz“) → sonst Crash beim Scrollen (LazyColumn).
            itemsIndexed(
                verifyStates,
                key = { index, state -> "${index}\u0000${state.result.line}" }
            ) { index, state ->
                val line = state.result.line
                IngredientVerifyRow(
                    state = state,
                    expanded = expandedLines.contains(line),
                    onToggleExpand = {
                        expandedLines = if (expandedLines.contains(line)) expandedLines - line else expandedLines + line
                    },
                    autoFocusFiberEdit = !readOnly && fiberEditTarget == line,
                    onFiberEditConsumed = { if (fiberEditTarget == line) fiberEditTarget = null },
                    onScan = { if (!readOnly) scanTarget = index },
                    readOnly = readOnly,
                    onDelete = {
                        // Index-basiert: indexOf(state) ist bei NaN-Floats / gleichen Zeilen unzuverlässig
                        if (index in verifyStates.indices) {
                            verifyStates = verifyStates.toMutableList().also { it.removeAt(index) }
                            updateOverride(line, IngredientOverride(deleted = true))
                        }
                    },
                    onManualFiberSaved = { value ->
                        if (index in verifyStates.indices) {
                            val updated = verifyStates.toMutableList().also {
                                it[index] = it[index].copy(manualFiber = value)
                            }
                            verifyStates = updated
                            updateOverride(line, updated[index].toOverride(null))
                            val newTotal = updated.mapNotNull { it.effectiveMicros["fiber"] }.takeIf { it.isNotEmpty() }?.sum()
                            newTotal?.let {
                                android.widget.Toast.makeText(
                                    context,
                                    "Ballaststoffe aktualisiert → neuer Gesamtwert: ${"%.1f".format(it)} g",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onAmountSaved = { value ->
                        if (index in verifyStates.indices) {
                            val updated = verifyStates.toMutableList().also {
                                it[index] = it[index].copy(amountOverride = value)
                            }
                            verifyStates = updated
                            updateOverride(line, updated[index].toOverride(null))
                        }
                    },
                    componentGroup = groups[line],
                    availableGroups = availableGroupKeys,
                    onMoveComponent = {
                        val keys = availableGroupKeys
                        if (keys.isNotEmpty()) {
                            val cur = groups[line] ?: keys.first()
                            val idx = keys.indexOf(cur).let { if (it < 0) 0 else it }
                            val next = keys[(idx + 1) % keys.size]
                            setGroup(line, next)
                        }
                    }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Neue Zutat per Scan/Suche hinzufügen
            if (!readOnly) item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { scanTarget = -1 },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Zutat scannen / hinzufügen")
                }
            }

            // Confirm button (nur im Edit-Modus)
            if (!readOnly) item {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val servDiv = servings.coerceAtLeast(1)
                        val totalWeight = verifyStates.sumOf { it.effectiveAmountG.toDouble() }.toFloat()
                            .takeIf { it > 0f }
                        // Abschnitts-Header nur wenn Split erlaubt und mehrere Gruppen
                        val ingredientsText = buildString {
                            if (!allowComponentSplit) {
                                verifyStates.forEach { s ->
                                    append("• ").append(formatVerifyLineTitle(s)).append('\n')
                                }
                            } else {
                                val orderedKeys = verifyStates
                                    .map { groups[it.result.line] ?: "sauce" }
                                    .distinct()
                                val multi = orderedKeys.size >= 2
                                for (key in orderedKeys) {
                                    if (multi) {
                                        val header = when (key) {
                                            "side" -> "Beilage"
                                            "sauce" -> "Sauce / Fleisch"
                                            else -> key
                                        }
                                        append(header).append('\n')
                                    }
                                    verifyStates.filter { groups[it.result.line] == key }.forEach { s ->
                                        append("• ").append(formatVerifyLineTitle(s)).append('\n')
                                    }
                                    if (multi) append('\n')
                                }
                            }
                        }.trim()
                        // Matches inkl. componentGroup (null bei Frühstück/Dessert)
                        onSaveMatches?.invoke(
                            verifyStates.map { s ->
                                val food = s.effectiveFood
                                IngredientMatch(
                                    recipeId = recipeIdForComponents,
                                    // WICHTIG: Muss exakt dem Text entsprechen, der in recipe.ingredients
                                    // gespeichert wird (siehe ingredientsText oben), sonst erkennt die
                                    // "bereits gematcht"-Prüfung in RecipesScreen die Zutat nicht wieder
                                    // und zeigt sie fälschlich nochmals unter "Weitere" an (Duplikate).
                                    ingredientRaw = formatVerifyLineTitle(s),
                                    // Manuelle DB-/Scan-Auswahl: Produktname ersetzt Rohtext
                                    ingredientName = when {
                                        s.override != null -> food?.name
                                            ?: s.result.parsed?.name
                                            ?: s.result.line
                                        else -> s.result.parsed?.name
                                            ?: food?.name
                                            ?: s.result.line
                                    },
                                    amountGrams = s.effectiveAmountG,
                                    matchedFoodItemId = food?.id?.toLong(),
                                    matchedFoodName = food?.name,
                                    matchedCalories = s.effectiveCalories,
                                    matchedProtein = s.effectiveProtein,
                                    matchedCarbs = s.effectiveCarbs,
                                    matchedFat = s.effectiveFat,
                                    matchSource = when {
                                        s.override != null -> MatchSource.MANUAL
                                        s.isVerified -> MatchSource.DATABASE
                                        else -> MatchSource.UNMATCHED
                                    },
                                    componentGroup = if (allowComponentSplit) groups[s.result.line] else null,
                                    manualAmountG = s.amountOverride,
                                    manualFiberG = s.manualFiber,
                                    isDeleted = false
                                )
                            }
                        )
                        onConfirm(
                            totalKcal / servDiv,
                            totalProt / servDiv,
                            totalCarbs / servDiv,
                            totalFat / servDiv,
                            totalFiber?.div(servDiv),
                            totalSugar?.div(servDiv),
                            totalSatFat?.div(servDiv),
                            totalSalt?.div(servDiv),
                            totalSodium?.div(servDiv),
                            totalWeight,
                            ingredientsText
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Nährwerte übernehmen ($verifiedCount/${verifyStates.size} verifiziert)")
                }
                Text(
                    "Für mehrere Teile: Button „Trennen“ neben Verify nutzen.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    }

}

