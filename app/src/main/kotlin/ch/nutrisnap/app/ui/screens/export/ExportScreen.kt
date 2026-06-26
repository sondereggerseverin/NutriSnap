package ch.nutrisnap.app.ui.screens.export

import android.app.Application
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.db.NutriDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

private fun csvEscape(s: String): String =
    if (s.contains(',') || s.contains('"') || s.contains('\n'))
        "\"" + s.replace("\"", "\"\"") + "\""
    else s

class ExportViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)

    suspend fun buildDiaryCsv(): String = withContext(Dispatchers.IO) {
        val entries = db.diaryDao().getAllOnce()
        buildString {
            appendLine("Datum,Mahlzeit,Lebensmittel,Menge_g,Kalorien,Protein_g,Kohlenhydrate_g,Fett_g")
            entries.forEach { e ->
                appendLine(
                    listOf(
                        e.dateStr, e.mealType.name, csvEscape(e.foodName),
                        fmt(e.amountGrams), fmt(e.calories), fmt(e.protein),
                        fmt(e.carbs), fmt(e.fat)
                    ).joinToString(",")
                )
            }
        }
    }

    suspend fun buildWeightCsv(): String = withContext(Dispatchers.IO) {
        val rows = db.weightDao().getAllOnce()
        buildString {
            appendLine("Datum,Gewicht_kg")
            rows.forEach { appendLine("${it.dateStr},${fmt(it.weightKg)}") }
        }
    }

    suspend fun diaryCount(): Int = withContext(Dispatchers.IO) { db.diaryDao().getAllOnce().size }
    suspend fun weightCount(): Int = withContext(Dispatchers.IO) { db.weightDao().getAllOnce().size }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    onBack: () -> Unit = {},
    vm: ExportViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var diaryCount by remember { mutableStateOf(0) }
    var weightCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        diaryCount = vm.diaryCount()
        weightCount = vm.weightCount()
    }

    var pendingContent by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val content = pendingContent
        if (uri != null && content != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    }
                }.onSuccess { snackbar.showSnackbar("✓ CSV gespeichert") }
                    .onFailure { snackbar.showSnackbar("Speichern fehlgeschlagen") }
            }
        }
    }

    fun share(content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(Intent.createChooser(intent, "Daten teilen"))
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Daten exportieren") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Exportiere deine Daten als CSV-Datei – z.B. für Excel, Google Sheets oder dein Backup.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ExportCard(
                title = "Ernährungstagebuch",
                subtitle = "$diaryCount Einträge",
                icon = Icons.Default.MenuBook,
                onSave = {
                    scope.launch {
                        pendingContent = vm.buildDiaryCsv()
                        saveLauncher.launch("nutrisnap_tagebuch.csv")
                    }
                },
                onShare = { scope.launch { share(vm.buildDiaryCsv()) } }
            )

            ExportCard(
                title = "Gewichtsverlauf",
                subtitle = "$weightCount Einträge",
                icon = Icons.Default.MonitorWeight,
                onSave = {
                    scope.launch {
                        pendingContent = vm.buildWeightCsv()
                        saveLauncher.launch("nutrisnap_gewicht.csv")
                    }
                },
                onShare = { scope.launch { share(vm.buildWeightCsv()) } }
            )
        }
    }
}

@Composable
private fun ExportCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onSave: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Speichern", fontSize = 13.sp)
                }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp)); Text("Teilen", fontSize = 13.sp)
                }
            }
        }
    }
}
