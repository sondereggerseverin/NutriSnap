package ch.nutrisnap.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.model.SleepQuality
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel
import ch.nutrisnap.app.ui.viewmodel.WeeklyStats
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HealthConnectScreen(
    onRequestPermission: () -> Unit,
    viewModel: HealthConnectViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val adjustedGoal by viewModel.adjustedCalorieGoal.collectAsState()
    val weeklyStats by viewModel.weeklyStats.collectAsState()

    if (!uiState.isAvailable) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = null,
                    modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(16.dp))
                Text("Health Connect nicht verfügbar", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Bitte installiere Health Connect aus dem Play Store.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Aktivität & Gesundheit", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (uiState.hasPermission) "✅ Verbunden mit Samsung Health / Health Connect"
                        else "Nicht verbunden",
                        fontSize = 13.sp,
                        color = if (uiState.hasPermission) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    )
                }
                if (uiState.isHistoricalSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        uiState.syncError?.let { error ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        item {
            TodayOverviewCard(
                data = uiState.todayData,
                adjustedGoal = adjustedGoal,
                hasPermission = uiState.hasPermission,
                isLoading = uiState.isLoading,
                onConnectClick = onRequestPermission,
                onSyncClick = { viewModel.syncNow() }
            )
        }

        // Weekly averages - shown when data is available
        weeklyStats?.let { stats ->
            if (stats.daysWithWeight > 0 || stats.daysWithKcal > 0) {
                item { WeeklyAveragesCard(stats = stats) }
            }
        }

        if (uiState.weeklyData.isNotEmpty()) {
            item { WeeklyStepsCard(weeklyData = uiState.weeklyData) }
        }

        // Weight chart (only if we have weight data)
        val weightData = uiState.weeklyData.filter { it.weightKg != null }
        if (weightData.size >= 2) {
            item { WeightTrendCard(weeklyData = uiState.weeklyData) }
        }

        if (uiState.weeklyData.any { it.sleepMinutes > 0 }) {
            item { WeeklySleepCard(weeklyData = uiState.weeklyData) }
        }
    }
}

