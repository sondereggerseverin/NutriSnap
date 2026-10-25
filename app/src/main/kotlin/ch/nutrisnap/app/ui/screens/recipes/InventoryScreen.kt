package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.InventoryItem

private val UNIT_OPTIONS = listOf("Stk", "g", "ml", "Packung", "Dose", "Glas", "Beutel", "kg", "l")
private val LOCATION_OPTIONS = listOf("", "Kühlschrank", "Vorratsschrank", "Gefrierfach", "Keller")

@Composable
fun InventoryScreen(vm: InventoryViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var editTarget by remember { mutableStateOf<InventoryItem?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Kitchen, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Inventar", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    if (items.isEmpty()) "Noch leer"
                    else "${items.size} Einträge",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Hinzufügen")
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Kitchen,
                        null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Vorrat noch leer",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Zutaten und Packungen erfassen,\ndie du zu Hause hast.\n(Ohne Ablaufdatum)",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    InventoryRow(
                        item = item,
                        onEdit = { editTarget = item },
                        onDelete = { vm.delete(item) }
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }

    if (showAdd) {
        InventoryEditDialog(
            title = "Eintrag hinzufügen",
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { name, qty, unit, notes, location ->
                vm.upsert(name = name, quantity = qty, unit = unit, notes = notes, location = location)
                showAdd = false
            }
        )
    }

    editTarget?.let { target ->
        InventoryEditDialog(
            title = "Eintrag bearbeiten",
            initial = target,
            onDismiss = { editTarget = null },
            onSave = { name, qty, unit, notes, location ->
                vm.upsert(
                    id = target.id,
                    name = name,
                    quantity = qty,
                    unit = unit,
                    notes = notes,
                    location = location,
                    barcode = target.barcode,
                    foodItemId = target.foodItemId
                )
                editTarget = null
            }
        )
    }
}

@Composable
private fun InventoryRow(
    item: InventoryItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val qtyText = if (item.quantity % 1f == 0f) {
                    item.quantity.toInt().toString()
                } else {
                    String.format("%.1f", item.quantity)
                }
                Text(
                    "$qtyText ${item.unit}" +
                        if (item.location.isNotBlank()) " · ${item.location}" else "",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.notes.isNotBlank()) {
                    Text(
                        item.notes,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2
                    )
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Bearbeiten")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InventoryEditDialog(
    title: String,
    initial: InventoryItem?,
    onDismiss: () -> Unit,
    onSave: (name: String, qty: Float, unit: String, notes: String, location: String) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var qtyText by remember {
        mutableStateOf(
            initial?.quantity?.let {
                if (it % 1f == 0f) it.toInt().toString() else String.format("%.1f", it)
            } ?: "1"
        )
    }
    var unit by remember { mutableStateOf(initial?.unit ?: "Stk") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var location by remember { mutableStateOf(initial?.location ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        label = { Text("Menge") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f)
                    )
                    // Einheit als einfaches Dropdown-ähnliches Menü
                    var unitExpanded by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = unit,
                            onValueChange = { unit = it },
                            label = { Text("Einheit") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            readOnly = false
                        )
                        // Chips darunter
                    }
                }
                // Einheiten-Chips
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    UNIT_OPTIONS.take(5).forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u, fontSize = 12.sp) }
                        )
                    }
                }
                // Ort
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LOCATION_OPTIONS.filter { it.isNotBlank() }.forEach { loc ->
                        FilterChip(
                            selected = location == loc,
                            onClick = { location = if (location == loc) "" else loc },
                            label = { Text(loc, fontSize = 11.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notizen (optional)") },
                    singleLine = false,
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val qty = qtyText.replace(',', '.').toFloatOrNull() ?: 1f
                    if (name.isNotBlank()) onSave(name.trim(), qty, unit.ifBlank { "Stk" }, notes, location)
                },
                enabled = name.isNotBlank()
            ) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
