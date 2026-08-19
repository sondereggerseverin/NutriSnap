package ch.nutrisnap.app.ui.screens.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import ch.nutrisnap.app.ui.theme.NutriRadius
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.MealTemplate
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.ui.theme.NutriSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryScheduleSheet(
    entry: DiaryEntry,
    currentDate: java.time.LocalDate,
    onDismiss: () -> Unit,
    onMove: (java.time.LocalDate, MealType) -> Unit,
    onCopyDays: (dayCount: Int, start: java.time.LocalDate, meal: MealType?, includeStart: Boolean) -> Unit
) {
    // move = verschieben | copy = 1× kopieren | mealprep = N Tage (gleiche Portionen)
    var mode by remember { mutableStateOf("move") }
    val today = java.time.LocalDate.now()
    var selectedDate by remember {
        mutableStateOf(
            runCatching { java.time.LocalDate.parse(entry.dateStr) }.getOrDefault(currentDate)
        )
    }
    var selectedMeal by remember { mutableStateOf(entry.mealType) }
    var dayCount by remember { mutableIntStateOf(5) }

    fun applyQuick(date: java.time.LocalDate, meal: MealType) {
        when (mode) {
            "move" -> onMove(date, meal)
            "copy" -> onCopyDays(1, date, meal, true)
            else -> onCopyDays(dayCount, date, meal, true) // mealprep
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp)
        ) {
            Text("Verschieben / kopieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(entry.foodName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "move", onClick = { mode = "move" }, label = { Text("Verschieben") })
                FilterChip(selected = mode == "copy", onClick = { mode = "copy" }, label = { Text("Kopieren") })
                FilterChip(selected = mode == "mealprep", onClick = { mode = "mealprep" }, label = { Text("Meal-Prep") })
            }
            Text(
                when (mode) {
                    "move" -> "Eintrag an einen anderen Tag/Mahlzeit verschieben"
                    "copy" -> "Einmalig an einen anderen Tag/Mahlzeit kopieren"
                    else -> "Gleiche Portion auf mehrere Tage verteilen (z. B. 5 Portionen vorgekocht)"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(12.dp))
            Text("Schnell", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(1), MealType.DINNER) },
                    modifier = Modifier.weight(1f)
                ) { Text("Gestern Abend", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(1), MealType.LUNCH) },
                    modifier = Modifier.weight(1f)
                ) { Text("Gestern Mittag", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(2), MealType.DINNER) },
                    modifier = Modifier.weight(1f)
                ) { Text("Vorgestern Abend", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = { applyQuick(today.minusDays(2), MealType.LUNCH) },
                    modifier = Modifier.weight(1f)
                ) { Text("Vorgestern Mittag", fontSize = 12.sp) }
            }

            Spacer(Modifier.height(12.dp))
            Text("Tag", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    today.minusDays(2) to "Vorgestern",
                    today.minusDays(1) to "Gestern",
                    today to "Heute",
                    today.plusDays(1) to "Morgen"
                ).forEach { (d, label) ->
                    FilterChip(
                        selected = selectedDate == d,
                        onClick = { selectedDate = d },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Mahlzeit", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MealType.entries.forEach { m ->
                    FilterChip(
                        selected = selectedMeal == m,
                        onClick = { selectedMeal = m },
                        label = { Text(m.label(), fontSize = 11.sp) }
                    )
                }
            }

            if (mode == "mealprep") {
                Spacer(Modifier.height(12.dp))
                Text("Anzahl Tage (Meal-Prep)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    listOf(5, 7).forEach { n ->
                        FilterChip(
                            selected = dayCount == n,
                            onClick = { dayCount = n },
                            label = { Text("$n Tage") }
                        )
                    }
                    IconButton(onClick = { dayCount = (dayCount - 1).coerceAtLeast(2) }) {
                        Icon(Icons.Default.Remove, null)
                    }
                    Text("$dayCount", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = { dayCount = (dayCount + 1).coerceAtMost(14) }) {
                        Icon(Icons.Default.Add, null)
                    }
                }
                Text(
                    "Kopiert ab ${selectedDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))} auf $dayCount Tage · ${selectedMeal.label()}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    when (mode) {
                        "move" -> onMove(selectedDate, selectedMeal)
                        "copy" -> onCopyDays(1, selectedDate, selectedMeal, true)
                        else -> onCopyDays(dayCount, selectedDate, selectedMeal, true)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when (mode) {
                        "move" -> "Verschieben"
                        "copy" -> "Kopieren"
                        else -> "Auf $dayCount Tage kopieren"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MealCopySheet(
    meal: MealType,
    sourceDate: java.time.LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (dayCount: Int) -> Unit
) {
    var dayCount by remember { mutableIntStateOf(5) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp)
        ) {
            Text("Mahlzeit kopieren", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "${meal.label()} · ab ${sourceDate.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM."))} auf Folgetage",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(5, 7).forEach { n ->
                    FilterChip(selected = dayCount == n, onClick = { dayCount = n }, label = { Text("$n Tage") })
                }
                IconButton(onClick = { dayCount = (dayCount - 1).coerceAtLeast(2) }) {
                    Icon(Icons.Default.Remove, null)
                }
                Text("$dayCount", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(onClick = { dayCount = (dayCount + 1).coerceAtMost(14) }) {
                    Icon(Icons.Default.Add, null)
                }
            }
            Text(
                "Alle Einträge dieser Mahlzeit werden auf die nächsten ${dayCount - 1} Tage kopiert (ohne heute nochmal).",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onConfirm(dayCount) }, modifier = Modifier.fillMaxWidth()) {
                Text("Auf $dayCount Tage kopieren")
            }
        }
    }
}

@Composable
internal fun AutopilotBanner(
    templates: List<ch.nutrisnap.app.data.model.MealTemplate>,
    onApply: (ch.nutrisnap.app.data.model.MealTemplate) -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = NutriSpacing.lg, vertical = NutriSpacing.sm),
        shape = RoundedCornerShape(NutriRadius.lg),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(Modifier.padding(NutriSpacing.md)) {
            Text("Wochen-Autopilot", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Mo–Fr · Vorlagen für leere Mahlzeiten",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            templates.forEach { t ->
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        Text(t.mealType.label(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilledTonalButton(onClick = { onApply(t) }) {
                        Text("Übernehmen", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