@Composable
private fun TodayOverviewCard(
    data: HealthConnectCache?,
    adjustedGoal: Int,
    hasPermission: Boolean,
    isLoading: Boolean,
    onConnectClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Heute", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                if (hasPermission) {
                    IconButton(onClick = onSyncClick, modifier = Modifier.size(32.dp)) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (!hasPermission) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = null,
                        modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Samsung Health verbinden", fontWeight = FontWeight.Medium)
                    Text("Für automatische Kalorienziel-Anpassung", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onConnectClick) { Text("Jetzt verbinden") }
                }
            } else {
                val activeKcal = data?.totalActivityCalories
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    BigStatItem(value = if (data != null) formatSteps(data.steps) else "–",
                        label = "Schritte", icon = "👟", color = Color(0xFF2196F3))
                    BigStatItem(value = activeKcal?.let { "$it" } ?: "–",
                        label = "Akt. kcal", icon = "🔥", color = Color(0xFFFF5722))
                    BigStatItem(
                        value = if (data?.weightKg != null) String.format("%.1f", data.weightKg) else "–",
                        label = "kg", icon = "⚖️", color = Color(0xFF9C27B0))
                    BigStatItem(
                        value = if (data != null && data.sleepMinutes > 0) "${data.sleepMinutes / 60}h" else "–",
                        label = "Schlaf", icon = data?.sleepQuality?.emoji ?: "😴",
                        color = Color(0xFF3F51B5))
                }

                if (activeKcal != null && activeKcal > 0) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TrendingUp, contentDescription = null,
                            tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text("Kalorienziel angepasst: $adjustedGoal kcal",
                                fontWeight = FontWeight.Medium, color = Color(0xFF4CAF50))
                            Text("Basis 2000 + $activeKcal aus Aktivität",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyAveragesCard(stats: WeeklyStats) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Ø Diese Woche", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {

                // Weight average + trend
                if (stats.avgWeightKg != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("⚖️", fontSize = 22.sp)
                        Text(
                            String.format("%.1f kg", stats.avgWeightKg),
                            fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = Color(0xFF9C27B0)
                        )
                        Text("Ø Gewicht", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (stats.weightTrend != null) {
                            val trendColor = when {
                                stats.weightTrend < -0.1 -> Color(0xFF4CAF50)  // lost weight = green
                                stats.weightTrend > 0.1  -> Color(0xFFEF5350)  // gained = red
                                else                      -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val trendIcon = when {
                                stats.weightTrend < -0.1 -> "↓"
                                stats.weightTrend > 0.1  -> "↑"
                                else                      -> "→"
                            }
                            Text(
                                "$trendIcon ${String.format("%+.1f", stats.weightTrend)} kg",
                                fontSize = 11.sp, color = trendColor, fontWeight = FontWeight.Medium
                            )
                        }
                        Text("${stats.daysWithWeight} Tage Daten", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (stats.avgWeightKg != null && stats.daysWithKcal > 0) {
                    VerticalDivider(modifier = Modifier.height(80.dp))
                }

                // Calorie average + trend
                if (stats.daysWithKcal > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔥", fontSize = 22.sp)
                        Text(
                            "${stats.avgActiveKcal.toInt()} kcal",
                            fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = Color(0xFFFF5722)
                        )
                        Text("Ø Akt. Kalorien", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (stats.kcalTrend != null) {
                            val trendColor = when {
                                stats.kcalTrend > 50  -> Color(0xFF4CAF50)  // more activity = green
                                stats.kcalTrend < -50 -> Color(0xFFEF5350)
                                else                   -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val trendIcon = if (stats.kcalTrend >= 0) "↑" else "↓"
                            Text(
                                "$trendIcon ${String.format("%+.0f", stats.kcalTrend)} kcal",
                                fontSize = 11.sp, color = trendColor, fontWeight = FontWeight.Medium
                            )
                        }
                        Text("${stats.daysWithKcal} Tage Daten", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeightTrendCard(weeklyData: List<HealthConnectCache>) {
    val dataPoints = weeklyData
        .filter { it.weightKg != null }
        .sortedBy { it.date }

    if (dataPoints.size < 2) return

    val minWeight = dataPoints.minOf { it.weightKg!! }
    val maxWeight = dataPoints.maxOf { it.weightKg!! }
    val range = (maxWeight - minWeight).coerceAtLeast(0.5)

    val primaryColor = MaterialTheme.colorScheme.primary
    val dotColor = Color(0xFF9C27B0)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Gewichtsverlauf – letzte 7 Tage", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))

            // Min / max labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format("%.1f kg", minWeight), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(String.format("%.1f kg", maxWeight), fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Canvas(modifier = Modifier.fillMaxWidth().height(80.dp)) {
                val w = size.width
                val h = size.height
                val pts = dataPoints.mapIndexed { i, d ->
                    val x = if (dataPoints.size == 1) w / 2f
                    else i.toFloat() / (dataPoints.size - 1) * w
                    val y = h - ((d.weightKg!! - minWeight) / range * h).toFloat()
                    Offset(x, y.coerceIn(4f, h - 4f))
                }
                // Draw line
                if (pts.size >= 2) {
                    val path = Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (k in 1 until pts.size) path.lineTo(pts[k].x, pts[k].y)
                    drawPath(path, color = primaryColor, style = Stroke(width = 3f))
                }
                // Draw dots
                pts.forEach { pt ->
                    drawCircle(color = dotColor, radius = 6f, center = pt)
                    drawCircle(color = Color.White, radius = 3f, center = pt)
                }
            }

            Spacer(Modifier.height(4.dp))
            // X-axis labels
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                dataPoints.forEach { d ->
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN),
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklyStepsCard(weeklyData: List<HealthConnectCache>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Schritte – letzte 7 Tage", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val maxSteps = weeklyData.maxOf { it.steps }.coerceAtLeast(1L)
            Row(modifier = Modifier.fillMaxWidth().height(100.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceEvenly) {
                weeklyData.sortedBy { it.date }.forEach { day ->
                    val fraction = (day.steps.toFloat() / maxSteps).coerceIn(0.02f, 1f)
                    val dayLabel = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.width(36.dp)) {
                        Box(modifier = Modifier.fillMaxWidth(0.7f).fillMaxHeight(fraction)) {
                            Surface(modifier = Modifier.fillMaxSize(),
                                color = if (day.steps >= 10000) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)) {}
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(dayLabel, fontSize = 9.sp)
                    }
                }
            }
            Text("🟢 = 10.000+ Schritte erreicht", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun WeeklySleepCard(weeklyData: List<HealthConnectCache>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Schlaf – letzte 7 Tage", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            weeklyData.sortedBy { it.date }.filter { it.sleepMinutes > 0 }.forEach { day ->
                val dayLabel = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(dayLabel, modifier = Modifier.width(32.dp), fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { (day.sleepMinutes / 480f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(8.dp),
                        color = when (day.sleepQuality) {
                            SleepQuality.GOOD -> Color(0xFF4CAF50)
                            SleepQuality.OK -> Color(0xFFFFA726)
                            else -> Color(0xFFEF5350)
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(day.sleepFormatted, fontSize = 11.sp, modifier = Modifier.width(52.dp))
                }
            }
        }
    }
}

@Composable
private fun BigStatItem(value: String, label: String, icon: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 22.sp)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatSteps(steps: Long): String =
    if (steps >= 1000) String.format("%.1fk", steps / 1000.0) else steps.toString()
