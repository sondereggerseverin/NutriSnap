package ch.nutrisnap.app.ui.screens.supplements

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.Supplement
import ch.nutrisnap.app.data.model.SupplementCategory
import ch.nutrisnap.app.data.model.SupplementStatus
import ch.nutrisnap.app.data.model.SupplementTiming
import ch.nutrisnap.app.domain.SupplementRecommendation
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupplementsScreen(
    vm: SupplementsViewModel = viewModel(),
    onNavigateBack: () -> Unit = {}
) {
    val list by vm.supplements.collectAsState()
    val plan by vm.dailyPlan.collectAsState()
    var selected by remember { mutableStateOf<Supplement?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Supplement?>(null) }

    if (selected != null) {
        SupplementDetailScreen(
            item = selected!!,
            onBack = { selected = null },
            onDelete = {
                pendingDelete = selected
                selected = null
            },
            onMarkEmpty = {
                vm.update(selected!!.copy(status = SupplementStatus.LEER))
                selected = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Supplements") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Hinzufügen")
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = NutriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(NutriSpacing.sm),
            contentPadding = PaddingValues(bottom = 88.dp, top = NutriSpacing.sm)
        ) {
            item {
                Text("Heute empfohlen", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
            }
            item {
                RecommendationBlock("Morgens", plan.morning)
            }
            item {
                RecommendationBlock("Abends", plan.evening)
            }
            if (plan.onDemandHints.isNotEmpty()) {
                item {
                    Text(
                        "Nur bei Bedarf / ärztlich",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    plan.onDemandHints.forEach { s ->
                        Text(
                            "· ${s.name}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text("Dein Bestand (${list.size})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            items(list, key = { it.id }) { s ->
                SupplementCard(
                    item = s,
                    onClick = { selected = s },
                    onDelete = { pendingDelete = s }
                )
            }
        }
    }

    if (showAdd) {
        AddSupplementDialog(
            onDismiss = { showAdd = false },
            onSave = { item ->
                vm.add(item)
                showAdd = false
            }
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Löschen?") },
            text = { Text("„${item.name}“ wirklich aus dem Bestand entfernen?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(item)
                    pendingDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun RecommendationBlock(title: String, items: List<SupplementRecommendation>) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
    ) {
        Column(Modifier.padding(NutriSpacing.md)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (items.isEmpty()) {
                Text("Keine Empfehlung", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                items.forEach { rec ->
                    Spacer(Modifier.height(6.dp))
                    Text(rec.supplement.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        "${rec.supplement.servingSize.ifBlank { "laut Packung" }} · ${rec.reason}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SupplementCard(
    item: Supplement,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (item.status) {
        SupplementStatus.AKTIV -> MaterialTheme.colorScheme.primary
        SupplementStatus.LEER -> MaterialTheme.colorScheme.outline
        SupplementStatus.ABGELAUFEN -> MaterialTheme.colorScheme.error
        SupplementStatus.NICHT_ZUTREFFEND -> MaterialTheme.colorScheme.outline
    }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(NutriRadius.lg)
    ) {
        Row(
            Modifier.padding(NutriSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Medication, contentDescription = null, tint = statusColor)
            Spacer(Modifier.width(NutriSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                val sub = listOfNotNull(
                    item.brand.takeIf { it.isNotBlank() },
                    item.category.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                    item.status.name.lowercase()
                ).joinToString(" · ")
                Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.activeIngredients.isNotBlank()) {
                    Text(
                        item.activeIngredients.take(80) + if (item.activeIngredients.length > 80) "…" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen")
            }
        }
    }
}

@Composable
private fun SupplementDetailScreen(
    item: Supplement,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onMarkEmpty: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Löschen")
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
                .padding(NutriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (item.brand.isNotBlank()) Text(item.brand, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DetailSection("Wirkstoffe", item.activeIngredients)
            DetailSection("Portion", item.servingSize)
            DetailSection("Wirkung", item.effects)
            DetailSection("Vorteile", item.pros)
            DetailSection("Nachteile", item.cons)
            DetailSection("Verzehrempfehlung", item.dosageRecommendation)
            if (item.warnings.isNotBlank()) {
                Text("Warnhinweise", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Text(item.warnings, color = MaterialTheme.colorScheme.error)
            }
            item.expiryDateStr?.let { DetailSection("MHD", it) }
            DetailSection("Status", item.status.name)
            DetailSection("Timing", item.preferredTiming.name)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onMarkEmpty, modifier = Modifier.fillMaxWidth()) {
                Text("Als leer markieren")
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, body: String) {
    if (body.isBlank()) return
    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddSupplementDialog(
    onDismiss: () -> Unit,
    onSave: (Supplement) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var ingredients by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("") }
    var timing by remember { mutableStateOf(SupplementTiming.MORGENS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supplement hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(brand, { brand = it }, label = { Text("Marke") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ingredients, { ingredients = it }, label = { Text("Wirkstoffe") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dosage, { dosage = it }, label = { Text("Dosierung") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Timing", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SupplementTiming.MORGENS, SupplementTiming.ABENDS).forEach { t ->
                        FilterChip(
                            selected = timing == t,
                            onClick = { timing = t },
                            label = { Text(if (t == SupplementTiming.MORGENS) "Morgens" else "Abends") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        Supplement(
                            name = name.trim(),
                            brand = brand.trim(),
                            category = SupplementCategory.SONSTIGES,
                            activeIngredients = ingredients.trim(),
                            dosageRecommendation = dosage.trim(),
                            servingSize = dosage.trim(),
                            preferredTiming = timing,
                            status = SupplementStatus.AKTIV
                        )
                    )
                }
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
