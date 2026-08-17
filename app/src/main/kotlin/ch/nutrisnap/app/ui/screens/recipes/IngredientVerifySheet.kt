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

private fun fmtMacro(v: Float): String =
    if (v.isFinite()) "%.1f".format(v) else "0.0"

private fun safeInt(v: Float): Int =
    if (v.isFinite()) v.toInt().coerceAtLeast(0) else 0

// ── State for a single ingredient during verification ─────────────────────────

/**
 * Persistente, leichte Repräsentation einer manuellen Zutaten-Anpassung.
 * Wird (anders als IngredientVerifyState) außerhalb des Sheets im ViewModel
 * gehalten, damit sie weder beim Schließen des Sheets noch bei "Neu berechnen"
 * (frische AnalysisResult-Instanz) verloren geht. Schlüssel ist die Zutaten-
 * Zeile (result.line) — identisch zum Key, den die LazyColumn ohnehin nutzt.
 */
data class IngredientOverride(
    val override: FoodItem? = null,
    val manualFiber: Float? = null,
    val amountOverride: Float? = null,
    /** True = Zutat wurde vom User entfernt; beim Merge übersprungen. */
    val deleted: Boolean = false,
    /** "side" | "sauce" | null = Heuristik beim Öffnen. */
    val componentGroup: String? = null
)

data class IngredientVerifyState(
    val result: RecipeNutritionAnalyzer.IngredientResult,
    // Override set by user scanning/searching/manual
    val override: FoodItem? = null,
    /** Manuell nachgetragene Ballaststoffe für die tatsächlich verwendete Menge (nicht pro 100g).
     *  Hat Vorrang vor jedem aus override/result stammenden Fiber-Wert. */
    val manualFiber: Float? = null,
    /** Manuell korrigierte Menge in Gramm (überschreibt die aus dem Rezepttext geparste Menge). */
    val amountOverride: Float? = null
) {
    val isVerified: Boolean get() = override != null || result.matched
    val effectiveFood: FoodItem? get() = override ?: result.foodItem
    val originalAmountG: Float get() = result.parsed?.amountG ?: 100f
    val effectiveAmountG: Float get() = amountOverride ?: originalAmountG
    /** Verhältnis effektive/ursprüngliche Menge — Fallback-Skalierung, wenn kein FoodItem vorliegt. */
    private val amountRatio: Float get() = effectiveAmountG / originalAmountG.coerceAtLeast(0.1f)

    val effectiveCalories: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.calories ?: 0f) }
            ?: (result.calories * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    val effectiveProtein: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.protein ?: 0f) }
            ?: (result.protein * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    val effectiveCarbs: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.carbs ?: 0f) }
            ?: (result.carbs * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    val effectiveFat: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.fat ?: 0f) }
            ?: (result.fat * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    /** Mikronaehrstoffe (Ballaststoffe etc.) für die tatsächlich verwendete Menge —
     *  bei bekanntem FoodItem (override oder Match) anhand der editierbaren Menge skaliert,
     *  sonst anhand des Mengenverhältnisses aus der ursprünglichen Analyse.
     *  manualFiber überschreibt einen ggf. vorhandenen Fiber-Wert immer. */
    val effectiveMicros: Map<String, Float> get() {
        val base = effectiveFood?.let { food ->
            val factor = effectiveAmountG / 100f
            buildMap {
                food.fiber?.let { put("fiber", it * factor) }
                food.sugar?.let { put("sugar", it * factor) }
                food.saturatedFat?.let { put("saturatedFat", it * factor) }
                food.salt?.let { put("salt", it * factor) }
                food.sodium?.let { put("sodium", it * factor) }
            }
        } ?: result.micros.mapValues { it.value * amountRatio }
        return manualFiber?.let { base + ("fiber" to it) } ?: base
    }

    fun toOverride(componentGroup: String? = null): IngredientOverride =
        IngredientOverride(
            override = override,
            manualFiber = manualFiber,
            amountOverride = amountOverride,
            componentGroup = componentGroup
        )
}

