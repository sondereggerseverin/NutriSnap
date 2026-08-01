package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.GlobalIngredientMatchDao
import ch.nutrisnap.app.data.db.entity.GlobalIngredientMatch

/** Cache-Schicht vor der OFF-Suche in [ch.nutrisnap.app.domain.RecipeNutritionAnalyzer]:
 *  einmal manuell verifizierte Zutat-Matches werden global (über alle Rezepte hinweg)
 *  wiederverwendet, statt bei jedem Rezept erneut per OFF/AI gesucht zu werden. */
class GlobalIngredientDictionary(private val dao: GlobalIngredientMatchDao) {

    suspend fun lookup(ingredientName: String): GlobalIngredientMatch? {
        val normalized = ingredientName.trim().lowercase()
        val existing = dao.findByName(normalized)
        if (existing != null) {
            dao.incrementUsage(normalized)
            return existing
        }
        return null
    }

    suspend fun save(
        originalName: String,
        offProductId: String,
        offProductName: String,
        kcalPer100g: Double,
        proteinPer100g: Double,
        carbsPer100g: Double,
        fatPer100g: Double
    ) {
        val normalized = originalName.trim().lowercase()
        val existing = dao.findByName(normalized)
        if (existing != null) {
            dao.verifyAndUpdate(normalized, offProductId, offProductName, kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g)
        } else {
            dao.insert(
                GlobalIngredientMatch(
                    normalizedName = normalized,
                    originalName = originalName,
                    offProductId = offProductId,
                    offProductName = offProductName,
                    kcalPer100g = kcalPer100g,
                    proteinPer100g = proteinPer100g,
                    carbsPer100g = carbsPer100g,
                    fatPer100g = fatPer100g,
                    isVerifiedByUser = true
                )
            )
        }
    }

    fun getAllVerified() = dao.getAllVerified()
}
