package ch.nutrisnap.app.ui.screens.customfood

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.CustomFoodItem
import ch.nutrisnap.app.ui.theme.NutriSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomFoodListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    vm: CustomFoodViewModel = viewModel()
) {
    val foods by vm.foods.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val verifiedFilter by vm.verifiedFilter.collectAsStateWithLifecycle()
    val sourceFilter by vm.sourceFilter.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CustomFoodItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lebensmittel-DB") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, "Hinzufügen")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, "Neues Lebensmittel")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
                placeholder = { Text("Suchen…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = verifiedFilter == null && sourceFilter == null,
                    onClick = {
                        vm.setVerifiedFilter(null)
                        vm.setSourceFilter(null)
                    },
                    label = { Text("Alle") }
                )
                FilterChip(
                    selected = verifiedFilter == false,
                    onClick = {
                        vm.setVerifiedFilter(if (verifiedFilter == false) null else false)
                    },
                    label = { Text("Zu prüfen") }
                )
                FilterChip(
                    selected = verifiedFilter == true,
                    onClick = {
                        vm.setVerifiedFilter(if (verifiedFilter == true) null else true)
                    },
                    label = { Text("Verifiziert") }
                )
                FilterChip(
                    selected = sourceFilter == "yazio_import",
                    onClick = {
                        vm.setSourceFilter(
                            if (sourceFilter == "yazio_import") null else "yazio_import"
                        )
                    },
                    label = { Text("Yazio") }
                )
                FilterChip(
                    selected = sourceFilter == "manual",
                    onClick = {
                        vm.setSourceFilter(if (sourceFilter == "manual") null else "manual")
                    },
                    label = { Text("Manuell") }
                )
                FilterChip(
                    selected = sourceFilter == "label_scan",
                    onClick = {
                        vm.setSourceFilter(
                            if (sourceFilter == "label_scan") null else "label_scan"
                        )
                    },
                    label = { Text("Label-Scan") }
                )
                FilterChip(
                    selected = sourceFilter == "yazio_diary_only",
                    onClick = {
                        vm.setSourceFilter(
                            if (sourceFilter == "yazio_diary_only") null else "yazio_diary_only"
                        )
                    },
                    label = { Text("Tagebuch") }
                )
            }

            Text(
                "${foods.size} Einträge · nur eigene / Yazio / Scan",
                modifier = Modifier.padding(horizontal = NutriSpacing.lg, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (foods.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isNotBlank() || verifiedFilter != null || sourceFilter != null)
                            "Keine Treffer"
                        else
                            "Noch keine eigenen Lebensmittel.\nTippe + zum Hinzufügen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = NutriSpacing.lg,
                        vertical = NutriSpacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(foods, key = { it.id }) { item ->
                        CustomFoodRow(
                            item = item,
                            onClick = { onEdit(item.id) },
                            onToggleVerified = { vm.setVerified(item, !item.verified) },
                            onDelete = { pendingDelete = item }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Löschen?") },
            text = {
                Text("„${item.name}“ wird aus der lokalen Lebensmittel-DB entfernt.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(item)
                        pendingDelete = null
                    }
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun CustomFoodRow(
    item: CustomFoodItem,
    onClick: () -> Unit,
    onToggleVerified: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleVerified) {
                Icon(
                    imageVector = if (item.verified)
                        Icons.Default.CheckCircle
                    else
                        Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (item.verified) "Verifiziert" else "Nicht verifiziert",
                    tint = if (item.verified)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.name,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val brandPart = item.brand?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: ""
                Text(
                    "${brandPart}${sourceLabel(item.source)} · " +
                        "${item.calories.toInt()} kcal · P ${fmt(item.protein)} · " +
                        "KH ${fmt(item.carbs)} · F ${fmt(item.fat)} / 100 g",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Löschen",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun sourceLabel(source: String): String = when (source) {
    "yazio_import" -> "Yazio"
    "yazio_recipe_ingredient" -> "Yazio-Zutat"
    "yazio_diary_only" -> "Yazio-Tagebuch"
    "label_scan" -> "Label-Scan"
    "manual" -> "Manuell"
    else -> source
}

private fun fmt(v: Float): String =
    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
