package ch.nutrisnap.app.ui.screens.recipes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
    val override: FoodItem? = null
) {
    val isVerified: Boolean get() = override != null || result.matched
    val effectiveFood: FoodItem? get() = override ?: result.foodItem
    val effectiveCalories: Float get() = override?.let {
        (result.parsed?.amountG ?: 100f) / 100f * it.calories
    } ?: result.calories
}

// ── Main Sheet ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientVerifySheet(
    analysisResult: RecipeNutritionAnalyzer.AnalysisResult,
    recipeName: String,
    servings: Int,
    onDismiss: () -> Unit,
    onConfirm: (totalKcal: Float, protein: Float, carbs: Float, fat: Float) -> Unit
) {
    var verifyStates by remember(analysisResult) {
        mutableStateOf(analysisResult.ingredients.map { IngredientVerifyState(it) })
    }
    var scanTarget by remember { mutableStateOf<Int?>(null) }  // index of ingredient being scanned

    // Recalculate totals
    val totalKcal = verifyStates.sumOf { it.effectiveCalories.toDouble() }.toFloat()
    val totalProt = verifyStates.sumOf { (it.override?.let { f ->
        (it.result.parsed?.amountG ?: 100f) / 100f * f.protein } ?: it.result.protein).toDouble() }.toFloat()
    val totalCarbs = verifyStates.sumOf { (it.override?.let { f ->
        (it.result.parsed?.amountG ?: 100f) / 100f * f.carbs } ?: it.result.carbs).toDouble() }.toFloat()
    val totalFat = verifyStates.sumOf { (it.override?.let { f ->
        (it.result.parsed?.amountG ?: 100f) / 100f * f.fat } ?: it.result.fat).toDouble() }.toFloat()
    val verifiedCount = verifyStates.count { it.isVerified }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.95f)
    ) {
        LazyColumn(
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
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Ingredient rows — stable keys so Compose recomposes correctly after delete
            items(verifyStates, key = { it.result.line }) { state ->
                val index = verifyStates.indexOf(state)
                IngredientVerifyRow(
                    state = state,
                    onScan = { scanTarget = index },
                    onDelete = {
                        verifyStates = verifyStates.toMutableList().also { it.remove(state) }
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
                        onConfirm(
                            totalKcal / servings.coerceAtLeast(1),
                            totalProt / servings.coerceAtLeast(1),
                            totalCarbs / servings.coerceAtLeast(1),
                            totalFat / servings.coerceAtLeast(1)
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
    onScan: () -> Unit,
    onDelete: () -> Unit
) {
    val isOverride = state.override != null
    val isMatched  = state.isVerified
    var showActions by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showActions = !showActions }
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

        // Action row — shown when expanded
        if (showActions) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Keep as-is (close actions)
                OutlinedButton(
                    onClick = { showActions = false },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Belassen", fontSize = 12.sp)
                }
                // Delete
                OutlinedButton(
                    onClick = { showActions = false; onDelete() },
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
