package ch.nutrisnap.app.ui.screens.mealtemplate

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.MealTemplate
import ch.nutrisnap.app.data.model.MealTemplateItem
import ch.nutrisnap.app.data.model.MealType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealTemplateScreen(
    onBack: () -> Unit,
    onTemplateSelected: (List<MealTemplateItem>) -> Unit,
    vm: MealTemplateViewModel = viewModel()
) {
    val templates by vm.templates.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<MealTemplate?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mahlzeit-Vorlagen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Default.Add, "Neue Vorlage")
            }
        }
    ) { padding ->
        if (templates.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.RestaurantMenu, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Noch keine Vorlagen", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tippe auf + um eine Vorlage zu erstellen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(templates, key = { it.id }) { template ->
                    TemplateCard(
                        template = template,
                        onUse = {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                val items: List<MealTemplateItem> = vm.getItems(template.id)
                                onTemplateSelected(items)
                            }
                        },
                        onDelete = { toDelete = template }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreateTemplateDialog(
            onDismiss = { showCreate = false },
            onSave = { name: String, mealType: MealType ->
                vm.saveTemplate(name, mealType, emptyList())
                showCreate = false
            }
        )
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Vorlage löschen?") },
            text = { Text(t.name) },
            confirmButton = {
                TextButton(onClick = { vm.delete(t); toDelete = null }) {
                    Text("Löschen", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun TemplateCard(
    template: MealTemplate,
    onUse: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(template.name, fontWeight = FontWeight.SemiBold)
                Text(
                    template.mealType.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onUse) { Text("Verwenden") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, "Löschen",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateTemplateDialog(
    onDismiss: () -> Unit,
    onSave: (String, MealType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var mealType by remember { mutableStateOf(MealType.LUNCH) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Vorlage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name der Vorlage") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = mealType.label(),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Mahlzeit-Typ") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        MealType.values().forEach { mt ->
                            DropdownMenuItem(
                                text = { Text(mt.label()) },
                                onClick = { mealType = mt; expanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name, mealType) }) {
                Text("Erstellen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

private fun MealType.label(): String = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snack"
}
