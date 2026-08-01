package ch.nutrisnap.app.ui.screens.insights

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.CorrelationInsight
import ch.nutrisnap.app.domain.InsightsViewModel
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    onBack: () -> Unit = {},
    vm: InsightsViewModel = viewModel()
) {
    val insights by vm.insights.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        if (insights.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(NutriSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Insights, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(NutriSpacing.md))
                Text(
                    "Noch keine Zusammenhänge gefunden. Dafür braucht es mind. 1–2 Wochen an Schlaf- und Tagebuchdaten.",
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(NutriSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
            ) {
                items(insights) { insight -> InsightCard(insight) }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: CorrelationInsight) {
    Card(shape = RoundedCornerShape(NutriRadius.lg)) {
        Column(Modifier.padding(NutriSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)) {
                Icon(Icons.Default.TrendingDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(insight.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(NutriSpacing.xs))
            Text(insight.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(NutriSpacing.xs))
            Text(
                "Basiert auf ${insight.sampleSize} Tagen",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
