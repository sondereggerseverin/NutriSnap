package ch.nutrisnap.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.datastore.preferences.core.edit
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.model.parseMealOrder
import ch.nutrisnap.app.ui.theme.KEY_MEAL_ORDER
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun MealType.label() = when (this) {
    MealType.BREAKFAST -> "Frühstück"
    MealType.LUNCH     -> "Mittagessen"
    MealType.DINNER    -> "Abendessen"
    MealType.SNACK     -> "Snacks"
}

private fun MealType.icon() = when (this) {
    MealType.BREAKFAST -> "☀️"
    MealType.LUNCH     -> "🌤️"
    MealType.DINNER    -> "🌙"
    MealType.SNACK     -> "🍎"
}

/** Reihenfolge der Mahlzeiten-Sektionen per Drag-Handle anpassen (wirkt sich auf Home + Diary aus). */
@Composable
fun MealOrderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { 64.dp.toPx() }

    val storedOrder by context.notifDataStore.data.collectAsState(initial = null)

    val items = remember { mutableStateListOf<MealType>() }
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(storedOrder) {
        if (!initialized) {
            items.clear()
            items.addAll(parseMealOrder(storedOrder?.get(KEY_MEAL_ORDER)))
            initialized = true
        }
    }

    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY   by remember { mutableStateOf(0f) }

    fun persist() {
        scope.launch {
            context.notifDataStore.edit { p -> p[KEY_MEAL_ORDER] = items.joinToString(",") { it.name } }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Spacer(Modifier.width(4.dp))
            Text("Mahlzeiten-Reihenfolge", fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Text(
            "Ziehe am Handle, um die Reihenfolge auf Start & im Tagebuch anzupassen.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))

        Column(Modifier.padding(horizontal = 16.dp)) {
            items.forEachIndexed { index, meal ->
                val isDragged = index == draggedIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .zIndex(if (isDragged) 1f else 0f)
                        .offset { IntOffset(0, if (isDragged) dragOffsetY.roundToInt() else 0) }
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDragged) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(meal.icon(), fontSize = 18.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(meal.label(), fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Verschieben",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.pointerInput(index) {
                            detectDragGestures(
                                onDragStart = { draggedIndex = index; dragOffsetY = 0f },
                                onDragEnd   = { draggedIndex = null; dragOffsetY = 0f; persist() },
                                onDragCancel = { draggedIndex = null; dragOffsetY = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val current = draggedIndex ?: return@detectDragGestures
                                    dragOffsetY += dragAmount.y
                                    if (dragOffsetY > rowHeightPx / 2 && current < items.lastIndex) {
                                        items.add(current + 1, items.removeAt(current))
                                        draggedIndex = current + 1
                                        dragOffsetY -= rowHeightPx
                                    } else if (dragOffsetY < -rowHeightPx / 2 && current > 0) {
                                        items.add(current - 1, items.removeAt(current))
                                        draggedIndex = current - 1
                                        dragOffsetY += rowHeightPx
                                    }
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}
