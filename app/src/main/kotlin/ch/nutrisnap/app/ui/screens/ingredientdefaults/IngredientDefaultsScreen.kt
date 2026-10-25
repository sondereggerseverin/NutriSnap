package ch.nutrisnap.app.ui.screens.ingredientdefaults

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
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
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.entity.GlobalIngredientMatch
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.FoodSearchRepository
import ch.nutrisnap.app.data.repository.GlobalIngredientDictionary
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Häufig geloggtes Lebensmittel mit Ø-Nährwerten pro 100 g aus dem eigenen Tagebuch. */
data class DiarySuggestion(
    val alias: String,
    val count: Int,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double
)

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
    private val foodItemRepo = FoodItemRepository(db)

    /** Lokale DB + Custom Foods (offline, schnell) für Standard-Zuweisung. */
    suspend fun searchLocalFoods(query: String): List<FoodItem> =
        withContext(Dispatchers.IO) { foodItemRepo.searchLocalOnly(query) }

    val standards = dict.getAllVerified()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _suggestions = MutableStateFlow<List<DiarySuggestion>>(emptyList())
    val suggestions: StateFlow<List<DiarySuggestion>> = _suggestions

    init { refreshSuggestions() }

    /**
     * Vorschläge aus den letzten 180 Tagen: Lebensmittel, die mind. 3× geloggt wurden und
     * noch kein Standard sind. Werte = Median pro 100 g über alle Einträge (robust gegen
     * einzelne Ausreißer). Rezept-Einträge, manuelle Overrides und unplausible Werte
     * werden ausgelassen.
     */
    fun refreshSuggestions() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val since = java.time.LocalDate.now().minusDays(180).toString()
                val known = dict.getAllVerified().first().map { it.normalizedName }.toSet()
                fun median(v: List<Double>): Double {
                    val sorted = v.sorted()
                    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                }
                db.diaryDao().getEntriesSince(since)
                    .filter { it.matchedRecipeId == null && it.recipeGrams == null &&
                        !it.isGloballyOverridden && it.amountGrams >= 5f && it.calories > 0f }
                    .groupBy { it.foodName.substringBefore(" (").substringBefore(" - ").trim() }
                    .filter { (alias, list) -> alias.length >= 3 && list.size >= 3 && alias.lowercase() !in known }
                    .map { (alias, list) ->
                        fun per100(sel: (ch.nutrisnap.app.data.model.DiaryEntry) -> Float) =
                            median(list.map { sel(it).toDouble() / it.amountGrams * 100.0 })
                        DiarySuggestion(
                            alias = alias, count = list.size,
                            kcal = per100 { it.calories }, protein = per100 { it.protein },
                            carbs = per100 { it.carbs }, fat = per100 { it.fat }
                        )
                    }
                    .filter { sug ->
                        GlobalIngredientMatch(
                            normalizedName = sug.alias.lowercase(), originalName = sug.alias,
                            offProductId = "diary", offProductName = sug.alias,
                            kcalPer100g = sug.kcal, proteinPer100g = sug.protein,
                            carbsPer100g = sug.carbs, fatPer100g = sug.fat
                        ).plausibilityWarning() == null
                    }
                    .sortedByDescending { it.count }
                    .take(15)
            }
            _suggestions.value = result
        }
    }

    fun addSuggestions(list: List<DiarySuggestion>) {
        viewModelScope.launch {
            list.forEach {
                dict.save(
                    originalName = it.alias,
                    offProductId = "diary",
                    offProductName = "${it.alias} (Ø aus deinem Tagebuch)",
                    kcalPer100g = it.kcal, proteinPer100g = it.protein,
                    carbsPer100g = it.carbs, fatPer100g = it.fat
                )
            }
            refreshSuggestions()
        }
    }

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
    fun updateValues(
        row: GlobalIngredientMatch,
        kcal: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fiber: Double = 0.0
    ) {
        viewModelScope.launch {
            dict.save(
                originalName = row.originalName,
                offProductId = row.offProductId,
                offProductName = row.offProductName,
                kcalPer100g = kcal,
                proteinPer100g = protein,
                carbsPer100g = carbs,
                fatPer100g = fat,
                fiberPer100g = fiber
            )
        }
    }

    suspend fun buildExportJson(): String = withContext(Dispatchers.IO) {
        val items = dict.exportAll()
        val arr = org.json.JSONArray()
        for (m in items) {
            arr.put(org.json.JSONObject().apply {
                put("alias", m.originalName)
                put("normalizedName", m.normalizedName)
                put("productName", m.offProductName)
                put("barcode", m.offProductId)
                put("kcal", m.kcalPer100g)
                put("protein", m.proteinPer100g)
                put("carbs", m.carbsPer100g)
                put("fat", m.fatPer100g)
                put("fiber", m.fiberPer100g)
                put("verified", m.isVerifiedByUser)
            })
        }
        org.json.JSONObject().apply {
            put("nutrisnap_ingredient_standards", 1)
            put("exportedAt", java.time.Instant.now().toString())
            put("count", items.size)
            put("items", arr)
        }.toString(2)
    }

    /** Importiert Standards aus JSON. REPLACE pro Eintrag. Gibt Anzahl zurück. */
    suspend fun importFromJson(raw: String): Int = withContext(Dispatchers.IO) {
        val root = org.json.JSONObject(raw.trim())
        if (!root.has("nutrisnap_ingredient_standards") && !root.has("items")) {
            error("Kein gültiges NutriSnap-Standards-JSON (fehlt nutrisnap_ingredient_standards / items)")
        }
        val arr = root.getJSONArray("items")
        val list = mutableListOf<GlobalIngredientMatch>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val alias = o.optString("alias").ifBlank { o.optString("originalName") }
            if (alias.isBlank()) continue
            val normalized = o.optString("normalizedName").ifBlank { alias.trim().lowercase() }
            list += GlobalIngredientMatch(
                normalizedName = normalized,
                originalName = alias.trim(),
                offProductId = o.optString("barcode").ifBlank { o.optString("offProductId") },
                offProductName = o.optString("productName").ifBlank { o.optString("offProductName").ifBlank { alias } },
                kcalPer100g = o.optDouble("kcal", o.optDouble("kcalPer100g", 0.0)),
                proteinPer100g = o.optDouble("protein", o.optDouble("proteinPer100g", 0.0)),
                carbsPer100g = o.optDouble("carbs", o.optDouble("carbsPer100g", 0.0)),
                fatPer100g = o.optDouble("fat", o.optDouble("fatPer100g", 0.0)),
                fiberPer100g = o.optDouble("fiber", o.optDouble("fiberPer100g", 0.0)),
                isVerifiedByUser = true
            )
        }
        dict.importAll(list)
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
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var aliasName by remember { mutableStateOf("") }
    var scannedFood by remember { mutableStateOf<FoodItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editRow by remember { mutableStateOf<GlobalIngredientMatch?>(null) }
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                    ?: error("Datei leer oder nicht lesbar")
                vm.importFromJson(raw)
            }.onSuccess { n ->
                Toast.makeText(context, "$n Standards importiert", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Import fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
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
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { vm.buildExportJson() }
                                .onSuccess { json ->
                                    // Als echte .json-Datei teilen (Text war für Zwischenablage/Chat zu lang)
                                    val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
                                    val file = java.io.File(dir, "NutriSnap-Zutaten-Standards.json")
                                    file.writeText(json, Charsets.UTF_8)
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "NutriSnap-Zutaten-Standards.json")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Standards exportieren"))
                                }
                                .onFailure {
                                    Toast.makeText(context, "Export fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }) { Icon(Icons.Default.Share, "Exportieren") }
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(Icons.Default.UploadFile, "Importieren")
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
            if (suggestions.isNotEmpty() && query.isBlank()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Vorschläge aus deinem Tagebuch",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            vm.addSuggestions(suggestions)
                            Toast.makeText(context, "${suggestions.size} Standards übernommen", Toast.LENGTH_SHORT).show()
                        }) { Text("Alle übernehmen") }
                    }
                    Text(
                        "Häufig geloggt (letzte 180 Tage), Ø-Werte pro 100 g aus deinen eigenen Einträgen.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(suggestions, key = { "sug_" + it.alias }) { sug ->
                    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(NutriRadius.md)) {
                        Row(
                            Modifier.fillMaxWidth().padding(NutriSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(sug.alias, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    "%d× geloggt · %.0f kcal · P %.1f · K %.1f · F %.1f / 100 g".format(
                                        sug.count, sug.kcal, sug.protein, sug.carbs, sug.fat
                                    ),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.addSuggestions(listOf(sug)) }) {
                                Icon(Icons.Default.Add, "Als Standard übernehmen", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showAdd) {
        var productQuery by remember { mutableStateOf("") }
        var localResults by remember { mutableStateOf<List<FoodItem>>(emptyList()) }
        var searchingLocal by remember { mutableStateOf(false) }

        // Debounced lokale Suche
        LaunchedEffect(productQuery) {
            val q = productQuery.trim()
            if (q.length < 2) {
                localResults = emptyList()
                return@LaunchedEffect
            }
            searchingLocal = true
            kotlinx.coroutines.delay(280)
            localResults = runCatching { vm.searchLocalFoods(q) }.getOrDefault(emptyList()).take(8)
            searchingLocal = false
        }

        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Standard setzen") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    OutlinedTextField(
                        value = aliasName,
                        onValueChange = { aliasName = it },
                        label = { Text("Name im Rezept (z. B. körniger Frischkäse)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = productQuery,
                        onValueChange = { productQuery = it },
                        label = { Text("Produkt in lokaler DB suchen") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (searchingLocal) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (localResults.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            localResults.forEach { food ->
                                val selected = scannedFood?.let {
                                    it.name == food.name && it.brand == food.brand && it.barcode == food.barcode
                                } == true
                                Surface(
                                    onClick = {
                                        scannedFood = food
                                        if (aliasName.isBlank()) aliasName = food.name
                                    },
                                    shape = RoundedCornerShape(NutriRadius.sm),
                                    color = if (selected)
                                        MaterialTheme.colorScheme.primaryContainer
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        Text(
                                            food.name,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        val brand = food.brand?.takeIf { it.isNotBlank() }
                                        Text(
                                            buildString {
                                                if (brand != null) append("$brand · ")
                                                append("%.0f kcal · P %.0f · K %.0f · F %.0f".format(
                                                    food.calories ?: 0f,
                                                    food.protein ?: 0f,
                                                    food.carbs ?: 0f,
                                                    food.fat ?: 0f
                                                ))
                                                food.fiber?.takeIf { it > 0f }?.let {
                                                    append(" · B %.0f".format(it))
                                                }
                                                append(" / 100 g")
                                            },
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    } else if (productQuery.trim().length >= 2 && !searchingLocal) {
                        Text(
                            "Keine Treffer in der lokalen DB",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showScanner = true; showAdd = false },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCodeScanner, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (scannedFood == null) "Barcode scannen" else "Anderen Barcode scannen")
                    }
                    scannedFood?.let { f ->
                        HorizontalDivider()
                        Text("Ausgewählt:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${f.name}${f.brand?.let { " · $it" } ?: ""}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            buildString {
                                append("%.0f kcal · P %.1f · K %.1f · F %.1f".format(
                                    f.calories ?: 0f, f.protein ?: 0f, f.carbs ?: 0f, f.fat ?: 0f
                                ))
                                f.fiber?.takeIf { it > 0f }?.let { append(" · B %.1f".format(it)) }
                                append(" (pro 100 g)")
                            },
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
            onSave = { kcal, p, c, f, fiber ->
                vm.updateValues(row, kcal, p, c, f, fiber)
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
    onSave: (Double, Double, Double, Double, Double) -> Unit
) {
    fun fmt(v: Double) = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)
    var kcal by remember { mutableStateOf(fmt(row.kcalPer100g)) }
    var protein by remember { mutableStateOf(fmt(row.proteinPer100g)) }
    var carbs by remember { mutableStateOf(fmt(row.carbsPer100g)) }
    var fat by remember { mutableStateOf(fmt(row.fatPer100g)) }
    var fiber by remember { mutableStateOf(fmt(row.fiberPer100g)) }
    fun parse(s: String) = s.trim().replace(',', '.').toDoubleOrNull()
    val values = listOf(parse(kcal), parse(protein), parse(carbs), parse(fat), parse(fiber))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Werte bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.originalName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(row.offProductName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Text("pro 100 g", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(kcal, { kcal = it }, label = { Text("kcal") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(protein, { protein = it }, label = { Text("Protein (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(carbs, { carbs = it }, label = { Text("Kohlenhydrate (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fat, { fat = it }, label = { Text("Fett (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fiber, { fiber = it }, label = { Text("Ballaststoffe (g)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = values.all { it != null && it >= 0.0 },
                onClick = { onSave(values[0]!!, values[1]!!, values[2]!!, values[3]!!, values[4]!!) }
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
                    "%.0f kcal · P %.1f · K %.1f · F %.1f · B %.1f / 100 g".format(
                        match.kcalPer100g, match.proteinPer100g, match.carbsPer100g, match.fatPer100g, match.fiberPer100g
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
