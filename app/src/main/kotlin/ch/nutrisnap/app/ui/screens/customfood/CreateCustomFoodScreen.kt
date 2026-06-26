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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCustomFoodScreen(
    onBack: () -> Unit,
    vm: CustomFoodViewModel = viewModel()
) {
    var name     by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein  by remember { mutableStateOf("") }
    var carbs    by remember { mutableStateOf("") }
    var fat      by remember { mutableStateOf("") }
    var fiber    by remember { mutableStateOf("") }
    var saved    by remember { mutableStateOf(false) }

    val nameError = name.isBlank() && saved
    val calError  = (calories.toFloatOrNull() ?: -1f) < 0 && saved

    val isValid = name.isNotBlank()
        && (calories.toFloatOrNull() ?: -1f) >= 0
        && (protein.toFloatOrNull()  ?: -1f) >= 0
        && (carbs.toFloatOrNull()    ?: -1f) >= 0
        && (fat.toFloatOrNull()      ?: -1f) >= 0

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
            Text("Nährwerte pro 100 g", style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name *") },
                isError = nameError,
                supportingText = { if (nameError) Text("Name darf nicht leer sein") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            NutrientField("Kalorien (kcal) *", calories, calError) { calories = it }
            NutrientField("Protein (g) *",     protein,  false)     { protein  = it }
            NutrientField("Kohlenhydrate (g) *", carbs,  false)     { carbs    = it }
            NutrientField("Fett (g) *",         fat,     false)     { fat      = it }
            NutrientField("Ballaststoffe (g)",  fiber,   false)     { fiber    = it }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    saved = true
                    if (isValid) {
                        vm.save(
                            name     = name,
                            calories = calories.toFloat(),
                            protein  = protein.toFloat(),
                            carbs    = carbs.toFloat(),
                            fat      = fat.toFloat(),
                            fiber    = fiber.toFloatOrNull() ?: 0f
                        )
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Speichern") }
        }
    }
}

@Composable
private fun NutrientField(
    label: String, value: String, isError: Boolean, onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(), singleLine = true
    )
}
