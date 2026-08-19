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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
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

// ── Identify Sheet: Barcode / Search / KI-Schätzung ───────────────────────────

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
                onAi      = { mode = IdentifyMode.AiEstimate }
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
            IdentifyMode.AiEstimate -> AiEstimateScreen(
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
    object AiEstimate : IdentifyMode()
}

// ── Choose screen ─────────────────────────────────────────────────────────────

@Composable
private fun IdentifyChooseScreen(
    ingredientName: String,
    onBarcode: () -> Unit,
    onSearch: () -> Unit,
    onAi: () -> Unit
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
            icon = Icons.Default.AutoAwesome,
            title = "Mit KI schätzen/suchen",
            subtitle = "Referenzwerte zuerst, sonst KI-Schätzung pro 100g",
            badge = "KI",
            badgeColor = Color(0xFF6A1B9A),
            onClick = onAi
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

// ── KI-Schätzung Screen ───────────────────────────────────────────────────────
// Strategie gegen Halluzinationen:
// 1. Zuerst lokale Referenz-DB (USDA-nahe Werte, z.B. Mais ~86 kcal) — kein LLM.
// 2. Sonst GroqFoodEstimatorApi mit strengem Prompt + Plausibilitätscheck
//    (kcal ≈ 4P+4K+9F, sinnvolle Bereiche).

@Composable
private fun AiEstimateScreen(
    name: String,
    onConfirm: (FoodItem) -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<FoodItem?>(null) }
    var sourceLabel by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(name) {
        isLoading = true
        errorMsg = null
        result = null
        // 1) Lokale Referenz zuerst — echte Werte, keine Erfindung
        val local = ch.nutrisnap.app.domain.IngredientNutritionDatabase.lookup(name)
        if (local != null) {
            result = FoodItem(
                name = name.trim().ifBlank { "Unbekannt" },
                brand = "Referenzdatenbank",
                calories = local.calories,
                protein = local.protein,
                carbs = local.carbs,
                fat = local.fat,
                fiber = local.fiber,
                servingSize = 100f,
                servingUnit = "g",
                source = FoodSource.MANUAL,
                completenessScore = 80
            )
            sourceLabel = "Lokale Referenz (USDA-nah)"
            isLoading = false
            return@LaunchedEffect
        }
        // 2) KI-Schätzung nur wenn nichts in der Referenz-DB
        val estimated = runCatching {
            ch.nutrisnap.app.data.api.GroqFoodEstimatorApi.estimate(name)
        }.getOrNull()
        if (estimated != null) {
            result = estimated
            sourceLabel = "KI-Schätzung (nicht verifiziert)"
        } else {
            errorMsg = "Keine Schätzung möglich — bitte Datenbank-Suche oder Barcode nutzen."
        }
        isLoading = false
    }

    Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Mit KI schätzen/suchen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Text(
            "Zuerst Referenzwerte, sonst KI pro 100g",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)
        )

        when {
            isLoading -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Suche Nährwerte…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            errorMsg != null -> {
                Text(errorMsg!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    scope.launch {
                        isLoading = true
                        errorMsg = null
                        val estimated = runCatching {
                            ch.nutrisnap.app.data.api.GroqFoodEstimatorApi.estimate(name)
                        }.getOrNull()
                        result = estimated
                        sourceLabel = if (estimated != null) "KI-Schätzung (nicht verifiziert)" else ""
                        if (estimated == null) errorMsg = "Keine Schätzung möglich."
                        isLoading = false
                    }
                }) { Text("Erneut versuchen") }
            }
            result != null -> {
                val food = result!!
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(food.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(sourceLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MacroChip("kcal", "${food.calories?.toInt() ?: "–"}")
                            MacroChip("Protein", "${food.protein?.let { "%.1f".format(it) } ?: "–"} g")
                            MacroChip("KH", "${food.carbs?.let { "%.1f".format(it) } ?: "–"} g")
                            MacroChip("Fett", "${food.fat?.let { "%.1f".format(it) } ?: "–"} g")
                        }
                        food.fiber?.takeIf { it > 0f }?.let {
                            Text("Ballaststoffe: %.1f g / 100g".format(it),
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onConfirm(food) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Übernehmen")
                }
            }
        }
    }
}
