package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.DiaryEntry
import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.isFoodEntry
import ch.nutrisnap.app.data.model.isRecipeEntry
import ch.nutrisnap.app.data.repository.FoodItemRepository
import ch.nutrisnap.app.data.repository.RecipeRepository
import org.json.JSONObject

/**
 * Priority-Mikronährstoffe auf dem Home-Screen (Micron-inspiriert).
 *
 * Nutzer wählt bis zu [MAX_PRIORITY_NUTRIENTS] Nährstoffe, die dauerhaft
 * als Status-Cards sichtbar bleiben. Werte kommen aus denselben Quellen wie
 * [NutrientDeficiencyEngine] (FoodItem pro 100g × Menge, Recipe.microNutrientsJson).
 *
 * Alle Roh-Werte in Gramm; Anzeige über [MICRO_META].factor / [NRV_REFERENCE].
 */

const val MAX_PRIORITY_NUTRIENTS = 6

/**
 * Default-Auswahl: häufige Lücken + Ballaststoffe.
 * Nur Keys mit [NRV_REFERENCE] oder explizitem Ziel (fiber via [FIBER_GOAL_G_DOMAIN]).
 */
val DEFAULT_PRIORITY_NUTRIENT_KEYS: List<String> = listOf(
    "fiber",
    "vitaminD",
    "iron",
    "magnesium",
    "vitaminC",
    "calcium"
)

/** D-A-CH-Richtwert Ballaststoffe ≥30 g/Tag (gleiche Konstante wie HomeViewModel). */
const val FIBER_GOAL_G_DOMAIN = 30f

enum class PriorityNutrientLevel {
    /** ≥ 80 % des Tagesziels */
    OK,
    /** 40–79 % */
    LOW,
    /** < 40 % */
    CRITICAL,
    /** Kein Referenzwert / keine Daten */
    UNKNOWN
}

data class PriorityNutrientStatus(
    val key: String,
    val label: String,
    val unit: String,
    /** Tageszufuhr in Anzeige-Einheit (mg/µg/g). */
    val amountDisplay: Float,
    /** Tagesziel in Anzeige-Einheit, null wenn kein NRV/Ziel. */
    val goalDisplay: Float?,
    /** 0–100+ Prozent des Ziels, null wenn kein Ziel. */
    val pctOfGoal: Int?,
    val level: PriorityNutrientLevel
)

/**
 * Parst die DataStore-Zeichenkette (kommagetrennt) in gültige Keys.
 * Ungültige Keys werden verworfen; leere Eingabe → Default-Liste.
 */
fun parsePriorityNutrientKeys(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return DEFAULT_PRIORITY_NUTRIENT_KEYS
    val parsed = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() && MICRO_META.containsKey(it) }
        .distinct()
        .take(MAX_PRIORITY_NUTRIENTS)
    return parsed.ifEmpty { DEFAULT_PRIORITY_NUTRIENT_KEYS }
}

fun serializePriorityNutrientKeys(keys: List<String>): String =
    keys.filter { MICRO_META.containsKey(it) }
        .distinct()
        .take(MAX_PRIORITY_NUTRIENTS)
        .joinToString(",")

/**
 * Baut Status-Liste aus bereits aggregierten Tages-Totals (Werte in Gramm).
 */
fun buildPriorityStatuses(
    keys: List<String>,
    totalsGrams: Map<String, Float>
): List<PriorityNutrientStatus> {
    return keys.map { key ->
        val meta = MICRO_META[key]
        val label = meta?.first ?: key
        val unit = meta?.second ?: "g"
        val factor = meta?.third ?: 1f
        val grams = totalsGrams[key] ?: 0f
        val amountDisplay = grams * factor

        val goalGrams: Float? = when (key) {
            "fiber" -> FIBER_GOAL_G_DOMAIN
            else -> NRV_REFERENCE[key]
        }
        val goalDisplay = goalGrams?.let { it * factor }
        val pct = goalGrams?.let { g ->
            if (g <= 0f) null else ((grams / g) * 100f).toInt().coerceAtLeast(0)
        }
        val level = when {
            pct == null -> PriorityNutrientLevel.UNKNOWN
            pct >= 80 -> PriorityNutrientLevel.OK
            pct >= 40 -> PriorityNutrientLevel.LOW
            else -> PriorityNutrientLevel.CRITICAL
        }
        PriorityNutrientStatus(
            key = key,
            label = label,
            unit = unit,
            amountDisplay = amountDisplay,
            goalDisplay = goalDisplay,
            pctOfGoal = pct,
            level = level
        )
    }
}

/**
 * Aggregiert Mikronährstoffe für eine Liste von Tagebuch-Einträgen (ein Tag).
 * Gleiche Datenquellen-Logik wie [NutrientDeficiencyEngine].
 */
class DailyMicronutrientAggregator(
    private val foodItemRepository: FoodItemRepository,
    private val recipeRepository: RecipeRepository
) {
    suspend fun totalsForEntries(entries: List<DiaryEntry>): Map<String, Float> {
        if (entries.isEmpty()) return emptyMap()
        val foodCache = mutableMapOf<Int, FoodItem?>()
        val recipeCache = mutableMapOf<Long, Map<String, Float>?>()
        val maps = entries.mapNotNull { entry -> microsForEntry(entry, foodCache, recipeCache) }
        return sumMaps(maps)
    }

    suspend fun priorityStatuses(
        entries: List<DiaryEntry>,
        keys: List<String>
    ): List<PriorityNutrientStatus> {
        val totals = totalsForEntries(entries)
        // Fiber steht oft direkt auf dem DiaryEntry (auch ohne FoodItem-Lookup).
        val fiberFromEntries = entries.sumOf { it.fiber.toDouble() }.toFloat()
        val merged = if (fiberFromEntries > 0f && (totals["fiber"] ?: 0f) < fiberFromEntries) {
            totals + ("fiber" to fiberFromEntries)
        } else {
            totals
        }
        return buildPriorityStatuses(keys, merged)
    }

    private suspend fun microsForEntry(
        entry: DiaryEntry,
        foodCache: MutableMap<Int, FoodItem?>,
        recipeCache: MutableMap<Long, Map<String, Float>?>
    ): Map<String, Float>? = when {
        entry.isFoodEntry -> {
            val food = foodCache.getOrPut(entry.foodItemId) {
                foodItemRepository.getById(entry.foodItemId)
            }
            food?.let { with(RecipeNutritionAnalyzer) { it.scaledMicros(entry.amountGrams / 100f) } }
        }
        entry.isRecipeEntry -> {
            val recipeId = -(entry.foodItemId).toLong()
            val perServing = recipeCache.getOrPut(recipeId) { loadRecipeMicrosPerServing(recipeId) }
            perServing?.mapValues { (_, v) -> v * entry.amountGrams }
        }
        else -> null
    }

    private suspend fun loadRecipeMicrosPerServing(recipeId: Long): Map<String, Float>? {
        val raw = recipeRepository.getById(recipeId)?.microNutrientsJson ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            buildMap<String, Float> {
                obj.keys().forEach { k -> put(k, obj.getDouble(k).toFloat()) }
            }
        }.getOrNull()
    }

    private fun sumMaps(maps: List<Map<String, Float>>): Map<String, Float> =
        maps.fold(emptyMap()) { acc, m ->
            (acc.keys + m.keys).associateWith { (acc[it] ?: 0f) + (m[it] ?: 0f) }
        }
}
