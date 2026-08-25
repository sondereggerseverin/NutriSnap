package ch.nutrisnap.app.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.isFoodEntry
import ch.nutrisnap.app.data.model.isRecipeEntry
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.RecipeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

// ============================================================
// Nährstoffmangel-Trend: Vitamin-/Mineralstoff-Zufuhr der letzten N Tage aus dem
// Tagebuch gegen die EU-NRV-Referenzwerte (siehe domain/NutrientReferenceData.kt).
//
// Datenquellen pro Tagebuch-Eintrag:
//  - FOOD:   FoodItem (Werte pro 100g) * amountGrams/100
//  - RECIPE: Recipe.microNutrientsJson (Werte pro Portion, seit Migration 36->37
//            persistiert) * amountGrams (= Portionsfaktor bei Rezept-Einträgen)
//  - MANUAL: keine Mikronährstoff-Basis vorhanden, wird übersprungen
//
// Bewertet werden NUR Nährstoffe mit einem NRV_REFERENCE-Eintrag (offizieller
// EU-Referenzwert) — für Spurenelemente ohne etablierten Referenzwert (Vanadium,
// Zinn, Arsen, ...) gibt es bewusst keine erfundene Zielgrösse.
//
// Nur Tage MIT mindestens einem Tagebuch-Eintrag zählen in den Schnitt — ein
// ungeloggter Tag bedeutet "keine Daten", nicht "0 Zufuhr".
// ============================================================

enum class DeficiencySeverity { NIEDRIG, KRITISCH }

data class NutrientDeficiencyTrend(
    val key: String,
    val label: String,
    val unit: String,
    /** Durchschnittliche Tages-Zufuhr in % der EU-NRV, über alle getrackten Tage. */
    val avgPctOfNrv: Int,
    val daysTracked: Int,
    /** Anzahl Tage mit < 25% NRV an diesem einzelnen Tag. */
    val daysCritical: Int,
    val severity: DeficiencySeverity
)

data class NutrientDeficiencyResult(
    /** Anzahl Tage im Analysezeitraum mit mind. einem Tagebuch-Eintrag. */
    val trackedDays: Int,
    val minDaysRequired: Int,
    /** Nur Nährstoffe unter dem Schwellenwert, aufsteigend nach avgPctOfNrv sortiert
     *  (niedrigster/kritischster zuerst). Leer heisst entweder "zu wenig Daten"
     *  (trackedDays < minDaysRequired) oder "alles im grünen Bereich". */
    val trends: List<NutrientDeficiencyTrend>
) {
    val hasEnoughData: Boolean get() = trackedDays >= minDaysRequired
}

