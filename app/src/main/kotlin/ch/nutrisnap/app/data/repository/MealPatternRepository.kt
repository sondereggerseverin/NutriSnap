package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.DetectedMealPatternDao
import ch.nutrisnap.app.data.db.entity.DetectedMealPatternEntity
import ch.nutrisnap.app.data.model.MealType
import ch.nutrisnap.app.domain.DetectedMealPattern

/** Persistenz für [ch.nutrisnap.app.domain.MealPatternDetector] — bewusst als eigenes
 *  Repository statt MealTemplateRepository zu erweitern, da manuell angelegte Vorlagen
 *  (MealTemplate) und automatisch erkannte Muster (DetectedMealPattern) unterschiedliche
 *  Lebenszyklen haben (Muster werden periodisch neu berechnet, nicht vom Nutzer editiert). */
class MealPatternRepository(private val dao: DetectedMealPatternDao) {

    suspend fun findByFingerprint(fingerprint: String): DetectedMealPatternEntity? =
        dao.findByFingerprint(fingerprint)

    suspend fun updateOccurrenceCount(fingerprint: String, occurrences: Int) =
        dao.updateOccurrenceCount(fingerprint, occurrences)

    suspend fun saveDetected(fingerprint: String, pattern: DetectedMealPattern) {
        dao.insert(
            DetectedMealPatternEntity(
                fingerprint = fingerprint,
                label = pattern.label,
                mealType = pattern.mealType,
                foodItemIds = pattern.foodItemIds.joinToString(","),
                avgKcal = pattern.avgKcal,
                occurrences = pattern.occurrences
            )
        )
    }

    suspend fun getSuggestions(mealType: MealType): List<DetectedMealPattern> =
        dao.getByMealType(mealType).map {
            DetectedMealPattern(
                id = it.id,
                label = it.label,
                mealType = it.mealType,
                foodItemIds = it.foodItemIds.split(",").filter { s -> s.isNotBlank() }.map { s -> s.toInt() },
                foodNames = emptyList(),
                avgKcal = it.avgKcal,
                occurrences = it.occurrences,
                lastSeenAt = it.lastSeenAt
            )
        }
}
