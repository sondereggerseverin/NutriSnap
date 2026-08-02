package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.MealPatternRepository
import java.time.LocalTime

// ============================================================
// FEATURE 5: Wiederkehrende Mahlzeiten automatisch erkennen → 1-Tap-Relog
//
// Bereits integriert in DiaryViewModel (Trigger in init{}, siehe unten) und
// DiaryScreen (Vorschlags-Banner mit 1-Tap-Relog).
// ============================================================

data class DetectedMealPattern(
    val id: Long = 0,
    val label: String,
    val mealType: MealType,
    val foodItemIds: List<Int>,
    val foodNames: List<String>,   // wird beim Anzeigen per ID aufgelöst
    val avgKcal: Double,
    val occurrences: Int,
    val lastSeenAt: Long = System.currentTimeMillis()
)

private data class MealSnapshot(
    val dateStr: String,
    val mealType: MealType,
    val foodItemIds: Set<Int>,
    val kcal: Double
)

/**
 * Erkennt wiederkehrende Mahlzeiten (gleiche Food-Kombination, gleicher MealType,
 * mind. [MIN_OCCURRENCES] mal in den letzten [LOOKBACK_DAYS] Tagen) für 1-Tap-Relog-
 * Vorschläge. Einzelne Zutaten (nur 1 Food an dem Tag/Slot) werden ignoriert, da dafür
 * die normalen Favoriten schon ausreichen.
 */
class MealPatternDetector(
    private val diaryRepository: DiaryRepository,
    private val mealPatternRepository: MealPatternRepository
) {
    companion object {
        const val MIN_OCCURRENCES = 3
        const val LOOKBACK_DAYS = 28
    }

    suspend fun detectAndSavePatterns() {
        val allEntries = diaryRepository.getDiaryEntriesLastNDays(LOOKBACK_DAYS)

        // Gruppiere nach Datum + MealType
        val mealsByDayAndType = allEntries.groupBy { it.dateStr to it.mealType }

        // Fingerprint = sortierte foodItemIds als String
        val fingerprints = mutableMapOf<String, MutableList<MealSnapshot>>()
        for ((key, entries) in mealsByDayAndType) {
            val foodSet = entries.map { it.foodItemId }.toSet()
            if (foodSet.size < 2) continue  // Einzelne Zutaten ignorieren
            val fp = foodSet.sorted().joinToString(",")
            fingerprints.getOrPut(fp) { mutableListOf() }
                .add(MealSnapshot(key.first, key.second, foodSet, entries.sumOf { it.calories.toDouble() }))
        }

        for ((fp, snaps) in fingerprints) {
            if (snaps.size < MIN_OCCURRENCES) continue

            val existing = mealPatternRepository.findByFingerprint(fp)
            if (existing != null) {
                mealPatternRepository.updateOccurrenceCount(fp, snaps.size)
                continue
            }

            val mealType = snaps.first().mealType
            val label = when (mealType) {
                MealType.BREAKFAST -> "dein übliches Frühstück"
                MealType.LUNCH     -> "dein übliches Mittagessen"
                MealType.DINNER    -> "dein übliches Abendessen"
                MealType.SNACK     -> "deine übliche Zwischenmahlzeit"
            }

            mealPatternRepository.saveDetected(
                fingerprint = fp,
                pattern = DetectedMealPattern(
                    label = label,
                    mealType = mealType,
                    foodItemIds = snaps.first().foodItemIds.toList(),
                    foodNames = emptyList(),
                    avgKcal = snaps.map { it.kcal }.average(),
                    occurrences = snaps.size
                )
            )
        }
    }

    suspend fun getSuggestionsForNow(): List<DetectedMealPattern> {
        val currentMealType = currentMealTypeFromHour()
        return mealPatternRepository.getSuggestions(currentMealType)
            .sortedByDescending { it.occurrences }
            .take(3)
    }

    private fun currentMealTypeFromHour(): MealType = when (LocalTime.now().hour) {
        in 5..10  -> MealType.BREAKFAST
        in 11..14 -> MealType.LUNCH
        in 17..21 -> MealType.DINNER
        else      -> MealType.SNACK
    }
}

// ============================================================
// Trigger: DiaryViewModel.init (nicht per WorkManager) — läuft einmal pro
// ViewModel-Instanz, also jedes Mal, wenn der Diary-Screen geöffnet wird. Für nur
// 28 Tage Historie günstig genug, kein eigener Hintergrund-Job nötig.
// ============================================================