class NutrientDeficiencyEngine(
    private val diaryRepository: DiaryRepository,
    private val foodItemRepository: FoodItemRepository,
    private val recipeRepository: RecipeRepository
) {
    companion object {
        const val MIN_TRACKED_DAYS = 5
        const val LOW_THRESHOLD_PCT = 80
        const val CRITICAL_DAY_THRESHOLD_PCT = 25
    }

    suspend fun analyzeTrend(daysBack: Int = 14): NutrientDeficiencyResult {
        val entries = diaryRepository.getDiaryEntriesLastNDays(daysBack)
        val byDate = entries.groupBy { it.dateStr }
        val trackedDays = byDate.size

        if (trackedDays < MIN_TRACKED_DAYS) {
            return NutrientDeficiencyResult(trackedDays, MIN_TRACKED_DAYS, emptyList())
        }

        // Caches, damit wiederkehrende Zutaten/Rezepte nicht mehrfach aus der DB
        // geladen werden (typischerweise wenige distinkte Foods/Rezepte über 14 Tage).
        val foodCache = mutableMapOf<Int, FoodItem?>()
        val recipeMicrosCache = mutableMapOf<Long, Map<String, Float>?>()

        val dailyTotals: List<Map<String, Float>> = byDate.values.map { dayEntries ->
            sumMaps(dayEntries.mapNotNull { entry -> microsForEntry(entry, foodCache, recipeMicrosCache) })
        }

        val trends = NRV_REFERENCE.keys.mapNotNull { key ->
            val ref = NRV_REFERENCE.getValue(key)
            val dailyPcts = dailyTotals.map { (it[key] ?: 0f) / ref * 100f }
            val avgPct = dailyPcts.average().toFloat().toInt()
            if (avgPct >= LOW_THRESHOLD_PCT) return@mapNotNull null

            val (label, unit, _) = MICRO_META.getValue(key)
            NutrientDeficiencyTrend(
                key = key,
                label = label,
                unit = unit,
                avgPctOfNrv = avgPct.coerceAtLeast(0),
                daysTracked = trackedDays,
                daysCritical = dailyPcts.count { it < CRITICAL_DAY_THRESHOLD_PCT },
                severity = if (avgPct < CRITICAL_DAY_THRESHOLD_PCT) DeficiencySeverity.KRITISCH else DeficiencySeverity.NIEDRIG
            )
        }.sortedBy { it.avgPctOfNrv }

        return NutrientDeficiencyResult(trackedDays, MIN_TRACKED_DAYS, trends)
    }

    /** Mikronährstoffe (in Gramm, absolut für diesen Eintrag) oder null falls nicht ermittelbar. */
    private suspend fun microsForEntry(
        entry: DiaryEntry,
        foodCache: MutableMap<Int, FoodItem?>,
        recipeMicrosCache: MutableMap<Long, Map<String, Float>?>
    ): Map<String, Float>? = when {
        entry.isFoodEntry -> {
            val food = foodCache.getOrPut(entry.foodItemId) { foodItemRepository.getById(entry.foodItemId) }
            food?.let { with(RecipeNutritionAnalyzer) { it.scaledMicros(entry.amountGrams / 100f) } }
        }
        entry.isRecipeEntry -> {
            val recipeId = -(entry.foodItemId).toLong()
            val perServing = recipeMicrosCache.getOrPut(recipeId) { loadRecipeMicrosPerServing(recipeId) }
            // amountGrams speichert bei Rezept-Einträgen den Portionsfaktor, nicht Gramm
            // (siehe DiaryEntry-Dokumentation in Models.kt) — direkt als Multiplikator nutzbar.
            perServing?.mapValues { (_, v) -> v * entry.amountGrams }
        }
        else -> null // MANUAL: keine Mikronährstoff-Basis vorhanden
    }

    private suspend fun loadRecipeMicrosPerServing(recipeId: Long): Map<String, Float>? {
        val raw = recipeRepository.getById(recipeId)?.microNutrientsJson ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            buildMap<String, Float> { obj.keys().forEach { k -> put(k, obj.getDouble(k).toFloat()) } }
        }.getOrNull()
    }

    private fun sumMaps(maps: List<Map<String, Float>>): Map<String, Float> =
        maps.fold(emptyMap()) { acc, m -> (acc.keys + m.keys).associateWith { (acc[it] ?: 0f) + (m[it] ?: 0f) } }
}

// ============================================================
// ViewModel — manuell instanziiert wie InsightsViewModel (kein Hilt im Projekt)
// ============================================================

class NutrientDeficiencyViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val engine = NutrientDeficiencyEngine(
        DiaryRepository(db),
        FoodItemRepository(db),
        RecipeRepository(db, app)
    )

    private val _result = MutableStateFlow(NutrientDeficiencyResult(0, NutrientDeficiencyEngine.MIN_TRACKED_DAYS, emptyList()))
    val result: StateFlow<NutrientDeficiencyResult> = _result.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { load() }

    fun load(daysBack: Int = 14) {
        viewModelScope.launch {
            _isLoading.value = true
            _result.value = engine.analyzeTrend(daysBack)
            _isLoading.value = false
        }
    }
}
