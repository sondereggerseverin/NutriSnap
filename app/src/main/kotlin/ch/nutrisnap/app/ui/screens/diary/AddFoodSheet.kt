package ch.nutrisnap.app.ui.screens.diary

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nutrisnap.app.data.model.*
import ch.nutrisnap.app.domain.EntryPlausibilityChecker
import ch.nutrisnap.app.domain.EverydayServingSizes
import ch.nutrisnap.app.domain.FoodPortionPresets
import androidx.compose.ui.platform.LocalContext
import ch.nutrisnap.app.ui.screens.settings.notifDataStore
import ch.nutrisnap.app.ui.theme.KEY_TOGGLE_RECIPE_CHIP_SIZING
import ch.nutrisnap.app.ui.components.EmptyState
import ch.nutrisnap.app.ui.components.SectionHeader
import ch.nutrisnap.app.ui.screens.barcode.BarcodeScannerScreen
import ch.nutrisnap.app.ui.screens.scan.PhotoCaptureScreen
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import java.time.LocalTime
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

internal fun defaultMealForNow(): MealType = when (LocalTime.now().hour) {
    in 5..10  -> MealType.BREAKFAST
    in 11..14 -> MealType.LUNCH
    in 17..21 -> MealType.DINNER
    else      -> MealType.SNACK
}


/** Parst Mengeneingaben wie "100", "100g", "100 g", "1,5", "100ml". */
internal fun parseGramsInput(text: String): Float? {
    val cleaned = text.trim()
        .replace(',', '.')
        .replace(Regex("""(?i)\s*(g|gramm|grams?|ml|milliliter)\s*$"""), "")
        .trim()
    return cleaned.toFloatOrNull()?.takeIf { it > 0f }
}

enum class AddFoodTab { SEARCH, AI, MANUAL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    autoOpenScanner: Boolean = false,
    onNavigateToPhotoScan: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var activeTab    by remember { mutableStateOf(AddFoodTab.SEARCH) }
    var showScanner  by remember { mutableStateOf(autoOpenScanner) }
    var barcodeStatus by remember { mutableStateOf("") }

    var pendingUnknownBarcode by remember { mutableStateOf<String?>(null) }
    var labelCaptureStep by remember { mutableStateOf(0) } // 0=none, 1=first photo, 2=optional second
    var firstLabelBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSavingProduct by remember { mutableStateOf(false) }

    if (showScanner) {
        BarcodeScannerScreen(
            onBarcodeDetected = { raw ->
                showScanner = false
                val barcode = ch.nutrisnap.app.utils.BarcodeUtils.normalize(raw).ifBlank { raw.trim() }
                barcodeStatus = "Suche Barcode $barcode..."
                vm.searchBarcode(barcode) { food ->
                    if (food != null) {
                        activeTab = AddFoodTab.SEARCH
                        barcodeStatus = ""
                        vm.setBarcodeResult(food)
                    } else {
                        barcodeStatus = "Barcode $barcode nicht gefunden"
                        pendingUnknownBarcode = barcode
                    }
                }
            },
            onNavigateBack = { showScanner = false }
        )
        return
    }

    // Unbekanntes Produkt: Nährwerttabelle fotografieren (1 Foto reicht, sofort speichern)
    if (labelCaptureStep > 0 && pendingUnknownBarcode != null) {
        PhotoCaptureScreen(
            title = "Nährwerttabelle fotografieren",
            instructions = "Barcode $pendingUnknownBarcode – Tabelle (pro 100 g) scharf fotografieren. Danach wird das Produkt gespeichert und ist per Barcode wiederfindbar.",
            onPhotoCaptured = { bitmap ->
                isSavingProduct = true
                labelCaptureStep = 0
                val meal = initialMeal ?: defaultMealForNow()
                val bc = pendingUnknownBarcode!!
                vm.captureUnknownProduct(
                    barcode = bc,
                    labelBitmap = bitmap,
                    secondBitmap = null,
                    meal = meal,
                    amountGrams = 100f
                ) { food ->
                    isSavingProduct = false
                    firstLabelBitmap = null
                    pendingUnknownBarcode = null
                    barcodeStatus = if (food != null)
                        "„${food.name}“ gespeichert – nächstes Mal per Barcode findbar"
                    else
                        "Etikett konnte nicht gelesen werden – bitte nochmals versuchen"
                }
            },
            onNavigateBack = {
                labelCaptureStep = 0
                firstLabelBitmap = null
            }
        )
        return
    }

