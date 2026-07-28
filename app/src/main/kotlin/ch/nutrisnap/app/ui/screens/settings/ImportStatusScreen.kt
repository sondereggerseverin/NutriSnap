package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.ImportStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportStatusScreen(
    onBack: () -> Unit,
    viewModel: YazioImportViewModel = viewModel()
) {
    val progress by viewModel.importProgress.collectAsState()
    val backfilled by viewModel.backfilledIngredients.collectAsState()

    // Build stats from last ImportProgress values stored in ViewModel
    val stats = viewModel.lastImportStats.collectAsState().value

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import-Statistik") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        if (stats == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Noch kein Import durchgeführt.", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Letzter Yazio-Import",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            StatCard(
                icon        = Icons.Default.Fastfood,
                title       = "Eigene Lebensmittel",
                imported    = stats.importedFoods,
                skipped     = stats.skippedFoods,
                extra       = if (stats.backfilledIngredients > 0)
                    "${stats.backfilledIngredients} Zutaten-Makros nachgefüllt" else null
            )
            StatCard(
                icon     = Icons.Default.MenuBook,
                title    = "Rezepte",
                imported = stats.importedRecipes,
                skipped  = stats.skippedRecipes,
                extra    = if (stats.unmatchedIngredients > 0)
                    "${stats.unmatchedIngredients} Zutaten ohne Makros" else null
            )
            StatCard(
                icon     = Icons.Default.CalendarMonth,
                title    = "Tagebuch-Einträge",
                imported = stats.importedDiaryEntries,
                skipped  = stats.skippedDiaryEntries
            )

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Gesamt importiert", fontWeight = FontWeight.SemiBold)
                Text("${stats.totalImported}", fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    title: String,
    imported: Int,
    skipped: Int,
    extra: String? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Neu importiert")
                Text("$imported", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Bereits vorhanden / übersprungen")
                Text("$skipped", color = MaterialTheme.colorScheme.secondary)
            }
            if (extra != null) {
                Text(extra, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
