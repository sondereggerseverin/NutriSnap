package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import ch.nutrisnap.app.ui.viewmodel.IngredientMatchViewModel

/**
 * Screen: Zutaten-Verifikation für ein Rezept.
 *
 * Flow:
 *  1. Zeigt alle Zutaten des Rezepts als Liste
 *  2. Grün ✓  = bereits gematcht
 *     Orange ⚠ = noch kein Match → "Scannen"-Button
 *  3. Klick auf Zutat öffnet ScanOptionsDialog:
 *     - Barcode scannen
 *     - In Datenbank suchen
 *     - Manuell eingeben
 *  4. Nach vollständigem Match: Nährwert-Zusammenfassung
 *
 * Navigation-Parameter:
 *   recipeId: Long
 *   recipeTitle: String
 *   ingredientsRaw: String  (aus Recipe.ingredients)
 *
 * Aufruf in NavGraph:
 *   composable("recipe_verify/{recipeId}?title={title}&ingredients={ingredients}") { ... }
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeIngredientVerificationScreen(
    recipeId: Long,
    recipeTitle: String,
    ingredientsRaw: String,
    onNavigateToBarcode: (matchId: Long) -> Unit,
    onNavigateToSearch: (matchId: Long, query: String) -> Unit,
    onBack: () -> Unit,
    viewModel: IngredientMatchViewModel = hiltViewModel()
) {
    // Initialisiert Matches beim ersten Aufruf
    LaunchedEffect(recipeId) {
        viewModel.initMatchesFromRecipe(recipeId, ingredientsRaw)
    }

    val matches by viewModel.getMatchesFor(recipeId).collectAsState(initial = emptyList())
    val matched = matches.count { it.matchedFoodItemId != null }
    val total = matches.size

    // State für Dialoge
    var selectedMatch by remember { mutableStateOf<IngredientMatch?>(null) }
    var showScanOptions by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(recipeTitle, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "$matched / $total Zutaten verifiziert",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Fortschrittsbalken
            VerificationProgressBar(matched = matched, total = total)

            Spacer(modifier = Modifier.height(8.dp))

            if (matches.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(matches, key = { it.id }) { match ->
                        IngredientMatchCard(
                            match = match,
                            onClick = {
                                selectedMatch = match
                                showScanOptions = true
                            },
                            onReset = { viewModel.resetMatch(match) }
                        )
                    }

                    // Nährwert-Zusammenfassung wenn alle gematcht
                    if (matched > 0) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            NutritionSummaryCard(matches = matches)
                        }
                    }
                }
            }
        }
    }

    // Dialog: Scan-Optionen
    if (showScanOptions && selectedMatch != null) {
        ScanOptionsDialog(
            match = selectedMatch!!,
            onBarcode = {
                showScanOptions = false
                onNavigateToBarcode(selectedMatch!!.id)
            },
            onSearch = {
                showScanOptions = false
                onNavigateToSearch(selectedMatch!!.id, selectedMatch!!.ingredientName)
            },
            onManual = {
                showScanOptions = false
                showManualEntry = true
            },
            onDismiss = { showScanOptions = false }
        )
    }

    // Dialog: Manuelle Eingabe
    if (showManualEntry && selectedMatch != null) {
        ManualNutritionDialog(
            match = selectedMatch!!,
            onConfirm = { name, kcal, protein, carbs, fat ->
                viewModel.saveManualMatch(
                    match = selectedMatch!!,
                    name = name,
                    calories = kcal,
                    protein = protein,
                    carbs = carbs,
                    fat = fat
                )
                showManualEntry = false
            },
            onDismiss = { showManualEntry = false }
        )
    }
}

// ── Fortschrittsbalken ────────────────────────────────────────────────────────

@Composable
private fun VerificationProgressBar(matched: Int, total: Int) {
    val progress = if (total > 0) matched.toFloat() / total else 0f
    val color by animateColorAsState(
        targetValue = when {
            progress == 1f -> Color(0xFF4CAF50)
            progress >= 0.5f -> Color(0xFFFFA726)
            else -> MaterialTheme.colorScheme.primary
        }
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ── Zutaten-Karte ─────────────────────────────────────────────────────────────

@Composable
private fun IngredientMatchCard(
    match: IngredientMatch,
    onClick: () -> Unit,
    onReset: () -> Unit
) {
    val isMatched = match.matchedFoodItemId != null
    val bgColor by animateColorAsState(
        targetValue = if (isMatched)
            Color(0xFFE8F5E9)
        else
            MaterialTheme.colorScheme.surfaceVariant
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Status-Icon
            Icon(
                imageVector = if (isMatched) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isMatched) Color(0xFF4CAF50) else Color(0xFFFFA726),
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Zutaten-Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.ingredientName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (match.amountGrams > 0) "${match.amountGrams.toInt()}g • ${match.ingredientRaw}"
                           else match.ingredientRaw,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                if (isMatched && match.matchedFoodName != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "✓ ${match.matchedFoodName} • ${match.matchedCalories?.toInt() ?: 0} kcal",
                        fontSize = 11.sp,
                        color = Color(0xFF2E7D32),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = sourceLabel(match.matchSource),
                        fontSize = 10.sp,
                        color = Color(0xFF66BB6A)
                    )
                }
            }

            // Action-Button
            if (isMatched) {
                IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Zurücksetzen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Button(
                    onClick = onClick,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFA726)
                    )
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scannen", fontSize = 12.sp)
                }
            }
        }
    }
}

// ── Scan-Optionen Dialog ──────────────────────────────────────────────────────

@Composable
private fun ScanOptionsDialog(
    match: IngredientMatch,
    onBarcode: () -> Unit,
    onSearch: () -> Unit,
    onManual: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = match.ingredientName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                if (match.amountGrams > 0) {
                    Text(
                        text = "${match.amountGrams.toInt()}g",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Option 1: Barcode
                ScanOptionButton(
                    icon = Icons.Default.QrCodeScanner,
                    title = "Barcode scannen",
                    subtitle = "Kamera öffnen und Barcode scannen",
                    color = Color(0xFF1976D2),
                    onClick = onBarcode
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Option 2: Suche
                ScanOptionButton(
                    icon = Icons.Default.Search,
                    title = "In Datenbank suchen",
                    subtitle = "Gemüse, Generisches, OpenFoodFacts…",
                    color = Color(0xFF388E3C),
                    onClick = onSearch
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Option 3: Manuell
                ScanOptionButton(
                    icon = Icons.Default.Edit,
                    title = "Manuell eingeben",
                    subtitle = "Nährwerte selbst eintragen",
                    color = Color(0xFF7B1FA2),
                    onClick = onManual
                )

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Abbrechen")
                }
            }
        }
    }
}

@Composable
private fun ScanOptionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Manuelle Eingabe Dialog ───────────────────────────────────────────────────

@Composable
private fun ManualNutritionDialog(
    match: IngredientMatch,
    onConfirm: (name: String, kcal: Float, protein: Float, carbs: Float, fat: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(match.ingredientName) }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Nährwerte eingeben", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NutritionInput("kcal", kcal, { kcal = it }, Modifier.weight(1f))
                    NutritionInput("Protein g", protein, { protein = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NutritionInput("Carbs g", carbs, { carbs = it }, Modifier.weight(1f))
                    NutritionInput("Fett g", fat, { fat = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                name.ifBlank { match.ingredientName },
                                kcal.toFloatOrNull() ?: 0f,
                                protein.toFloatOrNull() ?: 0f,
                                carbs.toFloatOrNull() ?: 0f,
                                fat.toFloatOrNull() ?: 0f
                            )
                        },
                        enabled = name.isNotBlank()
                    ) { Text("Speichern") }
                }
            }
        }
    }
}

@Composable
private fun NutritionInput(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label, fontSize = 11.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

// ── Nährwert-Zusammenfassung ──────────────────────────────────────────────────

@Composable
private fun NutritionSummaryCard(matches: List<IngredientMatch>) {
    val totalKcal = matches.sumOf { ((it.matchedCalories ?: 0f) * (it.amountGrams / 100f)).toDouble() }.toFloat()
    val totalProtein = matches.sumOf { ((it.matchedProtein ?: 0f) * (it.amountGrams / 100f)).toDouble() }.toFloat()
    val totalCarbs = matches.sumOf { ((it.matchedCarbs ?: 0f) * (it.amountGrams / 100f)).toDouble() }.toFloat()
    val totalFat = matches.sumOf { ((it.matchedFat ?: 0f) * (it.amountGrams / 100f)).toDouble() }.toFloat()
    val matchedCount = matches.count { it.matchedFoodItemId != null }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BarChart, null, tint = Color(0xFF1565C0))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Nährwerte ($matchedCount/${matches.size} Zutaten)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                NutritionChip("Kalorien", "${totalKcal.toInt()} kcal", Color(0xFFE53935))
                NutritionChip("Protein", "${totalProtein.toInt()} g", Color(0xFF1976D2))
                NutritionChip("Carbs", "${totalCarbs.toInt()} g", Color(0xFFF57C00))
                NutritionChip("Fett", "${totalFat.toInt()} g", Color(0xFF388E3C))
            }
        }
    }
}

@Composable
private fun NutritionChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sourceLabel(source: MatchSource): String = when (source) {
    MatchSource.BARCODE -> "📷 Barcode"
    MatchSource.DATABASE -> "🔍 Datenbank"
    MatchSource.MANUAL -> "✏️ Manuell"
    MatchSource.UNMATCHED -> ""
}
