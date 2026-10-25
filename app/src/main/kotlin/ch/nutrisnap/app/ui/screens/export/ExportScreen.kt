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
import ch.nutrisnap.app.data.repository.PdfReportGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

private fun csvEscape(s: String): String =
    if (s.contains(',') || s.contains('"') || s.contains('\n'))
        "\"" + s.replace("\"", "\"\"") + "\""
    else s

class ExportViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val pdfReportGenerator = PdfReportGenerator(app, db)

    suspend fun generateAndShareWeeklyReport() = withContext(Dispatchers.IO) {
        val file = pdfReportGenerator.generateWeeklyReport()
        withContext(Dispatchers.Main) { pdfReportGenerator.shareReport(file) }
    }

    suspend fun generateAndShareMonthlyReport() = withContext(Dispatchers.IO) {
        val file = pdfReportGenerator.generateMonthlyReport()
        withContext(Dispatchers.Main) { pdfReportGenerator.shareReport(file) }
    }

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

    /** JSON-Export analog OpenNutriTracker (stabile Schema-Felder). */
    suspend fun buildDiaryJson(): String = withContext(Dispatchers.IO) {
        val entries = db.diaryDao().getAllOnce()
        buildString {
            append("{\n  \"format\": \"nutrisnap-diary-v1\",\n  \"entries\": [\n")
            entries.forEachIndexed { i, e ->
                if (i > 0) append(",\n")
                append("    {")
                append("\"id\":").append(e.id).append(",")
                append("\"date\":\"").append(e.dateStr).append("\",")
                append("\"meal\":\"").append(e.mealType.name).append("\",")
                append("\"name\":\"").append(e.foodName.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",")
                append("\"amountGrams\":").append(e.amountGrams).append(",")
                append("\"calories\":").append(e.calories).append(",")
                append("\"protein\":").append(e.protein).append(",")
                append("\"carbs\":").append(e.carbs).append(",")
                append("\"fat\":").append(e.fat).append(",")
                append("\"fiber\":").append(e.fiber)
                e.snapshotBrand?.let { append(",\"brand\":\"").append(it.replace("\"", "'")).append("\"") }
                e.snapshotBarcode?.let { append(",\"barcode\":\"").append(it).append("\"") }
                append("}")
            }
            append("\n  ]\n}")
        }
    }

    suspend fun buildWeightCsv(): String = withContext(Dispatchers.IO) {
        val rows = db.weightDao().getAllOnce()
        buildString {
            appendLine("Datum,Gewicht_kg")
            rows.forEach { appendLine("${it.dateStr},${fmt(it.weightKg)}") }
        }
    }

    suspend fun buildInventoryCsv(): String = withContext(Dispatchers.IO) {
        val rows = db.inventoryDao().getAllOnce()
        buildString {
            appendLine("Name,Menge,Einheit,Ort,Notizen,Barcode")
            rows.forEach { e ->
                appendLine(
                    listOf(
                        csvEscape(e.name),
                        fmt(e.quantity),
                        csvEscape(e.unit),
                        csvEscape(e.location),
                        csvEscape(e.notes),
                        csvEscape(e.barcode.orEmpty())
                    ).joinToString(",")
                )
            }
        }
    }


    /**
     * Vollbackup: Tagebuch + Gewicht + Inventar (nutrisnap-backup-v1).
     * Rezepte/Profil bewusst ausgelassen – die haben eigene Sync-/Export-Pfade.
     */
    suspend fun buildFullBackupJson(): String = withContext(Dispatchers.IO) {
        val diary = db.diaryDao().getAllOnce()
        val weights = db.weightDao().getAllOnce()
        val inventory = db.inventoryDao().getAllOnce()
        val root = JSONObject()
        root.put("format", "nutrisnap-backup-v1")
        root.put("exportedAt", java.time.Instant.now().toString())
        val diaryArr = JSONArray()
        diary.forEach { e ->
            diaryArr.put(JSONObject().apply {
                put("date", e.dateStr)
                put("meal", e.mealType.name)
                put("name", e.foodName)
                put("foodItemId", e.foodItemId)
                put("amountGrams", e.amountGrams.toDouble())
                put("calories", e.calories.toDouble())
                put("protein", e.protein.toDouble())
                put("carbs", e.carbs.toDouble())
                put("fat", e.fat.toDouble())
                put("fiber", e.fiber.toDouble())
                put("sugar", e.sugar.toDouble())
                put("saturatedFat", e.saturatedFat.toDouble())
                put("salt", e.salt.toDouble())
                put("sodium", e.sodium.toDouble())
                e.snapshotBrand?.let { put("brand", it) }
                e.snapshotBarcode?.let { put("barcode", it) }
            })
        }
        root.put("diary", diaryArr)
        val wArr = JSONArray()
        weights.forEach { w ->
            wArr.put(JSONObject().apply {
                put("date", w.dateStr)
                put("weightKg", w.weightKg.toDouble())
            })
        }
        root.put("weights", wArr)
        val iArr = JSONArray()
        inventory.forEach { item ->
            iArr.put(JSONObject().apply {
                put("name", item.name)
                put("quantity", item.quantity.toDouble())
                put("unit", item.unit)
                put("location", item.location)
                put("notes", item.notes)
                item.barcode?.let { put("barcode", it) }
                item.foodItemId?.let { put("foodItemId", it) }
            })
        }
        root.put("inventory", iArr)
        root.toString(2)
    }

    data class BackupImportResult(
        val diaryAdded: Int,
        val weightsUpserted: Int,
        val inventoryAdded: Int
    )

    /**
     * Importiert nutrisnap-backup-v1 (Merge: Tagebuch anhängen, Gewicht ersetzen pro Datum,
     * Inventar neu anlegen bzw. Menge bei gleichem Barcode addieren).
     */
    suspend fun importFullBackupJson(json: String): BackupImportResult = withContext(Dispatchers.IO) {
        val root = JSONObject(json)
        val format = root.optString("format")
        require(format == "nutrisnap-backup-v1") { "Unbekanntes Format: $format" }

        var diaryAdded = 0
        val diaryArr = root.optJSONArray("diary") ?: JSONArray()
        for (i in 0 until diaryArr.length()) {
            val o = diaryArr.getJSONObject(i)
            val meal = runCatching {
                ch.nutrisnap.app.data.model.MealType.valueOf(o.getString("meal"))
            }.getOrDefault(ch.nutrisnap.app.data.model.MealType.LUNCH)
            val entry = ch.nutrisnap.app.data.model.DiaryEntry(
                foodItemId = o.optInt("foodItemId", -999),
                foodName = o.getString("name"),
                amountGrams = o.optDouble("amountGrams", 0.0).toFloat(),
                mealType = meal,
                dateStr = o.getString("date"),
                calories = o.optDouble("calories", 0.0).toFloat(),
                protein = o.optDouble("protein", 0.0).toFloat(),
                carbs = o.optDouble("carbs", 0.0).toFloat(),
                fat = o.optDouble("fat", 0.0).toFloat(),
                fiber = o.optDouble("fiber", 0.0).toFloat(),
                sugar = o.optDouble("sugar", 0.0).toFloat(),
                saturatedFat = o.optDouble("saturatedFat", 0.0).toFloat(),
                salt = o.optDouble("salt", 0.0).toFloat(),
                sodium = o.optDouble("sodium", 0.0).toFloat(),
                snapshotBrand = o.optString("brand").takeIf { it.isNotBlank() },
                snapshotBarcode = o.optString("barcode").takeIf { it.isNotBlank() }
            )
            db.diaryDao().insert(entry)
            diaryAdded++
        }

        var weightsUpserted = 0
        val wArr = root.optJSONArray("weights") ?: JSONArray()
        for (i in 0 until wArr.length()) {
            val o = wArr.getJSONObject(i)
            db.weightDao().upsert(
                ch.nutrisnap.app.data.model.WeightEntry(
                    dateStr = o.getString("date"),
                    weightKg = o.getDouble("weightKg").toFloat()
                )
            )
            weightsUpserted++
        }

        var inventoryAdded = 0
        val invRepo = ch.nutrisnap.app.data.repository.InventoryRepository(db)
        val iArr = root.optJSONArray("inventory") ?: JSONArray()
        for (i in 0 until iArr.length()) {
            val o = iArr.getJSONObject(i)
            val barcode = o.optString("barcode").takeIf { it.isNotBlank() }
            val qty = o.optDouble("quantity", 1.0).toFloat()
            val existing = barcode?.let { invRepo.getByBarcode(it) }
            if (existing != null) {
                invRepo.updateQuantity(existing.id, existing.quantity + qty)
            } else {
                invRepo.upsert(
                    ch.nutrisnap.app.data.model.InventoryItem(
                        name = o.getString("name"),
                        quantity = qty.coerceAtLeast(0.1f),
                        unit = o.optString("unit", "Stk").ifBlank { "Stk" },
                        location = o.optString("location", ""),
                        notes = o.optString("notes", ""),
                        barcode = barcode,
                        foodItemId = if (o.has("foodItemId") && !o.isNull("foodItemId")) o.getLong("foodItemId") else null
                    )
                )
                inventoryAdded++
            }
        }
        BackupImportResult(diaryAdded, weightsUpserted, inventoryAdded)
    }

    suspend fun diaryCount(): Int = withContext(Dispatchers.IO) { db.diaryDao().getAllOnce().size }
    suspend fun weightCount(): Int = withContext(Dispatchers.IO) { db.weightDao().getAllOnce().size }
    suspend fun inventoryCount(): Int = withContext(Dispatchers.IO) { db.inventoryDao().getAllOnce().size }
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
    var inventoryCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        diaryCount = vm.diaryCount()
        weightCount = vm.weightCount()
        inventoryCount = vm.inventoryCount()
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
                }.onSuccess { snackbar.showSnackbar("✓ Datei gespeichert") }
                    .onFailure { snackbar.showSnackbar("Speichern fehlgeschlagen") }
            }
        }
    }

    val openBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Datei leer")
                }
                val result = vm.importFullBackupJson(text)
                diaryCount = vm.diaryCount()
                weightCount = vm.weightCount()
                inventoryCount = vm.inventoryCount()
                snackbar.showSnackbar(
                    "Import: ${result.diaryAdded} Tagebuch, ${result.weightsUpserted} Gewicht, ${result.inventoryAdded} Inventar"
                )
            }.onFailure { e ->
                snackbar.showSnackbar("Import fehlgeschlagen: ${e.message?.take(80) ?: "unbekannt"}")
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
                title = "Ernährungstagebuch (CSV)",
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
                title = "Ernährungstagebuch (JSON)",
                subtitle = "nutrisnap-diary-v1 · inkl. Snapshot-Felder",
                icon = Icons.Default.Code,
                onSave = {
                    scope.launch {
                        pendingContent = vm.buildDiaryJson()
                        saveLauncher.launch("nutrisnap_tagebuch.json")
                    }
                },
                onShare = {
                    scope.launch {
                        val json = vm.buildDiaryJson()
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "JSON teilen"))
                    }
                }
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

            ExportCard(
                title = "Inventar (CSV)",
                subtitle = "$inventoryCount Einträge · ohne Ablaufdatum",
                icon = Icons.Default.Kitchen,
                onSave = {
                    scope.launch {
                        pendingContent = vm.buildInventoryCsv()
                        saveLauncher.launch("nutrisnap_inventar.csv")
                    }
                },
                onShare = { scope.launch { share(vm.buildInventoryCsv()) } }
            )

            ExportCard(
                title = "Vollbackup (JSON)",
                subtitle = "Tagebuch + Gewicht + Inventar · nutrisnap-backup-v1",
                icon = Icons.Default.Backup,
                onSave = {
                    scope.launch {
                        pendingContent = vm.buildFullBackupJson()
                        saveLauncher.launch("nutrisnap_backup.json")
                    }
                },
                onShare = {
                    scope.launch {
                        val json = vm.buildFullBackupJson()
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_TEXT, json)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Backup teilen"))
                    }
                }
            )

            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.UploadFile, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Backup importieren", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("nutrisnap-backup-v1 JSON · Merge (kein Löschen)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Button(
                        onClick = { openBackupLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Datei wählen", fontSize = 13.sp)
                    }
                }
            }

            Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("PDF-Report", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Kalorien, Makros & Gewicht als Bericht zum Teilen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { vm.generateAndShareWeeklyReport() }
                                        .onFailure { snackbar.showSnackbar("Report fehlgeschlagen") }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Wochenreport", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { vm.generateAndShareMonthlyReport() }
                                        .onFailure { snackbar.showSnackbar("Report fehlgeschlagen") }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Monatsbericht", fontSize = 13.sp) }
                    }
                }
            }
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
