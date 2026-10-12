package ch.nutrisnap.app.ui.screens.ingredientdefaults

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.entity.GlobalIngredientMatch
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.repository.FoodSearchRepository
import ch.nutrisnap.app.data.repository.GlobalIngredientDictionary
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IngredientDefaultsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val dict = GlobalIngredientDictionary(db.globalIngredientMatchDao())
    private val foodRepo = FoodSearchRepository(
        foodItemDao = db.foodItemDao(),
        usdaApi = ch.nutrisnap.app.data.api.UsdaFoodApi(
            apiKey = ch.nutrisnap.app.BuildConfig.USDA_API_KEY
        ),
        nutritionixApi = ch.nutrisnap.app.data.api.NutritionixApi(
            appId = ch.nutrisnap.app.BuildConfig.NUTRITIONIX_APP_ID,
            apiKey = ch.nutrisnap.app.BuildConfig.NUTRITIONIX_API_KEY
        ),
        openFoodFactsSearch = { q -> ch.nutrisnap.app.data.api.OpenFoodFactsApi.search(q) }
    )

    val standards = dict.getAllVerified()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun lookupBarcode(barcode: String): FoodItem? =
        foodRepo.searchByBarcode(barcode)

    fun saveStandard(alias: String, food: FoodItem, onDone: () -> Unit) {
        viewModelScope.launch {
            dict.saveAsStandard(alias, food)
            onDone()
        }
    }

    fun delete(normalizedName: String) {
        viewModelScope.launch { dict.delete(normalizedName) }
    }

    /** Korrigiert die Nährwerte (pro 100 g) eines bestehenden Standards. */
    fun updateValues(row: GlobalIngredientMatch, kcal: Double, protein: Double, carbs: Double, fat: Double) {
        viewModelScope.launch {
            dict.save(
                originalName = row.originalName,
                offProductId = row.offProductId,
                offProductName = row.offProductName,
                kcalPer100g = kcal,
                proteinPer100g = protein,
                carbsPer100g = carbs,
                fatPer100g = fat
            )
        }
    }
}

/** Plausibilitäts-Hinweis für einen Standard, oder null wenn nichts auffällt. */
internal fun GlobalIngredientMatch.plausibilityWarning(): String? {
    val estimate = 4.0 * proteinPer100g + 4.0 * carbsPer100g + 9.0 * fatPer100g
    if (kcalPer100g > 905.0) return "Über 900 kcal/100 g – evtl. kJ statt kcal"
    if (kcalPer100g > 0 && kotlin.math.abs(kcalPer100g - estimate) > maxOf(40.0, 0.25 * kcalPer100g)) {
        return "kcal passen nicht zu P/K/F (≈ %.0f kcal)".format(estimate)
    }
    fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    val product = norm(offProductName)
    val tokens = norm(originalName).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }
    if (tokens.isNotEmpty() && tokens.any { it !in product }) {
        return "Produktname passt evtl. nicht zu «$originalName»"
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientDefaultsScreen(
    onBack: () -> Unit,
    vm: IngredientDefaultsViewModel = viewModel()
) {
    val standards by vm.standards.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var aliasName by remember { mutableStateOf("") }
    var scannedFood by remember { mutableStateOf<FoodItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editRow by remember { mutableStateOf<GlobalIngredientMatch?>(null) }
    val scope = rememberCoroutineScope()
    val filtered = remember(standards, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) standards
        else standards.filter { it.originalName.lowercase().contains(q) || it.offProductName.lowercase().contains(q) }
    }

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeDetected = { code ->
                showScanner = false
                loading = true
                scope.launch {
                    val food = vm.lookupBarcode(code)
                    loading = false
                    if (food != null) {
                        scannedFood = food
                        if (aliasName.isBlank()) aliasName = food.name
                        showAdd = true
                    } else {
                        Toast.makeText(context, "Produkt nicht gefunden", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onNavigateBack = { showScanner = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meine Zutaten-Standards") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    aliasName = ""
                    scannedFood = null
                    showAdd = true
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Standard hinzufügen") }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(NutriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
        ) {
            item {
                Text(
                    "Häufige Lebensmittel einmal per Barcode scannen. Beim nächsten Rezept/Foto wird dieser Treffer bevorzugt – vor generischen Defaults.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(8.dp))
            }
            if (standards.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Standards durchsuchen") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "Keine Treffer für «${query.trim()}».",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (standards.isEmpty()) {
                item {
                    Text(
                        "Noch keine Standards. Tippe + und scanne z. B. deinen körnigen Frischkäse.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(filtered, key = { it.normalizedName }) { row ->
                StandardRow(
                    match = row,
                    onEdit = { editRow = row },
                    onDelete = { vm.delete(row.normalizedName) }
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Standard setzen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = aliasName,
                        onValueChange = { aliasName = it },
                        label = { Text("Name im Rezept (z. B. körniger Frischkäse)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { showScanner = true; showAdd = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (scannedFood == null) "Barcode scannen" else "Anderen Barcode scannen")
                    }
                    scannedFood?.let { f ->
                        Text(
                            "${f.name}${f.brand?.let { " · $it" } ?: ""}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "%.0f kcal · P %.1f · K %.1f · F %.1f (pro 100 g)".format(
                                f.calories ?: 0f, f.protein ?: 0f, f.carbs ?: 0f, f.fat ?: 0f
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (loading) {
                        Text("Suche Produkt…", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = aliasName.isNotBlank() && scannedFood != null && !loading,
                    onClick = {
                        val food = scannedFood ?: return@TextButton
                        vm.saveStandard(aliasName.trim(), food) {
                            showAdd = false
                            Toast.makeText(context, "Standard gespeichert", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Abbrechen") }
            }
        )
    }

    editRow?.let { row ->
        EditStandardDialog(
            row = row,
            onDismiss = { editRow = null },
            onSave = { kcal, p, c, f ->
                vm.updateValues(row, kcal, p, c, f)
                editRow = null
                Toast.makeText(context, "Werte gespeichert", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun EditStandardDialog(
    row: GlobalIngredientMatch,
    onDismiss: () -> Unit,
    onSave: (Double, Double, Double, Double) -> Unit
) {
    fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)
    var kcal by remember { mutableStateOf(fmt(row.kcalPer100g)) }
    var protein by remember { mutableStateOf(fmt(row.proteinPer100g)) }
    var carbs by remember { mutableStateOf(fmt(row.carbsPer100g)) }
    var fat by remember { mutableStateOf(fmt(row.fatPer100g)) }
    fun num(t: String) = t.replace(',', '.').toDoubleOrNull()
    val values = listOf(num(kcal), num(protein), num(carbs), num(fat))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Werte pro 100 g – ${row.originalName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.offProductName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(kcal, { kcal = it }, label = { Text("kcal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(protein, { protein = it }, label = { Text("Protein (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(carbs, { carbs = it }, label = { Text("Kohlenhydrate (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fat, { fat = it }, label = { Text("Fett (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = values.all { it != null && it >= 0.0 },
                onClick = { onSave(values[0]!!, values[1]!!, values[2]!!, values[3]!!) }
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun StandardRow(
    match: GlobalIngredientMatch,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.md)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(NutriSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(match.originalName, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    match.offProductName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    "%.0f kcal · P %.1f · K %.1f · F %.1f / 100 g".format(
                        match.kcalPer100g, match.proteinPer100g, match.carbsPer100g, match.fatPer100g
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                match.plausibilityWarning()?.let { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(w, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Werte bearbeiten")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
