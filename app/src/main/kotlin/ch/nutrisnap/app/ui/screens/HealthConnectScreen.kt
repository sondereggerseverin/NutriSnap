package ch.nutrisnap.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.ui.viewmodel.HealthConnectViewModel
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun HealthConnectScreen(
    onRequestPermission: () -> Unit,
    viewModel: HealthConnectViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val adjustedGoal by viewModel.adjustedCalorieGoal.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Aktivität & Gesundheit",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Verbunden mit Samsung Health",
                fontSize = 13.sp,
                color = if (uiState.hasPermission) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
        }

        // Fehler-Banner
        uiState.syncError?.let { error ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(error, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // Heute Übersicht
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

        // 7-Tage Schritte
        if (uiState.weeklyData.isNotEmpty()) {
            item {
                WeeklyStepsCard(weeklyData = uiState.weeklyData)
            }
        }

        // Schlaf letzte Tage
        if (uiState.weeklyData.any { it.sleepMinutes > 0 }) {
            item {
                WeeklySleepCard(weeklyData = uiState.weeklyData)
            }
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
            Text("Heute", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(12.dp))

            if (!hasPermission) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.FavoriteBorder, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Samsung Health verbinden", fontWeight = FontWeight.Medium)
                    Text("Für automatische Kalorienziel-Anpassung", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onConnectClick) { Text("Jetzt verbinden") }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    BigStatItem(
                        value = if (data != null) formatSteps(data.steps) else "–",
                        label = "Schritte",
                        icon = "👟",
                        color = Color(0xFF2196F3)
                    )
                    BigStatItem(
                        value = if (data != null) "${data.totalActivityCalories}" else "–",
                        label = "Akt. kcal",
                        icon = "🔥",
                        color = Color(0xFFFF5722)
                    )
                    BigStatItem(
                        value = if (data?.weightKg != null) String.format("%.1f", data.weightKg) else "–",
                        label = "kg",
                        icon = "⚖️",
                        color = Color(0xFF9C27B0)
                    )
                    BigStatItem(
                        value = if (data != null && data.sleepMinutes > 0) "${data.sleepMinutes / 60}h" else "–",
                        label = "Schlaf",
                        icon = data?.sleepQuality?.emoji ?: "😴",
                        color = Color(0xFF3F51B5)
                    )
                }

                if (data != null && data.totalActivityCalories > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TrendingUp, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Kalorienziel angepasst: $adjustedGoal kcal",
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    Text(
                        "Basis 2000 + ${data.totalActivityCalories} aus Aktivität",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Spacer(modifier = Modifier.height(12.dp))
            val maxSteps = weeklyData.maxOf { it.steps }.coerceAtLeast(1L)
            Row(
                modifier = Modifier.fillMaxWidth().height(100.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                weeklyData.sortedBy { it.date }.forEach { day ->
                    val fraction = (day.steps.toFloat() / maxSteps).coerceIn(0.02f, 1f)
                    val dayLabel = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                        modifier = Modifier.width(36.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .fillMaxHeight(fraction)
                                .then(
                                    Modifier
                                        .padding(horizontal = 4.dp)
                                )
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = if (day.steps >= 10000) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            ) {}
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(dayLabel, fontSize = 9.sp)
                    }
                }
            }
            Text(
                "🟢 = 10.000+ Schritte erreicht",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun WeeklySleepCard(weeklyData: List<HealthConnectCache>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Schlaf – letzte 7 Tage", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            weeklyData.sortedBy { it.date }.filter { it.sleepMinutes > 0 }.forEach { day ->
                val dayLabel = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dayLabel, modifier = Modifier.width(32.dp), fontSize = 12.sp)
                    LinearProgressIndicator(
                        progress = { (day.sleepMinutes / 480f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(8.dp),
                        color = when (day.sleepQuality) {
                            ch.nutrisnap.app.data.model.SleepQuality.GOOD -> Color(0xFF4CAF50)
                            ch.nutrisnap.app.data.model.SleepQuality.OK -> Color(0xFFFFA726)
                            else -> Color(0xFFEF5350)
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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

private fun formatSteps(steps: Long): String = when {
    steps >= 1000 -> String.format("%.1fk", steps / 1000.0)
    else -> steps.toString()
}