    val density = LocalDensity.current
    // rememberUpdatedState: confirmValueChange-Lambda hält sonst stale imeVisible
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val imeVisibleState = rememberUpdatedState(imeVisible)
    // Tastatur offen: Sheet nicht per Gesture schließen (sonst Crashes/Dismiss beim Öffnen)
    val addSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { newValue ->
            if (imeVisibleState.value && newValue == SheetValue.Hidden) false else true
        }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = addSheetState,
        // Insets selbst handhaben (imePadding unten), sonst doppelte/kämpfende Resize-Logik
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NutriSpacing.lg)
                .padding(bottom = NutriSpacing.xl)
        ) {
            Text(
                "Eintrag hinzufügen",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(bottom = NutriSpacing.sm)
            )
            // Nachträglich tracken: Tag wählen (nutzt dieselbe Datums-Navigation wie das Tagebuch)
            val diaryDate by vm.uiState.collectAsStateWithLifecycle()
            val activeDate = diaryDate.selectedDate
            Text("Tag", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = NutriSpacing.md)
            ) {
                val today = java.time.LocalDate.now()
                listOf(
                    today to "Heute",
                    today.plusDays(1) to "Morgen",
                    today.plusDays(2) to "+2 Tage",
                    today.minusDays(1) to "Gestern"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = activeDate == d,
                        onClick = { vm.setDate(d) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
            if (activeDate != java.time.LocalDate.now()) {
                Text(
                    "Wird für ${activeDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))} gespeichert",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = NutriSpacing.sm)
                )
            }

            // Direkte Einstiege: Foto ist der kürzeste Weg zum Tracken per Kamera
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = NutriSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
            ) {
                OutlinedButton(
                    onClick = onNavigateToPhotoScan,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(NutriSpacing.xs))
                    Text("Foto")
                }
                OutlinedButton(
                    onClick = { showScanner = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(NutriSpacing.xs))
                    Text("Barcode")
                }
            }

            ScrollableTabRow(
                selectedTabIndex = activeTab.ordinal,
                edgePadding = 0.dp
            ) {
                Tab(
                    selected = activeTab == AddFoodTab.SEARCH,
                    onClick  = { activeTab = AddFoodTab.SEARCH },
                    text     = { Text("Suche") },
                    icon     = { Icon(Icons.Default.Search, null, Modifier.size(16.dp)) }
                )
                Tab(
                    selected = activeTab == AddFoodTab.AI,
                    onClick  = { activeTab = AddFoodTab.AI },
                    text     = { Text("KI schätzen") },
                    icon     = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp)) }
                )
                Tab(
                    selected = activeTab == AddFoodTab.MANUAL,
                    onClick  = { activeTab = AddFoodTab.MANUAL },
                    text     = { Text("Manuell") },
                    icon     = { Icon(Icons.Default.Edit, null, Modifier.size(16.dp)) }
                )
            }

            Spacer(Modifier.height(NutriSpacing.md))

            if (pendingUnknownBarcode != null && labelCaptureStep == 0) {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = NutriSpacing.sm),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Barcode $pendingUnknownBarcode nicht in der Datenbank",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            "Nährwerttabelle fotografieren – Produkt wird gespeichert und ist nächstes Mal per Barcode findbar.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { labelCaptureStep = 1 },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Etikett fotografieren")
                        }
                        TextButton(onClick = { pendingUnknownBarcode = null; barcodeStatus = "" }) {
                            Text("Abbrechen")
                        }
                    }
                }
            }
            if (isSavingProduct) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Produkt wird gespeichert…", fontSize = 13.sp)
                }
            }

            when (activeTab) {
                AddFoodTab.SEARCH -> SearchTab(
                    vm = vm,
                    initialMeal = initialMeal,
                    barcodeStatus = barcodeStatus,
                    onOpenScanner = { showScanner = true },
                    onSwitchToAi = { activeTab = AddFoodTab.AI },
                    onDismiss = onDismiss
                )
                AddFoodTab.AI -> AiEstimateTab(
                    vm = vm,
                    initialMeal = initialMeal,
                    onDismiss = onDismiss
                )
                AddFoodTab.MANUAL -> ManualEntryTab(
                    initialMeal = initialMeal,
                    onSave = { name, kcal, protein, carbs, fat, meal ->
                        vm.addManualEntry(name, kcal, protein, carbs, fat, meal)
                        onDismiss()
                    }
                )
            }

            Spacer(Modifier.height(NutriSpacing.xxxl))
        }
    }
}

