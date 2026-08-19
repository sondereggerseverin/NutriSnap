package ch.nutrisnap.app.ui.screens.recipes

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.UrlExtractor
import ch.nutrisnap.app.ui.theme.MacroColors

// ── Import Sheet ──────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    prefillUrl: String,
    isLoading: Boolean,
    error: String?,
    importPhase: String? = null,
    openAtManualCaption: Boolean = false,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current; val vm: RecipesViewModel = viewModel()
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var showManual by remember(openAtManualCaption) { mutableStateOf(openAtManualCaption) }
    var manualTitle by remember { mutableStateOf("") }; var manualCaption by remember { mutableStateOf("") }
    var hybridScreenshot by remember { mutableStateOf<Bitmap?>(null) }
    val isInstagram = "instagram.com" in url.lowercase() || "instagr.am" in url.lowercase()

    fun decodePickedBitmap(uri: Uri): Bitmap? =
        ch.nutrisnap.app.utils.ImageDecodeUtils.decodeUri(context, uri, maxEdgePx = 2048)

    // Bild-Import / Hybrid-Screenshot: kein Crop (OCR braucht volle Tabelle/Text).
    // Zuschneiden nur bei Rezept-Fotos (RecipeEditSheet).
    // Decode immer mit Downsampling – volle Kamera-MP → sonst OOM/Hänger.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bitmap = decodePickedBitmap(uri)
                ?: throw IllegalStateException("Bild konnte nicht geladen werden")
            vm.importFromImage(bitmap)
        }
    }

    val hybridScreenshotPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        hybridScreenshot = decodePickedBitmap(uri)
    }

    LaunchedEffect(error) {
        if (error != null && isInstagram) showManual = true
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            if (!showManual) {
                Text(
                    "Rezept importieren",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Instagram, TikTok oder Webseite",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                // Instagram-specific import button
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CameraAlt, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Rezepte aus Instagram importieren",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Link kopieren und unten einfügen",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PhotoCamera, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rezept aus Bild importieren")
                }
                Text(
                    "Screenshot oder Foto einer Rezeptkarte auswählen",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp)
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Oder per Link", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(value=url, onValueChange={url=it}, label={Text("URL einfügen")},
                    leadingIcon={Icon(Icons.Default.Link,null)}, modifier=Modifier.fillMaxWidth(), singleLine=true, isError=error!=null)
                if (error != null) Text(error, color=MaterialTheme.colorScheme.error, fontSize=13.sp, modifier=Modifier.padding(top=4.dp))

                // Hybrid: bei Instagram-Link optional Rezept-Screenshot anhängen
                if (isInstagram && url.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Caption leer? Rezept-Screenshot anhängen",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Link liefert Bild + Quelle, Screenshot die Zutaten/Anleitung.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        hybridScreenshotPicker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    enabled = !isLoading,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (hybridScreenshot != null) "Screenshot gewählt"
                                        else "Screenshot wählen"
                                    )
                                }
                                if (hybridScreenshot != null) {
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { hybridScreenshot = null },
                                        enabled = !isLoading
                                    ) {
                                        Icon(Icons.Default.Close, "Entfernen")
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isInstagram && hybridScreenshot != null) {
                            vm.importHybridFromInstagram(url.trim(), hybridScreenshot)
                        } else {
                            onImport(url.trim())
                        }
                    },
                    enabled = url.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            isLoading && !importPhase.isNullOrBlank() -> importPhase
                            isLoading -> "Importiere…"
                            isInstagram && hybridScreenshot != null -> "Link + Screenshot importieren"
                            else -> "Importieren"
                        }
                    )
                }
                if (isLoading && !importPhase.isNullOrBlank()) {
                    Text(
                        importPhase!!,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (isInstagram) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick={showManual=true}, modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ContentPaste,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Caption manuell einfügen")
                    }
                }
            } else {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if (!openAtManualCaption) IconButton(onClick={showManual=false}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Zurück") }
                    Column(Modifier.weight(1f)) {
                        Text("Caption einfügen", fontWeight=FontWeight.Bold, fontSize=18.sp)
                        if (openAtManualCaption) Text("Instagram blockiert automatischen Import.", fontSize=12.sp, color=MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply{flags=Intent.FLAG_ACTIVITY_NEW_TASK}) }}, modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.OpenInNew,null,Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Instagram öffnen & Caption kopieren")
                }
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                OutlinedTextField(value=manualTitle, onValueChange={manualTitle=it}, label={Text("Titel (optional)")}, modifier=Modifier.fillMaxWidth(), singleLine=true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value=manualCaption, onValueChange={manualCaption=it}, label={Text("Caption einfügen")}, modifier=Modifier.fillMaxWidth().heightIn(min=140.dp), maxLines=12)
                Spacer(Modifier.height(12.dp))
                Button(onClick={ if(manualCaption.isNotBlank()){vm.saveManualRecipe(url.trim(),manualTitle.trim().ifBlank{null},manualCaption.trim());onDismiss()}}, enabled=manualCaption.isNotBlank(), modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Save,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Speichern")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Batch-Import-Sheet ──────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchImportSheet(
    state: BatchImportState,
    onAddUrls: (List<String>) -> Unit,
    onRemoveItem: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit
) {
    var pasteText by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("Rezepte importieren", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.height(4.dp))
            Text("Mehrere Links auf einmal importieren",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            // Instagram multi-import card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlaylistAdd, null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Mehrere Rezepte auf einmal", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Links aus Zwischenablage einfügen", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = pasteText, onValueChange = { pasteText = it },
                label = { Text("Links einfügen (ein pro Zeile)") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp), maxLines = 8
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val urls = UrlExtractor.extractAll(pasteText)
                    if (urls.isNotEmpty()) { onAddUrls(urls); pasteText = "" }
                },
                enabled = UrlExtractor.extractAll(pasteText).isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Zur Warteschlange hinzufügen")
            }

            if (state.items.isNotEmpty()) {
                Spacer(Modifier.height(16.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))

                // Progress bar
                val progress = if (state.items.isNotEmpty()) state.doneCount.toFloat() / state.items.size else 0f
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${state.doneCount}/${state.items.size} importiert",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.isRunning) {
                            Text(
                                "Analysiere ${state.items.size} Rezepte…",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))

                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    items(state.items, key = { it.url }) { item ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val (icon, tint) = when (item.status) {
                                BatchStatus.PENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
                                BatchStatus.RUNNING  -> Icons.Default.Sync      to MaterialTheme.colorScheme.primary
                                BatchStatus.DONE     -> Icons.Default.CheckCircle to MacroColors.calories
                                BatchStatus.ERROR    -> Icons.Default.Error     to MaterialTheme.colorScheme.error
                            }
                            Icon(icon, null, Modifier.size(18.dp), tint = tint)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.resultTitle ?: item.url.take(50),
                                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (item.status == BatchStatus.DONE) FontWeight.SemiBold else FontWeight.Normal
                                )
                                if (item.error != null) {
                                    Text(item.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (item.status == BatchStatus.PENDING && !state.isRunning) {
                                IconButton(onClick = { onRemoveItem(item.url) }, Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Close, "Entfernen", Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onStart,
                    enabled = !state.isRunning && state.items.any { it.status != BatchStatus.DONE },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isRunning) "Importiere…" else "Import starten")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
