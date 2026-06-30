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
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.FoodScanResult

@Composable
fun FoodScanScreen(
    onNavigateBack: () -> Unit,
    vm: FoodScanViewModel = viewModel()
) {
    val state by vm.state.collectAsState()

    when (val s = state) {
        is FoodScanState.Capturing -> PhotoCaptureScreen(
            title = "Essen scannen",
            instructions = "Foto vom Teller machen – die KI schätzt Kalorien & Makros",
            onPhotoCaptured = { bitmap -> vm.analyzePhoto(bitmap) },
            onNavigateBack = onNavigateBack
        )
        is FoodScanState.Analyzing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Analysiere Foto…")
            }
        }
        is FoodScanState.Result -> FoodScanResultView(
            result = s.result,
            onSave = { edited, mealType -> vm.saveToDiary(edited, mealType) },
            onRetake = { vm.retake() },
            onBack = onNavigateBack
        )
        is FoodScanState.Error -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.retake() }) { Text("Erneut versuchen") }
            }
        }
        is FoodScanState.Saved -> LaunchedEffect(Unit) { onNavigateBack() }
    }
}

@Composable
private fun FoodScanResultView(
    result: FoodScanResult,
    onSave: (FoodScanResult, MealType) -> Unit,
    onRetake: () -> Unit,
    onBack: () -> Unit
) {
    var gramsText by remember { mutableStateOf(result.estimatedGrams.toInt().toString()) }
    var mealType by remember { mutableStateOf(MealType.LUNCH) }
    var mealMenuExpanded by remember { mutableStateOf(false) }

    val originalGrams = result.estimatedGrams.coerceAtLeast(1f)
    val factor = (gramsText.toFloatOrNull() ?: originalGrams) / originalGrams
    val scaled = result.copy(
        estimatedGrams = gramsText.toFloatOrNull() ?: result.estimatedGrams,
        calories = result.calories * factor,
        protein = result.protein * factor,
        carbs = result.carbs * factor,
        fat = result.fat * factor
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ergebnis") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(result.foodName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            AssistChip(onClick = {}, label = { Text("Sicherheit der Schätzung: ${result.confidence}") })

            OutlinedTextField(
                value = gramsText,
                onValueChange = { gramsText = it },
                label = { Text("Geschätzte Menge (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroRow("Kalorien", "${scaled.calories.toInt()} kcal")
                    MacroRow("Protein", "${scaled.protein.toInt()} g")
                    MacroRow("Kohlenhydrate", "${scaled.carbs.toInt()} g")
                    MacroRow("Fett", "${scaled.fat.toInt()} g")
                }
            }

            ExposedDropdownMenuBox(expanded = mealMenuExpanded, onExpandedChange = { mealMenuExpanded = it }) {
                OutlinedTextField(
                    value = mealType.name, onValueChange = {}, readOnly = true,
                    label = { Text("Mahlzeit") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = mealMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = mealMenuExpanded, onDismissRequest = { mealMenuExpanded = false }) {
                    MealType.entries.forEach { type ->
                        DropdownMenuItem(text = { Text(type.name) }, onClick = { mealType = type; mealMenuExpanded = false })
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Neu fotografieren") }
                Button(onClick = { onSave(scaled, mealType) }, modifier = Modifier.weight(1f)) { Text("Speichern") }
            }
        }
    }
}

@Composable
private fun MacroRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
