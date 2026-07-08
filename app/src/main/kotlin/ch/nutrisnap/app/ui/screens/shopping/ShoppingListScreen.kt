package ch.nutrisnap.app.ui.screens.shopping

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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.ShoppingListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingListScreen(onBack: () -> Unit, vm: ShoppingListViewModel = viewModel()) {
    val items by vm.items.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val open = items.filter { !it.checked }
    val done = items.filter { it.checked }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einkaufsliste") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Zurück") } },
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Mehr") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Erledigte entfernen") },
                                onClick = { vm.clearChecked(); menuExpanded = false },
                                enabled = done.isNotEmpty()
                            )
                            DropdownMenuItem(
                                text = { Text("Liste leeren") },
                                onClick = { vm.clearAll(); menuExpanded = false },
                                enabled = items.isNotEmpty()
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Eintrag hinzufügen") }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ShoppingCart, null, Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Einkaufsliste ist leer", style = MaterialTheme.typography.titleMedium)
                    Text("Tippe auf + oder füge Zutaten aus einem Rezept hinzu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Spacer(Modifier.height(8.dp)) }
                // Nach Rezept gruppieren, manuelle Einträge zuerst
                val groups = open.groupBy { it.recipeTitle }
                groups[null]?.let { manual ->
                    items(manual, key = { it.id }) { ShoppingRow(it, onToggle = { vm.toggle(it) }, onDelete = { vm.delete(it) }) }
                }
                groups.filterKeys { it != null }.forEach { (recipe, recipeItems) ->
                    item(key = "header_$recipe") {
                        Text(recipe ?: "", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(recipeItems, key = { it.id }) { ShoppingRow(it, onToggle = { vm.toggle(it) }, onDelete = { vm.delete(it) }) }
                }
                if (done.isNotEmpty()) {
                    item(key = "done_header") {
                        Text("Erledigt (${done.size})", fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    }
                    items(done, key = { it.id }) { ShoppingRow(it, onToggle = { vm.toggle(it) }, onDelete = { vm.delete(it) }) }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAdd) {
        AddShoppingItemDialog(
            onDismiss = { showAdd = false },
            onSave = { name, amount, unit -> vm.addItem(name, amount, unit); showAdd = false }
        )
    }
}

@Composable
private fun ShoppingRow(item: ShoppingListItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = item.checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            if (item.amount != null) {
                val amountText = if (item.amount == item.amount.toInt().toFloat()) item.amount.toInt().toString() else item.amount.toString()
                Text("$amountText ${item.unit ?: ""}".trim(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Close, "Entfernen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddShoppingItemDialog(onDismiss: () -> Unit, onSave: (String, Float?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zur Einkaufsliste hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Was?") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Menge") },
                        modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Einheit") },
                        modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, amount.toFloatOrNull(), unit.ifBlank { null }) },
                enabled = name.isNotBlank()
            ) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