/** Heuristik: Beilage vs. Sauce anhand des Zutatennamens. */
fun defaultComponentGroup(text: String): String {
    val n = text.lowercase()
    val sideKeys = listOf(
        "reis", "basmati", "erbse", "erbsen", "peas", "kartoffel", "nudel", "pasta",
        "quinoa", "couscous", "bulgur", "beilage", "reisnudeln"
    )
    val sauceKeys = listOf(
        "poulet", "huhn", "chicken", "fleisch", "tomate", "rahm", "sahne", "cream",
        "joghurt", "yogurt", "püree", "puree", "gewürz", "garam", "sauce", "butter",
        "masala", "chili", "ingwer", "knoblauch", "zwiebel", "öl", "oil", "speiseöl",
        "fromage", "rôti", "roti"
    )
    val isSide = sideKeys.any { it in n }
    val isSauce = sauceKeys.any { it in n }
    return when {
        isSide && !isSauce -> "side"
        isSauce -> "sauce"
        isSide -> "side"
        else -> "sauce"
    }
}

data class VerifiedTotals(
    val kcal: Float, val protein: Float, val carbs: Float, val fat: Float,
    val fiber: Float?, val sugar: Float?, val saturatedFat: Float?, val salt: Float?, val sodium: Float?
)

/** Baut die aktuelle Zutatenliste aus einem frischen AnalysisResult + gespeicherten
 *  manuellen Anpassungen zusammen. Als Löschung markierte Zutaten werden ausgelassen. */
fun mergeIngredientOverrides(
    ingredients: List<RecipeNutritionAnalyzer.IngredientResult>,
    overrides: Map<String, IngredientOverride>
): List<IngredientVerifyState> = ingredients.mapNotNull { result ->
    val ov = overrides[result.line]
        ?: overrides.entries.firstOrNull { (k, _) ->
            k.trim().equals(result.line.trim(), ignoreCase = true)
        }?.value
    when {
        ov?.deleted == true -> null
        ov != null -> IngredientVerifyState(result, ov.override, ov.manualFiber, ov.amountOverride)
        else -> IngredientVerifyState(result)
    }
}

/**
 * Rekonstruiert Session-Overrides aus persistenten [IngredientMatch]-Zeilen.
 * Key = ingredientRaw (Zeilentext), damit mergeIngredientOverrides greift.
 */
fun matchesToOverrides(matches: List<IngredientMatch>): Map<String, IngredientOverride> {
    if (matches.isEmpty()) return emptyMap()
    return matches.associate { m ->
        val amount = m.manualAmountG ?: m.amountGrams.takeIf { it > 0f }
        val per100 = amount?.takeIf { it > 0f } ?: 100f
        val food = if (m.matchedFoodName != null || m.matchedCalories != null) {
            FoodItem(
                id = m.matchedFoodItemId?.toInt() ?: 0,
                name = m.matchedFoodName ?: m.ingredientName,
                calories = m.matchedCalories?.let { it / per100 * 100f },
                protein = m.matchedProtein?.let { it / per100 * 100f },
                carbs = m.matchedCarbs?.let { it / per100 * 100f },
                fat = m.matchedFat?.let { it / per100 * 100f },
                fiber = m.manualFiberG?.let { it / per100 * 100f }
            )
        } else null
        m.ingredientRaw to IngredientOverride(
            override = food,
            manualFiber = m.manualFiberG,
            amountOverride = m.manualAmountG,
            deleted = m.isDeleted,
            componentGroup = m.componentGroup
        )
    }
}

/** True, wenn Matches manuelle Anpassungen oder Komponenten-Zuordnung tragen. */
fun matchesHaveOverrides(matches: List<IngredientMatch>): Boolean =
    matches.any {
        it.manualAmountG != null || it.manualFiberG != null || it.isDeleted ||
            !it.componentGroup.isNullOrBlank() || it.matchedFoodItemId != null
    }

