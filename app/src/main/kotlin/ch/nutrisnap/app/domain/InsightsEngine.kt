package ch.nutrisnap.app.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.HealthConnectRepository
import ch.nutrisnap.app.health.HealthConnectManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

// ============================================================
// FEATURE 4: Korrelations-Insights (Schlaf, Tagebuch-Lücken, kcal)
//
// Integration:
//  1. InsightsViewModel (unten, manuell instanziiert wie HomeViewModel) an einer neuen
//     "Insights"-Stelle im Stats-/Analyse-Screen einbinden.
//  2. insights.collect { ... } beobachten und als Karten/Banner anzeigen.
//
// Abweichung vom ursprünglichen Entwurf: Die dritte geplante Korrelation ("Bewegung
// verschiebt die Essenszeit nach hinten") ist NICHT enthalten — sie bräuchte einen
// Logging-Zeitstempel pro Diary-Eintrag, den es aktuell nicht gibt (DiaryEntry speichert
// nur das Datum, nicht die Uhrzeit der Eingabe). Das wäre eine eigene, grössere Änderung
// an der Tagebuch-Speicherung und ist bewusst nicht Teil dieses Patches.
// ============================================================

enum class InsightType { SLEEP_CALORIES, DIARY_GAPS_SLEEP }

data class CorrelationInsight(
    val type: InsightType,
    val title: String,
    val description: String,
    val strength: Double,      // Pearson r, -1.0 bis 1.0
    val sampleSize: Int,
    val isPositive: Boolean
)

/**
 * Sucht nach Korrelationen zwischen Schlaf (Health-Connect-Cache) und Kalorienverhalten
 * (Diary). Nutzt ausschliesslich bereits lokal gecachte Daten — kein Live-HealthConnect-
 * Zugriff pro Tag, siehe [HealthConnectRepository.getRange].
 */
class InsightsEngine(
    private val healthConnectRepository: HealthConnectRepository,
    private val diaryRepository: DiaryRepository
) {
    suspend fun generateInsights(weeksBack: Int = 8): List<CorrelationInsight> {
        val insights = mutableListOf<CorrelationInsight>()
        val days = weeksBack * 7
        val today = LocalDate.now()
        val from = today.minusDays((days - 1).toLong())

        val hcCache = healthConnectRepository.getRange(from, today).first()
        val dailySummaries = diaryRepository.getSummaryBetween(from, today).first()

        val sleepByDate = hcCache
            .filter { it.sleepMinutes > 0 }
            .associate { it.date to it.sleepMinutes / 60.0 }
        val kcalByDate = dailySummaries.associate { LocalDate.parse(it.dateStr) to it.calories.toDouble() }

        if (sleepByDate.size >= 7 && kcalByDate.size >= 7) {
            correlateSleepToCalories(sleepByDate, kcalByDate)?.let { insights.add(it) }
        }

        // Tage im Fenster ohne jeden Diary-Eintrag = "Lücke". Heute wird nicht gewertet,
        // der Tag ist noch nicht vorbei.
        val loggedDates = dailySummaries.map { LocalDate.parse(it.dateStr) }.toSet()
        val gapDates = generateSequence(from) { it.plusDays(1) }
            .takeWhile { it.isBefore(today) }
            .filter { it !in loggedDates }
            .toList()
        if (gapDates.size >= 3 && sleepByDate.size >= 7) {
            correlateDiaryGapsToSleep(gapDates, sleepByDate)?.let { insights.add(it) }
        }

        return insights.sortedByDescending { abs(it.strength) }
    }

    private fun correlateSleepToCalories(
        sleep: Map<LocalDate, Double>,
        kcal: Map<LocalDate, Double>
    ): CorrelationInsight? {
        val commonDays = sleep.keys.intersect(kcal.keys).sorted()
        if (commonDays.size < 7) return null

        val shortSleepDays = commonDays.filter { (sleep[it] ?: 8.0) < 6.0 }
        val normalSleepDays = commonDays.filter { (sleep[it] ?: 8.0) >= 6.0 }
        if (shortSleepDays.isEmpty() || normalSleepDays.isEmpty()) return null

        val avgKcalShort = shortSleepDays.mapNotNull { kcal[it] }.average()
        val avgKcalNormal = normalSleepDays.mapNotNull { kcal[it] }.average()
        val delta = avgKcalShort - avgKcalNormal

        val r = pearsonCorrelation(
            commonDays.map { sleep[it] ?: 0.0 },
            commonDays.map { kcal[it] ?: 0.0 }
        )
        if (abs(r) < 0.25) return null

        return CorrelationInsight(
            type = InsightType.SLEEP_CALORIES,
            title = "Wenig Schlaf → mehr Hunger",
            description = "In Nächten mit <6h Schlaf hast du im Schnitt ${delta.toInt()} kcal mehr gegessen",
            strength = r,
            sampleSize = commonDays.size,
            isPositive = false
        )
    }

    private fun correlateDiaryGapsToSleep(
        gapDates: List<LocalDate>,
        sleep: Map<LocalDate, Double>
    ): CorrelationInsight? {
        // Schlaf der Nacht vor der Lücke — fällt auf den Tag selbst zurück, falls für die
        // Vornacht nichts gecacht ist (z.B. Cache-Lücke).
        val sleepBeforeGap = gapDates.mapNotNull { date -> sleep[date] ?: sleep[date.minusDays(1)] }
        if (sleepBeforeGap.size < 3) return null

        val avgSleepBeforeGap = sleepBeforeGap.average()
        val avgSleepOverall = sleep.values.average()
        if (avgSleepBeforeGap >= avgSleepOverall - 0.5) return null

        return CorrelationInsight(
            type = InsightType.DIARY_GAPS_SLEEP,
            title = "Tagebuch-Lücken nach wenig Schlaf",
            description = "Deine ${gapDates.size} nicht geloggten Tage fielen häufig auf Tage nach " +
                    "Ø ${String.format("%.1f", avgSleepBeforeGap)}h Schlaf " +
                    "(normal: ${String.format("%.1f", avgSleepOverall)}h)",
            strength = -(avgSleepOverall - avgSleepBeforeGap) / avgSleepOverall,
            sampleSize = sleepBeforeGap.size,
            isPositive = false
        )
    }

    private fun pearsonCorrelation(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        if (n < 3) return 0.0
        val mx = x.average(); val my = y.average()
        val num = x.zip(y).sumOf { (xi, yi) -> (xi - mx) * (yi - my) }
        val denX = sqrt(x.sumOf { (it - mx) * (it - mx) })
        val denY = sqrt(y.sumOf { (it - my) * (it - my) })
        return if (denX == 0.0 || denY == 0.0) 0.0 else num / (denX * denY)
    }
}

// ============================================================
// ViewModel — manuell instanziiert wie HomeViewModel (kein Hilt im Projekt)
// ============================================================

class InsightsViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val engine = InsightsEngine(
        HealthConnectRepository(HealthConnectManager(app), db.healthConnectDao()),
        DiaryRepository(db)
    )

    private val _insights = MutableStateFlow<List<CorrelationInsight>>(emptyList())
    val insights: StateFlow<List<CorrelationInsight>> = _insights.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _insights.value = engine.generateInsights()
        }
    }
}