@Composable
private fun SearchTab(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    barcodeStatus: String,
    onOpenScanner: () -> Unit,
    onSwitchToAi: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var query        by remember { mutableStateOf("") }
    var selectedFood by remember { mutableStateOf<FoodItem?>(null) }
    var amountText   by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    var portionWarning by remember { mutableStateOf<String?>(null) }

    val results   by vm.searchResults.collectAsStateWithLifecycle()
    val searching by vm.isSearching.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val barcodeResult by vm.barcodeResult.collectAsStateWithLifecycle()
    val favoriteKeys = remember(favorites) { favorites.map { it.favoriteKey() }.toSet() }

    LaunchedEffect(barcodeResult) {
        barcodeResult?.let { selectedFood = it; vm.clearBarcodeResult() }
    }
    var rememberedGrams by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(selectedFood) {
        val food = selectedFood ?: return@LaunchedEffect
        rememberedGrams = null
        val last = vm.getLastAmount(food)
        if (last != null && last > 0f) {
            rememberedGrams = last
            amountText = if (last == last.toInt().toFloat()) last.toInt().toString() else "%.0f".format(last)
        }
    }

    if (selectedFood == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; vm.searchFood(it) },
                label = { Text("Suchen\u2026") },
                leadingIcon  = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            IconButton(
                onClick = onOpenScanner,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(56.dp)
            ) {
                Icon(
                    Icons.Default.QrCodeScanner, "Barcode",
                    Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (barcodeStatus.isNotBlank()) {
            Text(
                barcodeStatus,
                fontSize = 13.sp,
                color = if ("nicht gefunden" in barcodeStatus)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = NutriSpacing.xs)
            )
        }

        if (query.isBlank() && favorites.isNotEmpty()) {
            Text(
                "\u2B50 Favoriten",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = NutriSpacing.md, bottom = NutriSpacing.xs)
            )
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(favorites, key = { it.favoriteKey() }) { food ->
                    FoodResultRow(
                        food     = food,
                        onClick  = { selectedFood = food },
                        leading  = {
                            Icon(
                                Icons.Default.Star, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (results.isEmpty() && query.length > 1 && !searching) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = NutriSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Keine Treffer in der Datenbank",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = onSwitchToAi,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Mit KI schätzen")
                }
            }
        }

        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            items(results) { food ->
                val isFav = food.favoriteKey() in favoriteKeys
                FoodResultRow(
                    food     = food,
                    onClick  = { selectedFood = food },
                    trailingAction = {
                        IconButton(
                            onClick = { vm.toggleFavorite(food) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null,
                                tint = if (isFav) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
                HorizontalDivider()
            }
        }
    } else {
        val food  = selectedFood!!
        val isFav = food.favoriteKey() in favoriteKeys
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    food.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                food.brand?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = { vm.toggleFavorite(food) }) {
                Icon(
                    if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null,
                    tint = if (isFav) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(NutriSpacing.md))
        val presets = remember(food) { ch.nutrisnap.app.domain.FoodPortionPresets.forFood(food) }
        val hasRemembered = rememberedGrams != null && rememberedGrams!! > 0f
        // Design-Toggle #20 "Portion-Chips größer" (Mehr → Design): klarerer
        // Selected-State via Primary-Container statt Default-Secondary-Container.
        val chipContext = LocalContext.current
        val chipPrefs by chipContext.notifDataStore.data.collectAsStateWithLifecycle(initialValue = null)
        val boldPortionChips = chipPrefs?.get(KEY_TOGGLE_RECIPE_CHIP_SIZING) ?: false
        val portionChipColors = if (boldPortionChips) {
            FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        } else {
            FilterChipDefaults.filterChipColors()
        }
        if (hasRemembered || presets.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                if (hasRemembered) {
                    val g = rememberedGrams!!
                    FilterChip(
                        selected = amountText.toFloatOrNull() == g,
                        onClick = {
                            amountText = if (g == g.toInt().toFloat()) g.toInt().toString() else "%.0f".format(g)
                        },
                        label = {
                            Text(
                                "Standard ${if (g == g.toInt().toFloat()) g.toInt() else "%.0f".format(g)} g",
                                fontSize = 12.sp
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Default.History, null, Modifier.size(16.dp))
                        },
                        colors = portionChipColors
                    )
                }
                presets.forEach { preset ->
                    FilterChip(
                        selected = amountText.toFloatOrNull() == preset.grams,
                        onClick  = { amountText = preset.grams.toInt().toString() },
                        label    = { Text(preset.label, fontSize = 12.sp) },
                        colors = portionChipColors
                    )
                }
            }
            Spacer(Modifier.height(NutriSpacing.sm))
        }
        var showServingGuide by remember { mutableStateOf(false) }
        val grams = parseGramsInput(amountText) ?: 0f
        fun confirmAdd(andClose: Boolean) {
            if (grams <= 0f) return
            val warning = EntryPlausibilityChecker.checkPortion(grams)
            if (warning != null) {
                portionWarning = warning
            } else {
                vm.addEntryWithMemory(food, grams, selectedMeal)
                if (andClose) onDismiss()
                else {
                    selectedFood = null
                    amountText = "100"
                    query = ""
                    vm.searchFood("")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Menge (g)") },
                placeholder = { Text("z.B. 100 oder 100g") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { confirmAdd(andClose = false) }),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md),
                trailingIcon = {
                    IconButton(onClick = { showServingGuide = true }) {
                        Icon(Icons.Default.Straighten, "Alltagseinheiten anzeigen")
                    }
                },
                supportingText = {
                    if (amountText.isNotBlank() && parseGramsInput(amountText) == null)
                        Text("Ungültige Menge – z.B. 100 oder 100g", color = MaterialTheme.colorScheme.error)
                },
                isError = amountText.isNotBlank() && parseGramsInput(amountText) == null
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }
        if (showServingGuide) {
            ServingSizeGuideDialog(
                food = food,
                onSelect = { g -> amountText = g.toInt().toString(); showServingGuide = false },
                onDismiss = { showServingGuide = false }
            )
        }
        if (grams > 0) {
            Spacer(Modifier.height(NutriSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.lg)) {
                Text(
                    "${((food.calories ?: 0f) * grams / 100f).toInt()} kcal",
                    fontWeight = FontWeight.SemiBold,
                    color = MacroColors.calories
                )
                Text("P ${((food.protein ?: 0f) * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.protein)
                Text("K ${((food.carbs ?: 0f) * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.carbs)
                Text("F ${((food.fat ?: 0f) * grams / 100f).toInt()}g", fontSize = 13.sp, color = MacroColors.fat)
            }
        }
        Spacer(Modifier.height(NutriSpacing.lg))
        // Primär: + weiteres Lebensmittel (Sheet bleibt offen)
        Button(
            onClick = { confirmAdd(andClose = false) },
            modifier = Modifier.fillMaxWidth(),
            enabled = grams > 0
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Hinzufügen & weiteres")
        }
        Spacer(Modifier.height(NutriSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedButton(
                onClick = { selectedFood = null },
                modifier = Modifier.weight(1f)
            ) {
                Text("Zurück")
            }
            OutlinedButton(
                onClick = { confirmAdd(andClose = true) },
                modifier = Modifier.weight(1f),
                enabled = grams > 0
            ) {
                Text("Hinzufügen & fertig")
            }
        }
        portionWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { portionWarning = null },
                title   = { Text("Menge prüfen") },
                text    = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.addEntryWithMemory(food, grams, selectedMeal)
                        portionWarning = null
                        selectedFood = null
                        amountText = "100"
                        query = ""
                    }) { Text("Trotzdem + weiteres") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        vm.addEntryWithMemory(food, grams, selectedMeal)
                        portionWarning = null
                        onDismiss()
                    }) { Text("Trotzdem & fertig") }
                }
            )
        }
    }
}