/** Reine Summierungslogik, wiederverwendbar sowohl im Sheet (Live-Anzeige) als
 *  auch im ViewModel (Button "Auswahl übernehmen", ohne Sheet zu öffnen). */
fun computeVerifiedTotals(states: List<IngredientVerifyState>): VerifiedTotals {
    fun microTotal(key: String): Float? =
        states.mapNotNull { it.effectiveMicros[key] }.takeIf { it.isNotEmpty() }?.sum()
    return VerifiedTotals(
        kcal = states.sumOf { it.effectiveCalories.toDouble() }.toFloat(),
        protein = states.sumOf { it.effectiveProtein.toDouble() }.toFloat(),
        carbs = states.sumOf { it.effectiveCarbs.toDouble() }.toFloat(),
        fat = states.sumOf { it.effectiveFat.toDouble() }.toFloat(),
        fiber = microTotal("fiber"),
        sugar = microTotal("sugar"),
        saturatedFat = microTotal("saturatedFat"),
        salt = microTotal("salt"),
        sodium = microTotal("sodium")
    )
}

/** Anzeigetext: aktuelle Menge + Produktname (ohne interne Keys/Timestamps). */
fun formatVerifyLineTitle(state: IngredientVerifyState): String {
    val g = state.effectiveAmountG
    val amountStr = if (g >= 10f) "${g.toInt()} g" else "${"%.1f".format(g)} g"
    val rawName = state.effectiveFood?.name?.takeIf { it.isNotBlank() }
        ?: state.result.parsed?.name?.takeIf { it.isNotBlank() }
        ?: state.result.line
    val name = rawName
        .trimStart('•', '-', ' ', '➕')
        .replace(Regex("""^added_\d+_"""), "")
        .replace(Regex("""\s*\(\d{10,}\)"""), "")
        .replace(Regex("""(?i)^\d+([.,]\d+)?\s*(g|ml|kg|el|tl|cup|tbsp|tsp)?\s+"""), "")
        .trim()
        .ifBlank { rawName.trim() }
    return "$amountStr $name"
}

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
    readOnly: Boolean = false
) {
    var overrides by remember { mutableStateOf(initialOverrides) }
    var verifyStates by remember {
        mutableStateOf(mergeIngredientOverrides(analysisResult.ingredients, initialOverrides))
    }
    // Abschnitte aus Rezept-Text (z. B. "Hot honey cajun chicken" / "For the sauce")
    val sectionByLine = remember(recipeIngredients) {
        val map = mutableMapOf<String, String>()
        for ((sectionName, lines) in parseIngredientSections(recipeIngredients)) {
            val key = sectionName.trim().lowercase().let { n ->
                when {
                    n.contains("sauce") || n.contains("marinade") || n.contains("dressing") ||
                        n.contains("fleisch") || n.contains("chicken") || n.contains("hähnchen") ||
                        n.contains("poulet") -> "sauce"
                    n.contains("beilage") || n.contains("side") || n.contains("mash") ||
                        n.contains("reis") || n.contains("potato") || n.contains("mais") ||
                        n.contains("bean") || n.contains("sweetcorn") -> "side"
                    else -> sectionName.trim().ifBlank { "sauce" }
                }
            }
            for (line in lines) {
                map[line.trim().lowercase()] = key
            }
        }
        map
    }
    // Zutat → "side" | "sauce" | Abschnittsname
    var groups by remember {
        mutableStateOf(
            mergeIngredientOverrides(analysisResult.ingredients, initialOverrides).associate { s ->
                val line = s.result.line
                val key = "${s.result.line} ${s.result.parsed?.name.orEmpty()} ${s.effectiveFood?.name.orEmpty()}"
                val fromSection = sectionByLine.entries.firstOrNull { (k, _) ->
                    line.lowercase().contains(k) || k.contains(line.lowercase().take(20))
                }?.value
                line to (initialOverrides[line]?.componentGroup
                    ?: fromSection
                    ?: defaultComponentGroup(key))
            }
        )
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

    // Neue Zutaten in groups aufnehmen (z. B. per Scan hinzugefügt)
    LaunchedEffect(verifyStates.map { it.result.line }) {
        val lines = verifyStates.map { it.result.line }.toSet()
        groups = groups.filterKeys { it in lines } + verifyStates
            .filter { it.result.line !in groups }
            .associate { s ->
                val key = "${s.result.line} ${s.result.parsed?.name.orEmpty()} ${s.effectiveFood?.name.orEmpty()}"
                s.result.line to (overrides[s.result.line]?.componentGroup ?: defaultComponentGroup(key))
            }
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
    // Zutaten, die die Ballaststoff-Warnung auslösen: verifiziert, aber ohne Fiber-Wert
    val missingFiberStates = verifyStates.filter { it.isVerified && !it.effectiveMicros.containsKey("fiber") }
    val fiberComplete = verifyStates.filter { it.isVerified }
        .let { verified -> verified.isNotEmpty() && missingFiberStates.isEmpty() }

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
                    // Interner Key stabil & eindeutig; Anzeige ohne Timestamp
                    val line = "added_${System.currentTimeMillis()}_${food.name}"
                    val result = RecipeNutritionAnalyzer.IngredientResult(
                        line = line,
                        parsed = RecipeNutritionAnalyzer.ParsedIngredient(amountG, food.name),
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
                                if (!fiberComplete) " (unvollständig – manuell prüfen)" else "",
                            fontSize = 12.sp,
                            fontWeight = if (!fiberComplete) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (!fiberComplete) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!fiberComplete && missingFiberStates.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (missingFiberStates.size == 1) "Unvollständige Zutat:" else "Unvollständige Zutaten:",
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                        missingFiberStates.forEach { s ->
                            Text(
                                "• ${s.result.line.trimStart('•', '-', ' ')} – Ballaststoffe fehlen",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { jumpToFiberEdit(s.result.line) }
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── Zutaten (flach, ohne Beilage/Sauce) ─────────────────────────
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
                    componentGroup = null,
                    onMoveComponent = null
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
                        // Abschnitts-Header aus groups beibehalten (nicht flach speichern!)
                        val ingredientsText = buildString {
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
                        }.trim()
                        // Matches inkl. componentGroup aus Abschnitten/UI persistieren
                        onSaveMatches?.invoke(
                            verifyStates.map { s ->
                                val food = s.effectiveFood
                                IngredientMatch(
                                    recipeId = recipeIdForComponents,
                                    ingredientRaw = s.result.line,
                                    ingredientName = s.result.parsed?.name
                                        ?: food?.name
                                        ?: s.result.line,
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
                                    componentGroup = groups[s.result.line],
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

@Composable
private fun ComponentSectionHeader(
    title: String,
    subtitle: String,
    weightText: String,
    onWeightChange: (String) -> Unit,
    groupKcal: Float
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (groupKcal > 0f) {
            Text(
                "${safeInt(groupKcal)} kcal aus Zutaten",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        val parsed = weightText.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
        OutlinedTextField(
            value = weightText,
            onValueChange = onWeightChange,
            label = { Text("Kochgewicht (g)") },
            supportingText = {
                Text(
                    if (parsed != null)
                        "Eingetragen: ${parsed.toInt()} g · wird automatisch gespeichert"
                    else
                        "Gesamtgewicht nach dem Kochen (ohne Topf) – wird automatisch gespeichert"
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Single ingredient row ─────────────────────────────────────────────────────

@Composable
private fun IngredientVerifyRow(
    state: IngredientVerifyState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    autoFocusFiberEdit: Boolean,
    onFiberEditConsumed: () -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit,
    onManualFiberSaved: (Float) -> Unit,
    onAmountSaved: (Float) -> Unit,
    componentGroup: String? = null,
    onMoveComponent: (() -> Unit)? = null,
    readOnly: Boolean = false
) {
    val isOverride = state.override != null
    val isMatched  = state.isVerified
    val showActions = expanded
    val fiberValue = state.effectiveMicros["fiber"]
    val isManualFiber = state.manualFiber != null
    var editingFiber by remember { mutableStateOf(false) }
    var fiberInput by remember { mutableStateOf(fiberValue?.let { "%.1f".format(it) } ?: "") }
    val fiberFocusRequester = remember { FocusRequester() }
    var editingAmount by remember { mutableStateOf(false) }
    // Keyed by recipe line + effective amount so the field always follows the recipe
    // (previously remember {} kept a stale 300g when the line said 350g).
    var amountInput by remember(state.result.line, state.effectiveAmountG) {
        mutableStateOf("%.0f".format(state.effectiveAmountG))
    }
    val amountFocusRequester = remember { FocusRequester() }

    fun parseAmountInput(text: String): Float? {
        val cleaned = text.trim()
            .replace(',', '.')
            .replace(Regex("""(?i)\s*(g|gramm|grams?|ml)\s*$"""), "")
            .trim()
        return cleaned.toFloatOrNull()?.takeIf { it > 0f }
    }

    fun saveAmount() {
        parseAmountInput(amountInput)?.let { onAmountSaved(it) }
        editingAmount = false
    }

    LaunchedEffect(autoFocusFiberEdit) {
        if (autoFocusFiberEdit) {
            editingFiber = true
            fiberFocusRequester.requestFocus()
            onFiberEditConsumed()
        }
    }

    fun saveFiber() {
        fiberInput.replace(',', '.').toFloatOrNull()?.let { onManualFiberSaved(it) }
        editingFiber = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status icon
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isOverride -> Color(0xFF1565C0).copy(alpha = 0.15f)
                            isMatched  -> Color(0xFF2E7D32).copy(alpha = 0.12f)
                            else       -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isOverride -> Icons.Default.QrCodeScanner
                        isMatched  -> Icons.Default.Check
                        else       -> Icons.Default.QuestionMark
                    },
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = when {
                        isOverride -> Color(0xFF1565C0)
                        isMatched  -> Color(0xFF2E7D32)
                        else       -> MaterialTheme.colorScheme.error
                    }
                )
            }

            // Name + source — effektive Menge (350→450) sofort in der Zeile
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatVerifyLineTitle(state),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2
                )
                Text(
                    text = when {
                        isOverride -> "✓ ${state.override?.name ?: ""} (gescannt/gesucht)"
                        isMatched  -> "✓ ${state.effectiveFood?.name ?: "gematcht"}"
                        else       -> "Nicht gefunden · Tippen für Optionen"
                    },
                    fontSize = 11.sp,
                    color = when {
                        isOverride -> Color(0xFF1565C0)
                        isMatched  -> Color(0xFF2E7D32)
                        else       -> MaterialTheme.colorScheme.error
                    }
                )
            }

            // Calories + direct scan + chevron
            Column(horizontalAlignment = Alignment.End) {
                if (state.effectiveCalories > 0f) {
                    Text(
                        "${safeInt(state.effectiveCalories)} kcal",
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Direkter Scan-Zugriff — kein Aufklappen nötig für die genaueste Methode
                    if (!readOnly) {
                        IconButton(onClick = onScan, Modifier.size(26.dp)) {
                            Icon(Icons.Default.QrCodeScanner, "Produkt ändern (Scan/Suche/Manuell)", Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Icon(
                        if (showActions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Schnell umhängen: Beilage ↔ Sauce
        if (onMoveComponent != null && componentGroup != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 60.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                AssistChip(
                    onClick = onMoveComponent,
                    label = {
                        Text(
                            if (componentGroup == "side") "→ Sauce / Fleisch" else "→ Beilage",
                            fontSize = 11.sp
                        )
                    },
                    modifier = Modifier.height(28.dp)
                )
            }
        }

        // Detail- & Action-Bereich — shown when expanded
        if (showActions) {
            // Kein nested verticalScroll in LazyColumn-Item (Crash-Ursache)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Menge — immer aus dem Rezept (parsed), editierbar mit +/- und Freitext ("100g")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Menge: ${"%.0f".format(state.effectiveAmountG)} g",
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                        if (state.amountOverride != null &&
                            kotlin.math.abs(state.amountOverride - state.originalAmountG) > 0.5f
                        ) {
                            Text(
                                "Rezept: ${"%.0f".format(state.originalAmountG)} g",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // − 10g
                    IconButton(
                        onClick = {
                            val next = (state.effectiveAmountG - 10f).coerceAtLeast(1f)
                            onAmountSaved(next)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Remove, "−10 g", Modifier.size(18.dp))
                    }
                    // + 10g
                    IconButton(
                        onClick = {
                            onAmountSaved(state.effectiveAmountG + 10f)
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, "+10 g", Modifier.size(18.dp))
                    }
                    // Freitext (100 / 100g)
                    IconButton(onClick = {
                        amountInput = "%.0f".format(state.effectiveAmountG)
                        editingAmount = true
                    }) {
                        Icon(Icons.Default.Edit, "Menge tippen", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (editingAmount) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 8.dp)) {
                        OutlinedTextField(
                            value = amountInput,
                            onValueChange = { amountInput = it },
                            label = { Text("Menge (g)", fontSize = 11.sp) },
                            placeholder = { Text("z.B. 350 oder 350g") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(amountFocusRequester),
                            shape = RoundedCornerShape(10.dp),
                            isError = amountInput.isNotBlank() && parseAmountInput(amountInput) == null
                        )
                        IconButton(onClick = { saveAmount() }) {
                            Icon(Icons.Default.Check, "Speichern", tint = MaterialTheme.colorScheme.primary)
                        }
                        // Zurück auf Rezeptmenge
                        TextButton(onClick = {
                            onAmountSaved(state.originalAmountG)
                            amountInput = "%.0f".format(state.originalAmountG)
                            editingAmount = false
                        }) {
                            Text("Rezept", fontSize = 11.sp)
                        }
                    }
                    LaunchedEffect(editingAmount) {
                        if (editingAmount) amountFocusRequester.requestFocus()
                    }
                }

                // Makro-Details — gleiche Quelle wie die Kalorien-Anzeige oben (effectiveXxx)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("P ${fmtMacro(state.effectiveProtein)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("K ${fmtMacro(state.effectiveCarbs)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("F ${fmtMacro(state.effectiveFat)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Ballaststoffe — hervorgehoben, ggf. mit manueller Eingabe
                if (editingFiber) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = fiberInput,
                            onValueChange = { fiberInput = it },
                            label = { Text("Ballaststoffe (g)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f).focusRequester(fiberFocusRequester),
                            shape = RoundedCornerShape(10.dp)
                        )
                        IconButton(onClick = { saveFiber() }) {
                            Icon(Icons.Default.Check, "Speichern", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (fiberValue != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Ballaststoffe: ${"%.1f".format(fiberValue)} g",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        if (isManualFiber) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Default.Edit, "manuell überschrieben", Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(" manuell", fontSize = 10.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    Text(
                        "Ballaststoffe: – (fehlen)",
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        "✎ Ballaststoffe manuell eintragen",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { editingFiber = true }
                            .padding(top = 2.dp)
                    )
                }

                // Optional: Zucker & Salz, falls vorhanden
                val sugar = state.effectiveMicros["sugar"]
                val salt = state.effectiveMicros["salt"]
                if (sugar != null || salt != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            sugar?.let { "Zucker ${"%.1f".format(it)} g" },
                            salt?.let { "Salz ${"%.1f".format(it)} g" }
                        ).joinToString("   ·   "),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!readOnly) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Keep as-is (close actions)
                    OutlinedButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Belassen", fontSize = 12.sp)
                    }
                    // Delete
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Löschen", fontSize = 12.sp)
                    }
                }
                } // !readOnly
            }
        }
    }
}

@Composable
private fun SmallScanButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.height(28.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(Icons.Default.QrCodeScanner, null, Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSecondary)
            Text("Scannen", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSecondary)
        }
    }
}

@Composable
private fun MacroChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.6f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

// ── Identify Sheet: Barcode / Search / Manual ─────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientIdentifySheet(
    ingredientName: String,
    onDismiss: () -> Unit,
    onFoodSelected: (FoodItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<IdentifyMode>(IdentifyMode.Choose) }

    val identifySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = identifySheetState
    ) {
        when (mode) {
            IdentifyMode.Choose -> IdentifyChooseScreen(
                ingredientName = ingredientName,
                onBarcode = { mode = IdentifyMode.Barcode },
                onSearch  = { mode = IdentifyMode.Search(ingredientName) },
                onManual  = { mode = IdentifyMode.Manual }
            )
            IdentifyMode.Barcode -> BarcodeLookupScreen(
                onBarcodeScanned = { barcode ->
                    scope.launch {
                        val repo = FoodItemRepository(NutriDatabase.getInstance(context))
                        val food = runCatching { repo.searchAll(barcode).firstOrNull() }.getOrNull()
                        if (food != null) onFoodSelected(food)
                        else mode = IdentifyMode.Search(barcode)
                    }
                },
                onBack = { mode = IdentifyMode.Choose }
            )
            is IdentifyMode.Search -> FoodSearchScreen(
                query = (mode as IdentifyMode.Search).query,
                onFoodSelected = onFoodSelected,
                onBack = { mode = IdentifyMode.Choose }
            )
            IdentifyMode.Manual -> ManualEntryScreen(
                name = ingredientName,
                onConfirm = onFoodSelected,
                onBack = { mode = IdentifyMode.Choose }
            )
        }
    }
}

sealed class IdentifyMode {
    object Choose  : IdentifyMode()
    object Barcode : IdentifyMode()
    data class Search(val query: String) : IdentifyMode()
    object Manual  : IdentifyMode()
}

// ── Choose screen ─────────────────────────────────────────────────────────────

@Composable
private fun IdentifyChooseScreen(
    ingredientName: String,
    onBarcode: () -> Unit,
    onSearch: () -> Unit,
    onManual: () -> Unit
) {
    Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
        Text("Zutat identifizieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(ingredientName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))

        OptionRow(
            icon = Icons.Default.QrCodeScanner,
            title = "Barcode scannen",
            subtitle = "Kamera öffnet sich — genaueste Methode",
            badge = "Genauest",
            badgeColor = Color(0xFF2E7D32),
            onClick = onBarcode
        )
        Spacer(Modifier.height(8.dp))
        OptionRow(
            icon = Icons.Default.Search,
            title = "In Datenbank suchen",
            subtitle = "OpenFoodFacts · über 3 Millionen Produkte",
            badge = "Alternativ",
            badgeColor = Color(0xFF1565C0),
            onClick = onSearch
        )
        Spacer(Modifier.height(8.dp))
        OptionRow(
            icon = Icons.Default.Edit,
            title = "Manuell eingeben",
            subtitle = "kcal, Protein, Kohlenhydrate, Fett selbst tippen",
            badge = null,
            badgeColor = Color.Transparent,
            onClick = onManual
        )
    }
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            badge?.let {
                Surface(shape = RoundedCornerShape(6.dp), color = badgeColor.copy(alpha = 0.12f)) {
                    Text(it, fontSize = 10.sp, color = badgeColor, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}

// ── Barcode Lookup (wraps existing BarcodeScannerScreen inline) ───────────────

@Composable
private fun BarcodeLookupScreen(
    onBarcodeScanned: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var scanned by remember { mutableStateOf<String?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* handled in BarcodeScannerScreen */ }

    Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
            }
            Text("Barcode scannen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(12.dp))

        // Inline camera preview using the existing BarcodeScannerScreen composable
        Box(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen(
                onBarcodeDetected = { barcode ->
                    scanned = barcode
                    onBarcodeScanned(barcode)
                },
                onNavigateBack = onBack
            )
        }

        scanned?.let {
            Spacer(Modifier.height(12.dp))
            Text("Barcode: $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Text("Suche in Datenbank…", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ── Food Search Screen ────────────────────────────────────────────────────────

@Composable
private fun FoodSearchScreen(
    query: String,
    onFoodSelected: (FoodItem) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchText by remember { mutableStateOf(query) }
    var results by remember { mutableStateOf<List<FoodItem>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Auto-search on open
    LaunchedEffect(Unit) {
        if (query.isNotBlank()) {
            isSearching = true
            errorMsg = null
            runCatching {
                val repo = FoodItemRepository(NutriDatabase.getInstance(context))
                results = repo.searchAll(query)
            }.onFailure { errorMsg = it.message }
            isSearching = false
        }
    }

    Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Suchen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            label = { Text("Lebensmittel suchen") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                IconButton(onClick = {
                    scope.launch {
                        isSearching = true; errorMsg = null
                        runCatching {
                            val repo = FoodItemRepository(NutriDatabase.getInstance(context))
                            results = repo.searchAll(searchText)
                        }.onFailure { errorMsg = it.message }
                        isSearching = false
                    }
                }) { Icon(Icons.Default.Send, "Suchen") }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(8.dp))

        when {
            isSearching -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            errorMsg != null -> Text(
                "Fehler: $errorMsg", color = MaterialTheme.colorScheme.error, fontSize = 13.sp
            )
            results.isEmpty() && !isSearching -> Text(
                "Keine Ergebnisse — versuche einen anderen Begriff",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> {
                results.forEach { food ->
                    Card(
                        onClick = { onFoodSelected(food) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(food.name, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                Text("${food.calories?.toInt() ?: "–"} kcal · ${food.protein?.toInt() ?: "–"}g P · ${food.carbs?.toInt() ?: "–"}g K",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Add, "Auswählen", Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

// ── Manual Entry Screen ───────────────────────────────────────────────────────

@Composable
private fun ManualEntryScreen(
    name: String,
    onConfirm: (FoodItem) -> Unit,
    onBack: () -> Unit
) {
    var foodName by remember { mutableStateOf(name) }
    var kcal     by remember { mutableStateOf("") }
    var protein  by remember { mutableStateOf("") }
    var carbs    by remember { mutableStateOf("") }
    var fat      by remember { mutableStateOf("") }

    Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Manuell eingeben", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text("Nährwerte pro 100g eingeben", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))

        OutlinedTextField(value = foodName, onValueChange = { foodName = it },
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            shape = RoundedCornerShape(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroField("kcal", kcal, { kcal = it }, Modifier.weight(1f))
            MacroField("Protein g", protein, { protein = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroField("Kohlenhy. g", carbs, { carbs = it }, Modifier.weight(1f))
            MacroField("Fett g", fat, { fat = it }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                onConfirm(FoodItem(
                    name     = foodName.ifBlank { name },
                    calories = kcal.toFloatOrNull() ?: 0f,
                    protein  = protein.toFloatOrNull() ?: 0f,
                    carbs    = carbs.toFloatOrNull() ?: 0f,
                    fat      = fat.toFloatOrNull() ?: 0f,
                    source   = FoodSource.MANUAL
                ))
            },
            enabled = kcal.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Übernehmen")
        }
    }
}

@Composable
private fun MacroField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true, modifier = modifier, shape = RoundedCornerShape(10.dp)
    )
}
