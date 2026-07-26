package ch.nutrisnap.app.ui.screens.recipes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer
import kotlinx.coroutines.launch

// ── State for a single ingredient during verification ─────────────────────────

data class IngredientVerifyState(
    val result: RecipeNutritionAnalyzer.IngredientResult,
    // Override set by user scanning/searching/manual
    val override: FoodItem? = null,
    /** Manuell nachgetragene Ballaststoffe für die tatsächlich verwendete Menge (nicht pro 100g).
     *  Hat Vorrang vor jedem aus override/result stammenden Fiber-Wert. */
    val manualFiber: Float? = null
) {
    val isVerified: Boolean get() = override != null || result.matched
    val effectiveFood: FoodItem? get() = override ?: result.foodItem
    val effectiveCalories: Float get() = override?.let {
        (result.parsed?.amountG ?: 100f) / 100f * it.calories
    } ?: result.calories
    val effectiveProtein: Float get() = override?.let {
        (result.parsed?.amountG ?: 100f) / 100f * it.protein
    } ?: result.protein
    val effectiveCarbs: Float get() = override?.let {
        (result.parsed?.amountG ?: 100f) / 100f * it.carbs
    } ?: result.carbs
    val effectiveFat: Float get() = override?.let {
        (result.parsed?.amountG ?: 100f) / 100f * it.fat
    } ?: result.fat
    /** Mikronaehrstoffe (Ballaststoffe etc.) für die tatsächlich verwendete Menge —
     *  bei manuellem Override aus dem gescannten/gesuchten FoodItem, sonst aus der
     *  ursprünglichen Analyse. Nur Werte, die die jeweilige Quelle geliefert hat.
     *  manualFiber überschreibt einen ggf. vorhandenen Fiber-Wert immer. */
    val effectiveMicros: Map<String, Float> get() {
        val base = override?.let { food ->
            val factor = (result.parsed?.amountG ?: 100f) / 100f
            buildMap {
                food.fiber?.let { put("fiber", it * factor) }
                food.sugar?.let { put("sugar", it * factor) }
                food.saturatedFat?.let { put("saturatedFat", it * factor) }
                food.salt?.let { put("salt", it * factor) }
                food.sodium?.let { put("sodium", it * factor) }
            }
        } ?: result.micros
        return manualFiber?.let { base + ("fiber" to it) } ?: base
    }
}

// ── Main Sheet ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientVerifySheet(
    analysisResult: RecipeNutritionAnalyzer.AnalysisResult,
    recipeName: String,
    servings: Int,
    onDismiss: () -> Unit,
    onConfirm: (
        totalKcal: Float, protein: Float, carbs: Float, fat: Float,
        fiber: Float?, sugar: Float?, saturatedFat: Float?, salt: Float?, sodium: Float?
    ) -> Unit
) {
    var verifyStates by remember(analysisResult) {
        mutableStateOf(analysisResult.ingredients.map { IngredientVerifyState(it) })
    }
    var scanTarget by remember { mutableStateOf<Int?>(null) }  // index of ingredient being scanned

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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.95f)
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 32.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Zutaten verifizieren", fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
                                Text("${totalKcal.toInt()} kcal", fontWeight = FontWeight.Bold, fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Text("$verifiedCount/${verifyStates.size} verifiziert",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MacroChip("P", "${totalProt.toInt()}g")
                                MacroChip("K", "${totalCarbs.toInt()}g")
                                MacroChip("F", "${totalFat.toInt()}g")
                            }
                        }
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

            // Ingredient rows — stable keys so Compose recomposes correctly after delete
            items(verifyStates, key = { it.result.line }) { state ->
                val index = verifyStates.indexOf(state)
                val line = state.result.line
                IngredientVerifyRow(
                    state = state,
                    expanded = expandedLines.contains(line),
                    onToggleExpand = {
                        expandedLines = if (expandedLines.contains(line)) expandedLines - line else expandedLines + line
                    },
                    autoFocusFiberEdit = fiberEditTarget == line,
                    onFiberEditConsumed = { if (fiberEditTarget == line) fiberEditTarget = null },
                    onScan = { scanTarget = index },
                    onDelete = {
                        verifyStates = verifyStates.toMutableList().also { it.remove(state) }
                    },
                    onManualFiberSaved = { value ->
                        verifyStates = verifyStates.toMutableList().also {
                            it[index] = it[index].copy(manualFiber = value)
                        }
                    }
                )
                HorizontalDivider(
                    Modifier.padding(horizontal = 16.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }

            // Confirm button
            item {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        val servDiv = servings.coerceAtLeast(1)
                        onConfirm(
                            totalKcal / servDiv,
                            totalProt / servDiv,
                            totalCarbs / servDiv,
                            totalFat / servDiv,
                            totalFiber?.div(servDiv),
                            totalSugar?.div(servDiv),
                            totalSatFat?.div(servDiv),
                            totalSalt?.div(servDiv),
                            totalSodium?.div(servDiv)
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Nährwerte übernehmen ($verifiedCount/${verifyStates.size} verifiziert)")
                }
            }
        }
    }

    // Show scan/search/manual sheet for the target ingredient
    scanTarget?.let { idx ->
        IngredientIdentifySheet(
            ingredientName = verifyStates[idx].result.parsed?.name ?: verifyStates[idx].result.line,
            onDismiss = { scanTarget = null },
            onFoodSelected = { food ->
                verifyStates = verifyStates.toMutableList().also {
                    it[idx] = it[idx].copy(override = food)
                }
                scanTarget = null
            }
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
    onManualFiberSaved: (Float) -> Unit
) {
    val isOverride = state.override != null
    val isMatched  = state.isVerified
    val showActions = expanded
    val fiberValue = state.effectiveMicros["fiber"]
    val isManualFiber = state.manualFiber != null
    var editingFiber by remember { mutableStateOf(false) }
    var fiberInput by remember { mutableStateOf(fiberValue?.let { "%.1f".format(it) } ?: "") }
    val fiberFocusRequester = remember { FocusRequester() }

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

            // Name + source
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.result.line.trimStart('•', '-', ' '),
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
                        "${state.effectiveCalories.toInt()} kcal",
                        fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Direkter Scan-Zugriff — kein Aufklappen nötig für die genaueste Methode
                    IconButton(onClick = onScan, Modifier.size(26.dp)) {
                        Icon(Icons.Default.QrCodeScanner, "Produkt scannen", Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    Icon(
                        if (showActions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Detail- & Action-Bereich — shown when expanded
        if (showActions) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Makro-Details — gleiche Quelle wie die Kalorien-Anzeige oben (effectiveXxx)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("P ${"%.1f".format(state.effectiveProtein)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("K ${"%.1f".format(state.effectiveCarbs)} g", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("·", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("F ${"%.1f".format(state.effectiveFat)} g", fontSize = 12.sp,
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                                Text("${food.calories.toInt()} kcal · ${food.protein.toInt()}g P · ${food.carbs.toInt()}g K",
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
