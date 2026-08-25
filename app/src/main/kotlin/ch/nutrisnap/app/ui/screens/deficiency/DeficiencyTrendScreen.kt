package ch.nutrisnap.app.ui.screens.deficiency

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.DeficiencySeverity
import ch.nutrisnap.app.domain.NutrientDeficiencyTrend
import ch.nutrisnap.app.domain.NutrientDeficiencyViewModel
import ch.nutrisnap.app.ui.theme.MacroColors
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeficiencyTrendScreen(
    onBack: () -> Unit = {},
    vm: NutrientDeficiencyViewModel = viewModel()
) {
    val result by vm.result.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nährstoffmangel-Trend") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        when {
            isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !result.hasEnoughData -> EmptyState(
                icon = Icons.Default.WarningAmber,
                title = "Noch nicht genug Daten",
                message = "Basierend auf ${result.trackedDays} von ${result.minDaysRequired} nötigen " +
                    "getrackten Tagen der letzten 14 Tage. Trag weiter dein Tagebuch, dann erscheint hier " +
                    "der Trend für Vitamine und Mineralstoffe.",
                modifier = Modifier.padding(padding)
            )
            result.trends.isEmpty() -> EmptyState(
                icon = Icons.Default.CheckCircle,
                iconTint = MacroColors.calories,
                title = "Alles im grünen Bereich",
                message = "Basierend auf ${result.trackedDays} getrackten Tagen liegt deine Zufuhr bei " +
                    "allen ausgewerteten Vitaminen und Mineralstoffen über 80% der EU-Referenzmenge.",
                modifier = Modifier.padding(padding)
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(NutriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
            ) {
                item {
                    Text(
                        "Basierend auf ${result.trackedDays} getrackten Tagen der letzten 14 Tage. " +
                            "Werte unter 80% der EU-Referenzmenge (NRV) im Schnitt:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(result.trends, key = { it.key }) { trend -> TrendCard(trend) }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    iconTint: Color = Color.Unspecified
) {
    Column(
        modifier.fillMaxSize().padding(NutriSpacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = if (iconTint == Color.Unspecified) LocalContentColor.current else iconTint
        )
        Spacer(Modifier.height(NutriSpacing.md))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(NutriSpacing.xs))
        Text(
            message,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrendCard(trend: NutrientDeficiencyTrend) {
    val color = if (trend.severity == DeficiencySeverity.KRITISCH) MacroColors.fat else MacroColors.carbs
    Card(shape = RoundedCornerShape(NutriRadius.lg)) {
        Column(Modifier.padding(NutriSpacing.lg)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(trend.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    "${trend.avgPctOfNrv}% NRV",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = color
                )
            }
            Spacer(Modifier.height(NutriSpacing.sm))
            LinearProgressIndicator(
                progress = { (trend.avgPctOfNrv / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Spacer(Modifier.height(NutriSpacing.xs))
            Text(
                if (trend.daysCritical > 0) {
                    "An ${trend.daysCritical} von ${trend.daysTracked} Tagen unter 25% der Referenzmenge"
                } else {
                    "Über ${trend.daysTracked} Tage im Schnitt unter 80% der Referenzmenge"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
