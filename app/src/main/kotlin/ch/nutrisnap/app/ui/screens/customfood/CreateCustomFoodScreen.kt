package ch.nutrisnap.app.ui.screens.customfood

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.domain.EntryPlausibilityChecker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCustomFoodScreen(
    onBack: () -> Unit,
    vm: CustomFoodViewModel = viewModel(),
    editId: Int? = null,
    prefillBarcode: String? = null,
    prefillName: String? = null,
    prefillCalories: Float? = null,
    prefillProtein: Float? = null,
    prefillCarbs: Float? = null,
    prefillFat: Float? = null,
    prefillFiber: Float? = null,
    prefillSugar: Float? = null,
    prefillSalt: Float? = null,
    prefillPortionG: Float? = null
) {
    var existing by remember { mutableStateOf<CustomFoodItem?>(null) }
    var loaded by remember { mutableStateOf(editId == null) }
    LaunchedEffect(editId) {
        if (editId != null) {
            existing = vm.getById(editId)
            loaded = true
        }
    }

    if (!loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val base = existing
    var name by remember { mutableStateOf(base?.name ?: prefillName.orEmpty()) }
    var calories by remember {
        mutableStateOf(base?.calories?.let { formatNum(it) } ?: prefillCalories?.let { formatNum(it) }.orEmpty())
    }
    var protein by remember {
        mutableStateOf(base?.protein?.let { formatNum(it) } ?: prefillProtein?.let { formatNum(it) }.orEmpty())
    }
    var carbs by remember {
        mutableStateOf(base?.carbs?.let { formatNum(it) } ?: prefillCarbs?.let { formatNum(it) }.orEmpty())
    }
    var fat by remember {
        mutableStateOf(base?.fat?.let { formatNum(it) } ?: prefillFat?.let { formatNum(it) }.orEmpty())
    }
    var fiber by remember {
        mutableStateOf(base?.fiber?.let { formatNum(it) } ?: prefillFiber?.let { formatNum(it) }.orEmpty())
    }
    var sugar by remember {
        mutableStateOf(base?.sugar?.let { formatNum(it) } ?: prefillSugar?.let { formatNum(it) }.orEmpty())
    }
    var salt by remember {
        mutableStateOf(base?.salt?.let { formatNum(it) } ?: prefillSalt?.let { formatNum(it) }.orEmpty())
    }
    var portionG by remember {
        mutableStateOf(
            base?.portionSizeG?.let { formatNum(it) }
                ?: prefillPortionG?.let { formatNum(it) }
                ?: "100"
        )
    }
    var barcode by remember { mutableStateOf(base?.barcode ?: prefillBarcode.orEmpty()) }
    var brand by remember { mutableStateOf(base?.brand.orEmpty()) }
    var markVerified by remember { mutableStateOf(base?.verified ?: true) }
    var saved by remember { mutableStateOf(false) }
    var macroWarning by remember { mutableStateOf<String?>(null) }

    val nameError = name.isBlank() && saved
    val calError = (calories.replace(',', '.').toFloatOrNull() ?: -1f) < 0 && saved

    val isValid = name.isNotBlank()
        && (calories.replace(',', '.').toFloatOrNull() ?: -1f) >= 0
        && (protein.replace(',', '.').toFloatOrNull() ?: -1f) >= 0
        && (carbs.replace(',', '.').toFloatOrNull() ?: -1f) >= 0
        && (fat.replace(',', '.').toFloatOrNull() ?: -1f) >= 0
        && (portionG.replace(',', '.').toFloatOrNull() ?: 0f) > 0f

    fun doSave() {
        val cal = calories.replace(',', '.').toFloat()
        val prot = protein.replace(',', '.').toFloat()
        val carb = carbs.replace(',', '.').toFloat()
        val f = fat.replace(',', '.').toFloat()
        val fib = fiber.replace(',', '.').toFloatOrNull() ?: 0f
        val sug = sugar.replace(',', '.').toFloatOrNull() ?: 0f
        val sal = salt.replace(',', '.').toFloatOrNull() ?: 0f
        val portion = portionG.replace(',', '.').toFloatOrNull() ?: 100f
        val bc = barcode.trim().ifBlank { null }
        val br = brand.trim().ifBlank { null }

        if (base != null) {
            vm.update(
                base.copy(
                    name = name.trim(),
                    calories = cal,
                    protein = prot,
                    carbs = carb,
                    fat = f,
                    fiber = fib,
                    sugar = sug,
                    salt = sal,
                    portionSizeG = portion.coerceAtLeast(1f),
                    barcode = bc,
                    brand = br,
                    verified = markVerified
                )
            )
        } else {
            vm.save(
                name = name,
                calories = cal,
                protein = prot,
                carbs = carb,
                fat = f,
                fiber = fib,
                sugar = sug,
                salt = sal,
                portionSizeG = portion,
                barcode = bc,
                brand = br,
                verified = markVerified
            )
        }
    }

    fun trySave() {
        saved = true
        if (!isValid) return
        val warning = EntryPlausibilityChecker.checkManualEntry(
            calories.replace(',', '.').toFloat(),
            protein.replace(',', '.').toFloat(),
            carbs.replace(',', '.').toFloat(),
            fat.replace(',', '.').toFloat()
        )
        if (warning != null) {
            macroWarning = warning
        } else {
            doSave()
            onBack()
        }
    }

    val cal100 = calories.replace(',', '.').toFloatOrNull() ?: 0f
    val pProt = protein.replace(',', '.').toFloatOrNull() ?: 0f
    val pCarb = carbs.replace(',', '.').toFloatOrNull() ?: 0f
    val pFat = fat.replace(',', '.').toFloatOrNull() ?: 0f
    val portionVal = portionG.replace(',', '.').toFloatOrNull() ?: 100f
    val factor = if (portionVal > 0f) portionVal / 100f else 1f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (base != null) "Lebensmittel bearbeiten" else "Eigenes Lebensmittel")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Nährwerte pro 100 g. Die Portionsgrösse ist der Vorschlag beim Tracken (z. B. 50 g Riegel).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (base != null) {
                Text(
                    "Quelle: ${sourceLabel(base.source)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name *") },
                isError = nameError,
                supportingText = { if (nameError) Text("Name darf nicht leer sein") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = brand,
                onValueChange = { brand = it },
                label = { Text("Marke (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portionG,
                    onValueChange = { portionG = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    label = { Text("Portion (g)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("Barcode") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text("Makros pro 100 g", style = MaterialTheme.typography.titleSmall)

            NutrientField("Kalorien (kcal) *", calories, calError) { calories = it }
            NutrientField("Protein (g) *", protein, false) { protein = it }
            NutrientField("Kohlenhydrate (g) *", carbs, false) { carbs = it }
            NutrientField("Fett (g) *", fat, false) { fat = it }
            NutrientField("Ballaststoffe (g)", fiber, false) { fiber = it }
            NutrientField("Zucker (g)", sugar, false) { sugar = it }
            NutrientField("Salz (g)", salt, false) { salt = it }

            if (portionVal != 100f && portionVal > 0f) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        "Portion (${portionVal.toInt()} g): ${(cal100 * factor).toInt()} kcal · " +
                            "${(pProt * factor).toInt()} g Protein · ${(pCarb * factor).toInt()} g KH · " +
                            "${(pFat * factor).toInt()} g Fett",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Als verifiziert markieren", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Bestätigt, dass Name und Nährwerte stimmen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = markVerified, onCheckedChange = { markVerified = it })
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { trySave() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (base != null) "Speichern" else "Hinzufügen") }
        }
    }

    macroWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { macroWarning = null },
            title = { Text("Werte prüfen") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = {
                    doSave()
                    macroWarning = null
                    onBack()
                }) { Text("Trotzdem speichern") }
            },
            dismissButton = {
                TextButton(onClick = { macroWarning = null }) { Text("Korrigieren") }
            }
        )
    }
}

@Composable
private fun NutrientField(
    label: String,
    value: String,
    isError: Boolean,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' || c == ',' }) },
        label = { Text(label) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

private fun formatNum(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)

private fun sourceLabel(source: String): String = when (source) {
    "yazio_import" -> "Yazio-Import"
    "yazio_recipe_ingredient" -> "Yazio-Rezeptzutat"
    "yazio_diary_only" -> "Yazio-Tagebuch"
    "label_scan" -> "Nährwerttabelle (Scan)"
    "manual" -> "Manuell"
    else -> source
}
