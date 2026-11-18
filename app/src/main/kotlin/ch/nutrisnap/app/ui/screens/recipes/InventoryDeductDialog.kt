package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.domain.InventoryMatcher

private fun fmtQty(v: Float): String =
    if (v >= 10f || v == v.toInt().toFloat()) v.toInt().toString()
    else String.format(java.util.Locale.GERMAN, "%.1f", v)

/**
 * «Vorrat aktualisieren» nach dem Kochen. Sichere Mengen (g/ml/Stück) sind vorausgewählt,
 * Packungen/Dosen nur als Vorschlag (-1) und nicht vorausgewählt.
 */
@Composable
fun InventoryDeductDialog(
    deductions: List<InventoryMatcher.Deduction>,
    onApply: (List<InventoryMatcher.Deduction>) -> Unit,
    onSkip: () -> Unit
) {
    val selected = remember(deductions) {
        mutableStateMapOf<Long, Boolean>().apply {
            deductions.forEach { put(it.inventoryId, it.measurable) }
        }
    }
    val chosen = deductions.filter { selected[it.inventoryId] == true }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Vorrat aktualisieren?") },
        text = {
            Column {
                Text(
                    "Diese Zutaten hast du im Vorrat. Gekochte Mengen abbuchen:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(deductions, key = { it.inventoryId }) { d ->
                        val on = selected[d.inventoryId] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected[d.inventoryId] = !on }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = on, onCheckedChange = { selected[d.inventoryId] = it })
                            Column {
                                Text(d.inventoryName, fontSize = 15.sp)
                                val change = if (d.fullyUsed) {
                                    "${fmtQty(d.currentQty)} ${d.unit} → aufgebraucht (wird entfernt)"
                                } else {
                                    "${fmtQty(d.currentQty)} ${d.unit} → ${fmtQty(d.newQty)} ${d.unit}"
                                }
                                Text(
                                    change + if (!d.measurable) " (Menge unklar)" else "",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(chosen) }, enabled = chosen.isNotEmpty()) {
                Text("Abbuchen (${chosen.size})")
            }
        },
        dismissButton = { TextButton(onClick = onSkip) { Text("Überspringen") } }
    )
}
