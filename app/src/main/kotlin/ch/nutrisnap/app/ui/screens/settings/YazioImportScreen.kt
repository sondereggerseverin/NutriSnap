package ch.nutrisnap.app.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.UploadFile
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
            Text(
                "Importiere deinen Yazio-Export (nutrition_log.csv) direkt in dein NutriSnap-Tagebuch.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))

            when (val s = state) {
                is YazioImportState.Idle -> {
                    Button(onClick = { filePicker.launch("text/csv") }) {
                        Icon(Icons.Filled.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("nutrition_log.csv auswaehlen")
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
        }
    }
}
