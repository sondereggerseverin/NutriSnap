package ch.nutrisnap.app.ui.screens.scan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.NutritionLabelResult

@Composable
fun NutritionLabelScanScreen(
    onNavigateBack: () -> Unit,
    vm: NutritionLabelScanViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    when (val s = state) {
        is LabelScanState.Capturing -> PhotoCaptureScreen(
            title = "Nährwerttabelle scannen",
            instructions = "Foto der Nährwerttabelle machen – Werte pro 100g werden automatisch erkannt",
            onPhotoCaptured = { bitmap -> vm.analyzePhoto(bitmap) },
            onNavigateBack = onNavigateBack
        )
        is LabelScanState.Analyzing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Lese Nährwerttabelle…")
            }
        }
        is LabelScanState.Result -> LabelResultView(
            result = s.result,
            onSave = { name, result -> vm.saveAsProduct(name, result) },
            onRetake = { vm.retake() },
            onBack = onNavigateBack
        )
        is LabelScanState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.retake() }) { Text("Erneut versuchen") }
            }
        }
        is LabelScanState.Saved -> LaunchedEffect(Unit) { onNavigateBack() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelResultView(
    result: NutritionLabelResult,
    onSave: (String, NutritionLabelResult) -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit
) {
    var name     by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf(result.caloriesPer100g.toInt().toString()) }
    var protein  by remember { mutableStateOf(result.proteinPer100g.toString()) }
    var carbs    by remember { mutableStateOf(result.carbsPer100g.toString()) }
    var fat      by remember { mutableStateOf(result.fatPer100g.toString()) }
    var fiber    by remember { mutableStateOf(result.fiberPer100g.toString()) }
    var saved    by remember { mutableStateOf(false) }

    val nameError = name.isBlank() && saved

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nährwerte erkannt") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Automatisch aus dem Foto erkannt – bitte kurz prüfen und Produktnamen eingeben.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Produktname *") },
                isError = nameError,
                supportingText = { if (nameError) Text("Name darf nicht leer sein") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Text("Pro 100 g", fontWeight = FontWeight.SemiBold)
            LabelField("Kalorien (kcal)", calories) { calories = it }
            LabelField("Protein (g)", protein) { protein = it }
            LabelField("Kohlenhydrate (g)", carbs) { carbs = it }
            LabelField("Fett (g)", fat) { fat = it }
            LabelField("Ballaststoffe (g)", fiber) { fiber = it }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Neu fotografieren") }
                Button(
                    onClick = {
                        saved = true
                        if (name.isNotBlank()) {
                            onSave(
                                name,
                                NutritionLabelResult(
                                    caloriesPer100g = calories.toFloatOrNull() ?: 0f,
                                    proteinPer100g  = protein.toFloatOrNull()  ?: 0f,
                                    carbsPer100g    = carbs.toFloatOrNull()    ?: 0f,
                                    fatPer100g      = fat.toFloatOrNull()      ?: 0f,
                                    fiberPer100g    = fiber.toFloatOrNull()    ?: 0f
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Speichern") }
            }
        }
    }
}

@Composable
private fun LabelField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(), singleLine = true
    )
}
