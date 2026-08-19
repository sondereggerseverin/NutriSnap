package ch.nutrisnap.app.ui.screens.recipes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.FrozenMeal
import ch.nutrisnap.app.data.model.MealType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FreezerScreen(vm: FreezerViewModel = viewModel()) {
    val meals by vm.meals.collectAsStateWithLifecycle()
    var thawTarget by remember { mutableStateOf<FrozenMeal?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AcUnit, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Gefrierschrank", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    if (meals.isEmpty()) "Noch nichts eingefroren"
                    else "${meals.sumOf { it.quantity }} Packungen · ${meals.size} Menüs",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (meals.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.AcUnit,
                        null,
                        Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Noch leer",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Beim Rezept „Einfrieren“ wählen\noder aus dem Tracking heraus speichern.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(meals, key = { it.id }) { meal ->
                    FrozenMealCard(
                        meal = meal,
                        onThaw = { thawTarget = meal },
                        onDelete = { vm.delete(meal) },
                        onQuantityChange = { q -> vm.adjustQuantity(meal, q) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    thawTarget?.let { meal ->
        ThawDialog(
            meal = meal,
            onConfirm = { mealType, track ->
                vm.thawAndTrack(meal, mealType, LocalDate.now(), track)
                thawTarget = null
            },
            onDismiss = { thawTarget = null }
        )
    }
}

@Composable
private fun FrozenMealCard(
    meal: FrozenMeal,
    onThaw: () -> Unit,
    onDelete: () -> Unit,
    onQuantityChange: (Int) -> Unit
) {
    val lines = meal.portionLines()
    val dateStr = remember(meal.frozenAt) {
        Instant.ofEpochMilli(meal.frozenAt)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(meal.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Eingefroren $dateStr · ${meal.quantity}×",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        "${meal.quantity}×",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            lines.forEach { line ->
                Text(
                    "• ${line.name}: ${line.grams.toInt()} g · ${line.calories.toInt()} kcal",
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Portion gesamt: ${meal.totalGramsPerPortion().toInt()} g · " +
                    "${meal.totalCaloriesPerPortion().toInt()} kcal · " +
                    "${meal.totalProteinPerPortion().toInt()} g Protein",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            if (meal.notes.isNotBlank()) {
                Text(meal.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { if (meal.quantity > 1) onQuantityChange(meal.quantity - 1) },
                    enabled = meal.quantity > 1,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("−") }
                OutlinedButton(
                    onClick = { onQuantityChange(meal.quantity + 1) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp)
                ) { Text("+") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Löschen", tint = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onThaw) {
                    Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Essen")
                }
            }
        }
    }
}

@Composable
private fun ThawDialog(
    meal: FrozenMeal,
    onConfirm: (MealType, track: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var mealType by remember { mutableStateOf(MealType.LUNCH) }
    var track by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Herausnehmen") },
        text = {
            Column {
                Text(
                    "1× ${meal.name} (${meal.totalCaloriesPerPortion().toInt()} kcal)",
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(12.dp))
                Text("Mahlzeit", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        MealType.BREAKFAST to "Frühstück",
                        MealType.LUNCH to "Mittag",
                        MealType.DINNER to "Abend",
                        MealType.SNACK to "Snack"
                    ).forEach { (mt, label) ->
                        FilterChip(
                            selected = mealType == mt,
                            onClick = { mealType = mt },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = track, onCheckedChange = { track = it })
                    Text("Ins Tagebuch buchen", fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(mealType, track) }) {
                Text(if (track) "Essen & tracken" else "Nur herausnehmen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

/**
 * Dialog: X Packungen mit definierten Portionen einfrieren
 * (aus Rezept-Tracking oder manuell).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreezeMealSheet(
    defaultName: String,
    /** Vorschläge: Name → Gramm (editierbar) + zugehörige kcal/macros pro Zeile */
    initialLines: List<FreezeLineDraft>,
    defaultQuantity: Int = 1,
    onConfirm: (name: String, lines: List<FreezeLineDraft>, quantity: Int, notes: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var quantityText by remember { mutableStateOf(defaultQuantity.toString()) }
    var notes by remember { mutableStateOf("") }
    var lines by remember {
        mutableStateOf(initialLines.map { it.copy() })
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AcUnit, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Einfrieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Menü-Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = quantityText,
                onValueChange = { quantityText = it.filter { c -> c.isDigit() } },
                label = { Text("Anzahl Packungen") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("Inhalt einer Portion", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            lines.forEachIndexed { idx, line ->
                OutlinedTextField(
                    value = line.gramsText,
                    onValueChange = { v ->
                        lines = lines.toMutableList().also {
                            it[idx] = line.copy(gramsText = v)
                        }
                    },
                    label = { Text("${line.name} (g)") },
                    supportingText = {
                        val g = line.gramsText.replace(',', '.').toFloatOrNull()
                        if (g != null && line.refGrams > 0f) {
                            val f = g / line.refGrams
                            Text("≈ ${(line.refCalories * f).toInt()} kcal")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notiz (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val q = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val resolved = lines.mapNotNull { line ->
                        val g = line.gramsText.replace(',', '.').toFloatOrNull()?.takeIf { it >= 1f }
                            ?: return@mapNotNull null
                        line.copy(gramsText = g.toString())
                    }
                    if (resolved.isNotEmpty()) onConfirm(name, resolved, q, notes)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = quantityText.toIntOrNull()?.let { it > 0 } == true
            ) {
                Icon(Icons.Default.AcUnit, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Einfrieren")
            }
        }
    }
}

/** Entwurf einer Portionslinie für den Freeze-Dialog. */
data class FreezeLineDraft(
    val name: String,
    val gramsText: String,
    val refGrams: Float,
    val refCalories: Float,
    val refProtein: Float = 0f,
    val refCarbs: Float = 0f,
    val refFat: Float = 0f,
    val refFiber: Float = 0f
) {
    fun toPortionLine(): ch.nutrisnap.app.data.model.FrozenPortionLine {
        val g = gramsText.replace(',', '.').toFloatOrNull() ?: refGrams
        val f = if (refGrams > 0f) g / refGrams else 1f
        return ch.nutrisnap.app.data.model.FrozenPortionLine(
            name = name,
            grams = g,
            calories = refCalories * f,
            protein = refProtein * f,
            carbs = refCarbs * f,
            fat = refFat * f,
            fiber = refFiber * f
        )
    }
}
