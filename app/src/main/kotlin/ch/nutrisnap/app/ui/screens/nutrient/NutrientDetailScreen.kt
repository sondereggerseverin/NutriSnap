package ch.nutrisnap.app.ui.screens.nutrient

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.RecipeRepository
import ch.nutrisnap.app.domain.DailyMicronutrientAggregator
import ch.nutrisnap.app.domain.FIBER_GOAL_G_DOMAIN
import ch.nutrisnap.app.domain.MICRO_META
import ch.nutrisnap.app.domain.NRV_REFERENCE
import ch.nutrisnap.app.domain.PriorityNutrientLevel
import ch.nutrisnap.app.domain.buildPriorityStatuses
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DayNutrientPoint(
    val date: LocalDate,
    val amountDisplay: Float,
    val pctOfGoal: Int?
)

data class NutrientContributorUi(
    val name: String,
    val amountDisplay: Float,
    val pctOfToday: Int?
)

data class NutrientDetailUiState(
    val key: String = "",
    val label: String = "",
    val unit: String = "",
    val amountDisplay: Float = 0f,
    val goalDisplay: Float? = null,
    val pctOfGoal: Int? = null,
    val level: PriorityNutrientLevel = PriorityNutrientLevel.UNKNOWN,
    val description: String = "",
    val history: List<DayNutrientPoint> = emptyList(),
    /** Top-Quellen heute (leer = keine Mikro-Daten in den geloggten Foods). */
    val contributors: List<NutrientContributorUi> = emptyList(),
    val isLoading: Boolean = true
)

private val NUTRIENT_BLURBS: Map<String, String> = mapOf(
    "fiber" to "Ballaststoffe unterstützen die Verdauung und helfen, Blutzucker und Sättigung stabil zu halten. Richtwert: mind. 30 g/Tag (D-A-CH).",
    "vitaminD" to "Vitamin D ist wichtig für Knochen, Immunsystem und Muskelkraft. Viele Menschen in Mitteleuropa liegen im Winter unter dem Bedarf.",
    "iron" to "Eisen transportiert Sauerstoff im Blut. Besonders relevant bei fleischarmer Ernährung und für menstruierende Personen.",
    "magnesium" to "Magnesium steckt in vielen Enzymen und unterstützt Muskeln, Nerven und EnergieStoffwechsel.",
    "vitaminC" to "Vitamin C ist ein Antioxidans und unterstützt die Eisenaufnahme sowie das Immunsystem.",
    "calcium" to "Calcium ist der Hauptbaustein von Knochen und Zähnen und an der Muskelkontraktion beteiligt.",
    "vitaminB12" to "Vitamin B12 wird vor allem über tierische Lebensmittel aufgenommen und ist für Nerven und Blutbildung wichtig.",
    "zinc" to "Zink spielt eine Rolle bei Immunfunktion, Wundheilung und vielen Enzymen.",
    "potassium" to "Kalium hilft bei Flüssigkeitshaushalt und normaler Blutdruckregulation.",
    "selenium" to "Selen ist Bestandteil von Antioxidans-Enzymen und unterstützt die Schilddrüsenfunktion."
)

class NutrientDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val diaryRepo = DiaryRepository(db)
    private val aggregator = DailyMicronutrientAggregator(
        FoodItemRepository(db),
        RecipeRepository(db, app)
    )

    private val _state = MutableStateFlow(NutrientDetailUiState())
    val state: StateFlow<NutrientDetailUiState> = _state.asStateFlow()

    fun load(key: String, daysBack: Int = 7) {
        viewModelScope.launch {
            _state.value = NutrientDetailUiState(key = key, isLoading = true)
            val meta = MICRO_META[key]
            val label = meta?.first ?: key
            val unit = meta?.second ?: "g"
            val factor = meta?.third ?: 1f
            val goalGrams = when (key) {
                "fiber" -> FIBER_GOAL_G_DOMAIN
                else -> NRV_REFERENCE[key]
            }
            val goalDisplay = goalGrams?.let { it * factor }

            val today = LocalDate.now()
            val history = mutableListOf<DayNutrientPoint>()
            var todayGrams = 0f

            for (i in 0 until daysBack) {
                val date = today.minusDays(i.toLong())
                val entries = diaryRepo.getEntriesForDateOnce(date)
                val totals = aggregator.totalsForEntries(entries)
                val fiberFallback = entries.sumOf { it.fiber.toDouble() }.toFloat()
                val grams = when {
                    key == "fiber" && fiberFallback > (totals["fiber"] ?: 0f) -> fiberFallback
                    else -> totals[key] ?: 0f
                }
                if (i == 0) todayGrams = grams
                val pct = goalGrams?.let { g ->
                    if (g <= 0f) null else ((grams / g) * 100f).toInt().coerceAtLeast(0)
                }
                history.add(
                    DayNutrientPoint(
                        date = date,
                        amountDisplay = grams * factor,
                        pctOfGoal = pct
                    )
                )
            }

            val status = buildPriorityStatuses(listOf(key), mapOf(key to todayGrams)).firstOrNull()
            val todayEntries = diaryRepo.getEntriesForDateOnce(today)
            val top = aggregator.topContributors(todayEntries, key, limit = 8)
            val contributors = top.map { c ->
                val display = c.amountGrams * factor
                val pct = if (todayGrams > 0f) {
                    ((c.amountGrams / todayGrams) * 100f).toInt().coerceIn(0, 100)
                } else null
                NutrientContributorUi(name = c.name, amountDisplay = display, pctOfToday = pct)
            }

            _state.value = NutrientDetailUiState(
                key = key,
                label = label,
                unit = unit,
                amountDisplay = status?.amountDisplay ?: (todayGrams * factor),
                goalDisplay = goalDisplay,
                pctOfGoal = status?.pctOfGoal,
                level = status?.level ?: PriorityNutrientLevel.UNKNOWN,
                description = NUTRIENT_BLURBS[key]
                    ?: "Tageszufuhr im Vergleich zur Referenzmenge (NRV bzw. D-A-CH bei Ballaststoffen).",
                history = history,
                contributors = contributors,
                isLoading = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutrientDetailScreen(
    nutrientKey: String,
    onBack: () -> Unit = {},
    vm: NutrientDetailViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(nutrientKey) { vm.load(nutrientKey) }

    val levelColor = when (state.level) {
        PriorityNutrientLevel.OK -> Color(0xFF2E7D32)
        PriorityNutrientLevel.LOW -> Color(0xFFF9A825)
        PriorityNutrientLevel.CRITICAL -> Color(0xFFC62828)
        PriorityNutrientLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.label.ifBlank { "Nährstoff" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(NutriSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(NutriSpacing.md)
        ) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(NutriRadius.lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(NutriSpacing.lg)) {
                        Text("Heute", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatAmount(state.amountDisplay, state.unit),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = levelColor
                        )
                        state.goalDisplay?.let { goal ->
                            Text(
                                "Ziel: ${formatAmount(goal, state.unit)}" +
                                    (state.pctOfGoal?.let { " · $it %" } ?: ""),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            val progress = ((state.pctOfGoal ?: 0).coerceIn(0, 100)) / 100f
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = levelColor,
                                trackColor = levelColor.copy(alpha = 0.15f)
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    state.description,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
            item {
                Text(
                    "Heute aus",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                if (state.contributors.isEmpty()) {
                    Text(
                        if (state.amountDisplay <= 0f) {
                            "Keine Quelle mit ${state.label}-Angabe in den heutigen Einträgen. " +
                                "Oft fehlen Vitamine bei Marken-/OFF-Produkten – generische USDA-Treffer (z. B. Lachs, Eier) liefern Werte."
                        } else {
                            "Beitrag konnte nicht den einzelnen Lebensmitteln zugeordnet werden."
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }
            if (state.contributors.isNotEmpty()) {
                items(state.contributors) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            c.name,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                            maxLines = 2
                        )
                        Text(
                            buildString {
                                append(formatAmount(c.amountDisplay, state.unit))
                                c.pctOfToday?.let { append(" · $it %") }
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
            }
            item {
                Text(
                    "Letzte 7 Tage",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(state.history) { point ->
                val dateLabel = point.date.format(
                    DateTimeFormatter.ofPattern("EEE d.M.", Locale.GERMAN)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dateLabel, fontSize = 14.sp)
                    Text(
                        buildString {
                            append(formatAmount(point.amountDisplay, state.unit))
                            point.pctOfGoal?.let { append("  ·  $it %") }
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

private fun formatAmount(value: Float, unit: String): String {
    val num = when {
        value >= 100f -> value.toInt().toString()
        value >= 10f -> "%.0f".format(value)
        value >= 1f -> "%.1f".format(value)
        value > 0f -> "%.2f".format(value)
        else -> "0"
    }
    return "$num $unit"
}
