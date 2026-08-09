package ch.nutrisnap.app.ui.screens.customfood

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.EntryPlausibilityChecker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCustomFoodScreen(
    onBack: () -> Unit,
    vm: CustomFoodViewModel = viewModel(),
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
    var name by remember { mutableStateOf(prefillName.orEmpty()) }
    var calories by remember { mutableStateOf(prefillCalories?.let { formatNum(it) }.orEmpty()) }
    var protein by remember { mutableStateOf(prefillProtein?.let { formatNum(it) }.orEmpty()) }
    var carbs by remember { mutableStateOf(prefillCarbs?.let { formatNum(it) }.orEmpty()) }
    var fat by remember { mutableStateOf(prefillFat?.let { formatNum(it) }.orEmpty()) }
    var fiber by remember { mutableStateOf(prefillFiber?.let { formatNum(it) }.orEmpty()) }
    var sugar by remember { mutableStateOf(prefillSugar?.let { formatNum(it) }.orEmpty()) }
    var salt by remember { mutableStateOf(prefillSalt?.let { formatNum(it) }.orEmpty()) }
    var portionG by remember {
        mutableStateOf(prefillPortionG?.let { formatNum(it) } ?: "100")
    }
    var barcode by remember { mutableStateOf(prefillBarcode.orEmpty()) }
    var brand by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var macroWarning by remember { mutableStateOf<String?>(null) }

    val nameError = name.isBlank() && saved
    val calError = (calories.toFloatOrNull() ?: -1f) < 0 && saved

    val isValid = name.isNotBlank()
        && (calories.toFloatOrNull() ?: -1f) >= 0
        && (protein.toFloatOrNull() ?: -1f) >= 0
        && (carbs.toFloatOrNull() ?: -1f) >= 0
        && (fat.toFloatOrNull() ?: -1f) >= 0
        && (portionG.toFloatOrNull() ?: 0f) > 0f

    fun doSave() {
        vm.save(
            name = name,
            calories = calories.replace(',', '.').toFloat(),
            protein = protein.replace(',', '.').toFloat(),
            carbs = carbs.replace(',', '.').toFloat(),
            fat = fat.replace(',', '.').toFloat(),
            fiber = fiber.replace(',', '.').toFloatOrNull() ?: 0f,
            sugar = sugar.replace(',', '.').toFloatOrNull() ?: 0f,
            salt = salt.replace(',', '.').toFloatOrNull() ?: 0f,
            portionSizeG = portionG.replace(',', '.').toFloatOrNull() ?: 100f,
            barcode = barcode.trim().ifBlank { null },
            brand = brand.trim().ifBlank { null }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Eigenes Lebensmittel") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") }
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
                    label = { Text("Portion (g) *") },
                    supportingText = { Text("z. B. 50 g pro Riegel") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it.filter { c -> c.isDigit() } },
                    label = { Text("Barcode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            Text("Nährwerte pro 100 g", fontWeight = FontWeight.SemiBold)

            NutrientField("Kalorien (kcal) *", calories, calError) { calories = it }
            NutrientField("Protein (g) *", protein, false) { protein = it }
            NutrientField("Kohlenhydrate (g) *", carbs, false) { carbs = it }
            NutrientField("Fett (g) *", fat, false) { fat = it }
            NutrientField("Ballaststoffe (g)", fiber, false) { fiber = it }
            NutrientField("Zucker (g)", sugar, false) { sugar = it }
            NutrientField("Salz (g)", salt, false) { salt = it }

            val pG = portionG.replace(',', '.').toFloatOrNull()
            val cal100 = calories.replace(',', '.').toFloatOrNull()
            if (pG != null && pG > 0f && cal100 != null) {
                val f = pG / 100f
                val pProt = (protein.replace(',', '.').toFloatOrNull() ?: 0f) * f
                val pCarb = (carbs.replace(',', '.').toFloatOrNull() ?: 0f) * f
                val pFat = (fat.replace(',', '.').toFloatOrNull() ?: 0f) * f
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "1 Portion (${pG.toInt()} g): ${(cal100 * f).toInt()} kcal · " +
                            "${pProt.toInt()} g Protein · ${pCarb.toInt()} g KH · ${pFat.toInt()} g Fett",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    saved = true
                    if (isValid) {
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
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Speichern") }
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