/**
 * KI-Schätzung wie bei Rezept-Zutaten: zuerst lokale Referenz-DB,
 * sonst Groq/Gemini. Ergebnis pro 100g, Menge + Mahlzeit wählbar.
 */
@Composable
private fun AiEstimateTab(
    vm: DiaryViewModel,
    initialMeal: MealType? = null,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FoodItem?>(null) }
    var sourceLabel by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var amountText by remember { mutableStateOf("100") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    val scope = rememberCoroutineScope()

    fun runEstimate(name: String) {
        val q = name.trim()
        if (q.length < 2) return
        scope.launch {
            isLoading = true
            errorMsg = null
            result = null
            // 1) Lokale Referenz (USDA-nah) – nichts erfinden
            val local = ch.nutrisnap.app.domain.IngredientNutritionDatabase.lookup(q)
            if (local != null) {
                result = FoodItem(
                    name = q,
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
                return@launch
            }
            // 2) KI-Schätzung
            val estimated = runCatching {
                ch.nutrisnap.app.data.api.GroqFoodEstimatorApi.estimate(q)
            }.getOrNull()
            if (estimated != null) {
                result = estimated
                sourceLabel = "KI-Schätzung (nicht verifiziert)"
            } else {
                errorMsg = "Keine Schätzung möglich – probiere Suche oder Manuell."
            }
            isLoading = false
        }
    }

    // Kein eigenes verticalScroll: Parent-Column in AddFoodSheet scrollt bereits
    // (nested verticalScroll → IllegalStateException: infinity max height).
    Column(
        verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
    ) {
        Text(
            "Name eingeben – zuerst Referenzwerte, sonst KI pro 100g",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Lebensmittel (z.B. Hähnchenbrust, Reis)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(NutriRadius.md),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search
            ),
            keyboardActions = KeyboardActions(
                onSearch = { runEstimate(query) }
            ),
            trailingIcon = {
                IconButton(
                    onClick = { runEstimate(query) },
                    enabled = query.trim().length >= 2 && !isLoading
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, "Schätzen")
                }
            }
        )
        Button(
            onClick = { runEstimate(query) },
            enabled = query.trim().length >= 2 && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (isLoading) "Schätze…" else "Mit KI schätzen")
        }

        errorMsg?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        result?.let { food ->
            Card(
                shape = RoundedCornerShape(NutriRadius.md),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(food.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        sourceLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${food.calories?.toInt() ?: "–"} kcal", fontWeight = FontWeight.Bold, color = MacroColors.calories)
                        Text("P ${food.protein?.let { "%.1f".format(it) } ?: "–"} g", fontSize = 13.sp, color = MacroColors.protein)
                        Text("K ${food.carbs?.let { "%.1f".format(it) } ?: "–"} g", fontSize = 13.sp, color = MacroColors.carbs)
                        Text("F ${food.fat?.let { "%.1f".format(it) } ?: "–"} g", fontSize = 13.sp, color = MacroColors.fat)
                    }
                    Text("pro 100 g", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } },
                    label = { Text("Menge g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(NutriRadius.md)
                )
                MealPicker(selected = selectedMeal) { selectedMeal = it }
            }

            val grams = amountText.replace(',', '.').toFloatOrNull() ?: 0f
            val factor = grams / 100f
            if (grams > 0f) {
                val kcal = (food.calories ?: 0f) * factor
                val p = (food.protein ?: 0f) * factor
                val k = (food.carbs ?: 0f) * factor
                val f = (food.fat ?: 0f) * factor
                Text(
                    "→ ${kcal.toInt()} kcal · P ${p.toInt()}g · K ${k.toInt()}g · F ${f.toInt()}g",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Button(
                onClick = {
                    if (grams > 0f) {
                        vm.addEntryWithMemory(food, grams, selectedMeal)
                        onDismiss()
                    }
                },
                enabled = grams > 0f,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ins Tagebuch")
            }
        }
    }
}

