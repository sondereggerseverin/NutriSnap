package ch.nutrisnap.app.ui.screens.recipes

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.ui.components.EmptyState
import coil.compose.AsyncImage

@Composable
fun RecipesScreen(
    vm: RecipesViewModel = viewModel(),
    sharedUrl: String?   = null
) {
    val state by vm.uiState.collectAsState()
    var showImportSheet  by remember { mutableStateOf(false) }
    var selectedRecipe   by remember { mutableStateOf<Recipe?>(null) }

    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank()) showImportSheet = true
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.secondary) {
                Icon(Icons.Default.Link, "Rezept importieren",
                    tint = MaterialTheme.colorScheme.onSecondary)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(
                value           = state.query,
                onValueChange   = vm::setQuery,
                label           = { Text("Rezepte durchsuchen") },
                leadingIcon     = { Icon(Icons.Default.Search, null) },
                modifier        = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine      = true,
                shape           = RoundedCornerShape(12.dp)
            )

            if (state.recipes.isEmpty()) {
                EmptyState(
                    icon    = { Icon(Icons.Default.MenuBook, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    message = "Noch keine Rezepte gespeichert",
                    sub     = "Tippe auf + und füge einen Instagram-, TikTok- oder Webseiten-Link ein"
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(state.recipes, key = { it.id }) { recipe ->
                        RecipeCard(recipe,
                            onClick   = { selectedRecipe = recipe },
                            onDelete  = { vm.deleteRecipe(recipe) })
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ImportSheet(
            prefillUrl = sharedUrl ?: "",
            isLoading  = state.isImporting,
            error      = state.importError,
            onImport   = { url -> vm.importFromUrl(url) },
            onDismiss  = { showImportSheet = false; vm.clearError() }
        )
    }

    state.lastImport?.let { recipe ->
        AlertDialog(
            onDismissRequest = vm::clearLastImport,
            icon    = { Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
            title   = { Text("Rezept importiert!") },
            text    = { Text(recipe.title) },
            confirmButton = { TextButton(onClick = { selectedRecipe = recipe; vm.clearLastImport() }) { Text("Ansehen") } },
            dismissButton = { TextButton(onClick = vm::clearLastImport) { Text("OK") } }
        )
    }

    selectedRecipe?.let { recipe ->
        RecipeDetailSheet(recipe = recipe, onDismiss = { selectedRecipe = null })
    }
}

@Composable
private fun RecipeCard(recipe: Recipe, onClick: () -> Unit, onDelete: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            recipe.imageUrl?.let { url ->
                AsyncImage(
                    model             = url,
                    contentDescription = recipe.title,
                    modifier          = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale      = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                if (recipe.platform != null) {
                    Spacer(Modifier.height(4.dp))
                    PlatformChip(recipe.platform)
                }
                recipe.prepTimeMinutes?.let {
                    Text("⏱ $it min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { showConfirm = true }) {
                Icon(Icons.Default.DeleteOutline, "Löschen", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Rezept löschen?") },
            text  = { Text(recipe.title) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showConfirm = false }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Abbrechen") } }
        )
    }
}

@Composable
private fun PlatformChip(platform: String) {
    val (icon, label) = when (platform.lowercase()) {
        "instagram" -> Icons.Default.CameraAlt to "Instagram"
        "tiktok"    -> Icons.Default.VideoLibrary to "TikTok"
        "youtube"   -> Icons.Default.PlayCircle to "YouTube"
        else        -> Icons.Default.Language to "Web"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
    }
}

// ── Import Bottom Sheet (2-step: URL → falls IG blockiert: Caption manuell) ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    prefillUrl: String,
    isLoading:  Boolean,
    error:      String?,
    onImport:   (String) -> Unit,
    onDismiss:  () -> Unit
) {
    val context = LocalContext.current
    var url     by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    // Step 2: manual caption entry (shown when Instagram is detected + blocked)
    var showManualCaption by remember { mutableStateOf(false) }
    var manualTitle       by remember { mutableStateOf("") }
    var manualCaption     by remember { mutableStateOf("") }
    val vm: RecipesViewModel = viewModel()

    val isInstagram = "instagram.com" in url || "instagr.am" in url

    // If error appears AND it's Instagram → offer caption fallback
    LaunchedEffect(error) {
        if (error != null && isInstagram) showManualCaption = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            if (!showManualCaption) {
                // ── Step 1: URL eingeben ──────────────────────────────────────
                Text("Rezept importieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Instagram-, TikTok- oder Webseiten-Link einfügen.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("URL") },
                    leadingIcon   = { Icon(Icons.Default.Link, null) },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    isError       = error != null && !showManualCaption
                )
                if (error != null && !isInstagram) {
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }

                // Instagram hint
                if (isInstagram) {
                    Spacer(Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape  = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, null, Modifier.size(16.dp).padding(top = 2.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Instagram blockiert externe Zugriffe. Falls der Import fehlschlägt, " +
                                "kannst du die Caption direkt einfügen.",
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick  = { onImport(url.trim()) },
                    enabled  = url.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isLoading) "Importiere…" else "Importieren")
                }

                // Manual caption fallback button (always visible for Instagram)
                if (isInstagram) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = { showManualCaption = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Caption manuell einfügen")
                    }
                }

            } else {
                // ── Step 2: Caption manuell einfügen ─────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showManualCaption = false }) {
                        Icon(Icons.Default.ArrowBack, "Zurück")
                    }
                    Text("Caption einfügen", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))

                // Open Instagram button
                OutlinedButton(
                    onClick  = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.trim())).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Instagram öffnen & Caption kopieren")
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text(
                    "1. Öffne den Post oben\n" +
                    "2. Tippe auf ··· → \"Kopieren\"\n" +
                    "3. Füge die Caption unten ein",
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value         = manualTitle,
                    onValueChange = { manualTitle = it },
                    label         = { Text("Titel (optional)") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = manualCaption,
                    onValueChange = { manualCaption = it },
                    label         = { Text("Caption / Rezepttext hier einfügen") },
                    modifier      = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    maxLines      = 12
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (manualCaption.isNotBlank()) {
                            vm.saveManualRecipe(
                                url     = url.trim(),
                                title   = manualTitle.trim().ifBlank { null },
                                caption = manualCaption.trim()
                            )
                            onDismiss()
                        }
                    },
                    enabled  = manualCaption.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rezept speichern")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Recipe Detail ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailSheet(recipe: Recipe, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.92f)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            recipe.imageUrl?.let { url ->
                item {
                    AsyncImage(
                        model = url, contentDescription = recipe.title,
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Text(recipe.title, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    recipe.prepTimeMinutes?.let {
                        Text("⏱ $it min", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("👥 ${recipe.servings} Portionen", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    recipe.platform?.let {
                        Text("📌 $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (recipe.description.isNotBlank()) {
                item {
                    Text("Beschreibung", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(recipe.description, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (recipe.ingredients.isNotBlank()) {
                item {
                    Text("Zutaten", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(recipe.ingredients, fontSize = 14.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(16.dp))
                }
            }
            if (recipe.instructions.isNotBlank()) {
                item {
                    Text("Zubereitung", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(recipe.instructions, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
            recipe.sourceUrl?.let { link ->
                item {
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(
                        onClick  = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Original-Link öffnen")
                    }
                }
            }
        }
    }
}
