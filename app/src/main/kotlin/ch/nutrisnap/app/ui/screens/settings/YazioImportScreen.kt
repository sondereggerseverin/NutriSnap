package ch.nutrisnap.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YazioImportScreen(
    onBack: () -> Unit,
    viewModel: YazioImportViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val recipeState by viewModel.recipeState.collectAsState()
    val foodState by viewModel.foodState.collectAsState()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importNutritionLog(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Yazio Import") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurueck")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Tagebuch-Import (CSV) ──────────────────────────────────────
            Text(
                "Importiere deinen Yazio-Export (nutrition_log.csv) direkt in dein NutriSnap-Tagebuch.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))

            var showClearConfirm by remember { mutableStateOf(false) }

            when (val s = state) {
                is YazioImportState.Idle -> {
                    Button(onClick = { filePicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("nutrition_log.csv auswaehlen")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Tagebuch zuerst leeren")
                    }
                }
                is YazioImportState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Importiere...")
                }
                is YazioImportState.Success -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Import abgeschlossen", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Tage: ${s.result.importedDays}")
                            Text("Eintraege importiert: ${s.result.importedEntries}")
                            if (s.result.skippedEntries > 0) {
                                Text("Uebersprungen: ${s.result.skippedEntries}")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.reset() }) {
                        Text("Weitere Datei importieren")
                    }
                }
                is YazioImportState.Error -> {
                    Text(
                        "Fehler: ${s.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { viewModel.reset() }) {
                        Text("Erneut versuchen")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── Rezepte-Import (mitgelieferte yazio_recipes.json) ──────────
            Text(
                "Importiere deine ${"\u00fc"}ber Jahre gesammelten Yazio-Rezepte (inkl. Zutaten) direkt in deine Rezepte-Sammlung.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))

            when (val rs = recipeState) {
                is YazioRecipeImportState.Idle -> {
                    Button(onClick = { viewModel.importBundledRecipes() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.RestaurantMenu, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Yazio-Rezepte importieren")
                    }
                }
                is YazioRecipeImportState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Rezepte werden importiert...")
                }
                is YazioRecipeImportState.Success -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Rezept-Import abgeschlossen", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Importiert: ${rs.result.importedRecipes}")
                            if (rs.result.skippedRecipes > 0) {
                                Text("Bereits vorhanden (uebersprungen): ${rs.result.skippedRecipes}")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.resetRecipeState() }) {
                        Text("Erneut pruefen")
                    }
                }
                is YazioRecipeImportState.Error -> {
                    Text("Fehler: ${rs.message}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.resetRecipeState() }) {
                        Text("Erneut versuchen")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ── Eigene Produkte-Import (mitgelieferte yazio_foods.json) ────
            Text(
                "Importiere deine eigenen Yazio-Produkte (selbst erstellte/gescannte Lebensmittel) als eigene Lebensmittel in NutriSnap.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(16.dp))

            when (val fs = foodState) {
                is YazioFoodImportState.Idle -> {
                    Button(onClick = { viewModel.importBundledFoods() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Fastfood, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Yazio-Produkte importieren")
                    }
                }
                is YazioFoodImportState.Loading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Produkte werden importiert...")
                }
                is YazioFoodImportState.Success -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Produkt-Import abgeschlossen", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Text("Importiert: ${fs.result.importedFoods}")
                            if (fs.result.skippedFoods > 0) {
                                Text("Bereits vorhanden (uebersprungen): ${fs.result.skippedFoods}")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.resetFoodState() }) {
                        Text("Erneut pruefen")
                    }
                }
                is YazioFoodImportState.Error -> {
                    Text("Fehler: ${fs.message}", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { viewModel.resetFoodState() }) {
                        Text("Erneut versuchen")
                    }
                }
            }

            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("Tagebuch wirklich leeren?") },
                    text = { Text("Alle Tagebuch-Eintraege (manuell, importiert, Rezepte) werden unwiderruflich geloescht. Das ist sinnvoll vor einem sauberen Yazio-Reimport.") },
                    confirmButton = {
                        TextButton(onClick = {
                            showClearConfirm = false
                            viewModel.clearAllDiaryEntries {}
                        }) {
                            Text("Loeschen", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text("Abbrechen")
                        }
                    }
                )
            }
        }
    }
}
