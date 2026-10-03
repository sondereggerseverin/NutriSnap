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
    val scope = rememberCoroutineScope()

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
            if (standards.isEmpty()) {
                item {
                    Text(
                        "Noch keine Standards. Tippe + und scanne z. B. deinen körnigen Frischkäse.",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(standards, key = { it.normalizedName }) { row ->
                StandardRow(
                    match = row,
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
}

@Composable
private fun StandardRow(
    match: GlobalIngredientMatch,
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
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