@Composable
private fun ManualEntryTab(
    initialMeal: MealType? = null,
    onSave: (name: String, kcal: Float, protein: Float, carbs: Float, fat: Float, meal: MealType) -> Unit
) {
    var name         by remember { mutableStateOf("") }
    var kcalText     by remember { mutableStateOf("") }
    var proteinText  by remember { mutableStateOf("") }
    var carbsText    by remember { mutableStateOf("") }
    var fatText      by remember { mutableStateOf("") }
    var selectedMeal by remember { mutableStateOf(initialMeal ?: MealType.LUNCH) }
    var manualWarning by remember { mutableStateOf<String?>(null) }

    val kcal    = kcalText.toFloatOrNull() ?: 0f
    val protein = proteinText.toFloatOrNull() ?: 0f
    val carbs   = carbsText.toFloatOrNull() ?: 0f
    val fat     = fatText.toFloatOrNull() ?: 0f
    val isValid = name.isNotBlank() && kcal > 0f

    Column(verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)) {
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name (z.B. Müesli mit Früchten)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(NutriRadius.md)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = kcalText, onValueChange = { kcalText = it },
                label = { Text("kcal *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            MealPicker(selected = selectedMeal) { selectedMeal = it }
        }

        Text(
            "Makros (optional)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
            OutlinedTextField(
                value = proteinText, onValueChange = { proteinText = it },
                label = { Text("Protein g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            OutlinedTextField(
                value = carbsText, onValueChange = { carbsText = it },
                label = { Text("Kohlenhydrate g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
            OutlinedTextField(
                value = fatText, onValueChange = { fatText = it },
                label = { Text("Fett g") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(NutriRadius.md)
            )
        }

        if (isValid) {
            Row(horizontalArrangement = Arrangement.spacedBy(NutriSpacing.lg)) {
                Text("${kcal.toInt()} kcal", fontWeight = FontWeight.SemiBold, color = MacroColors.calories)
                if (protein > 0) Text("P ${protein.toInt()}g", fontSize = 12.sp, color = MacroColors.protein)
                if (carbs > 0)   Text("K ${carbs.toInt()}g", fontSize = 12.sp, color = MacroColors.carbs)
                if (fat > 0)     Text("F ${fat.toInt()}g", fontSize = 12.sp, color = MacroColors.fat)
            }
        }

        Button(
            onClick  = {
                if (isValid) {
                    val warning = EntryPlausibilityChecker.checkManualEntry(kcal, protein, carbs, fat)
                    if (warning != null) manualWarning = warning
                    else onSave(name, kcal, protein, carbs, fat, selectedMeal)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled  = isValid
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(NutriSpacing.sm))
            Text("Manuell hinzufügen")
        }
        manualWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = { manualWarning = null },
                title   = { Text("Werte prüfen") },
                text    = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = {
                        onSave(name, kcal, protein, carbs, fat, selectedMeal)
                        manualWarning = null
                    }) { Text("Trotzdem speichern") }
                },
                dismissButton = {
                    TextButton(onClick = { manualWarning = null }) { Text("Anpassen") }
                }
            )
        }
    }
}

/**
 * Visueller Portionsgrössen-Guide: zeigt ~80 Alltagseinheiten (Tasse, Handvoll, Scheibe, ...)
 * mit Emoji-Referenzbild, Objektname und dem für [food] berechneten Kalorienwert, als
 * Alternative zur reinen Gramm-Eingabe. Ergänzt [FoodPortionPresets] um allgemeine,
 * lebensmittelunabhängige Masse.
 */
@Composable
private fun ServingSizeGuideDialog(
    food: FoodItem,
    onSelect: (grams: Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wie viel ungefähr?") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(EverydayServingSizes.ALL) { unit ->
                    val kcal = (food.calories ?: 0f) * unit.grams / 100f
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(unit.grams) }
                            .padding(vertical = NutriSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(unit.emoji, fontSize = 22.sp)
                            Spacer(Modifier.width(NutriSpacing.sm))
                            Column {
                                Text(unit.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${unit.grams.toInt()} g",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            "${kcal.toInt()} kcal",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MacroColors.calories
                        )
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schliessen") }
        }
    )
}

@Composable
private fun FoodResultRow(
    food: FoodItem,
    onClick: () -> Unit,
    leading: @Composable () -> Unit = {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(NutriRadius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                food.name.firstOrNull()?.uppercase() ?: "?",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    },
    trailingAction: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = NutriSpacing.sm, horizontal = NutriSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(NutriSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                food.name,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                food.brand ?: "${food.calories?.toInt() ?: "–"} kcal/100g",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(NutriSpacing.sm))
        Text(
            "${food.calories?.toInt() ?: "–"} kcal",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MacroColors.calories
        )
        trailingAction?.invoke()
    }
}

@Composable
private fun MealPicker(selected: MealType, onSelect: (MealType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selected.label())
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MealType.values().forEach { meal ->
                DropdownMenuItem(
                    text    = { Text(meal.label()) },
                    onClick = { onSelect(meal); expanded = false }
                )
            }
        }
    }
}

internal fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
