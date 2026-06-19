package ch.nutrisnap.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.nutrisnap.app.data.model.HealthConnectCache
import ch.nutrisnap.app.data.model.SleepQuality

/**
 * Kompakte Dashboard-Card für Health Connect Daten.
 * Zeigt Schritte, Kalorien, Gewicht, Schlaf auf einen Blick.
 */
@Composable
fun HealthConnectCard(
    data: HealthConnectCache?,
    adjustedCalorieGoal: Int,
    hasPermission: Boolean,
    isLoading: Boolean,
    onConnectClick: () -> Unit,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Samsung Health",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
                if (hasPermission) {
                    IconButton(onClick = onSyncClick, modifier = Modifier.size(32.dp)) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Sync", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!hasPermission) {
                // Nicht verbunden
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Verbinde Samsung Health für\nautomatische Kalorienziel-Anpassung",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onConnectClick) {
                        Text("Verbinden")
                    }
                }
            } else if (data == null) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            } else {
                // Daten-Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    HealthStatItem(
                        icon = Icons.Default.DirectionsWalk,
                        value = formatSteps(data.steps),
                        label = "Schritte",
                        color = Color(0xFF2196F3)
                    )
                    HealthStatItem(
                        icon = Icons.Default.LocalFireDepartment,
                        value = "+${data.totalActivityCalories} kcal",
                        label = "Aktiv",
                        color = Color(0xFFFF5722)
                    )
                    data.weightKg?.let {
                        HealthStatItem(
                            icon = Icons.Default.Monitor,
                            value = String.format("%.1f kg", it),
                            label = "Gewicht",
                            color = Color(0xFF9C27B0)
                        )
                    }
                    HealthStatItem(
                        icon = Icons.Default.Bedtime,
                        value = data.sleepQuality.emoji,
                        label = if (data.sleepMinutes > 0) data.sleepFormatted else "Schlaf",
                        color = Color(0xFF3F51B5)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Angepasstes Kalorienziel
                if (data.totalActivityCalories > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Kalorienziel heute: $adjustedCalorieGoal kcal " +
                                "(+${data.totalActivityCalories} aus Aktivität)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthStatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatSteps(steps: Long): String = when {
    steps >= 1000 -> String.format("%.1fk", steps / 1000.0)
    else -> steps.toString()
}
