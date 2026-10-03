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
        fatPer100g: Double,
        verifiedByUser: Boolean = true
    ) {
        val normalized = originalName.trim().lowercase()
        if (normalized.isBlank()) return
        val existing = dao.findByName(normalized)
        // Nutzer-Standard nie durch automatischen Netzwerk-Cache überschreiben
        if (existing?.isVerifiedByUser == true && !verifiedByUser) return
        if (existing != null && verifiedByUser) {
            dao.verifyAndUpdate(normalized, offProductId, offProductName, kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g)
        } else if (existing != null && !verifiedByUser) {
            // bereits gecacht – nicht erneut schreiben (würde verified-Flag falsch setzen)
            return
        } else {
            dao.insert(
                GlobalIngredientMatch(
                    normalizedName = normalized,
                    originalName = originalName.trim(),
                    offProductId = offProductId,
                    offProductName = offProductName,
                    kcalPer100g = kcalPer100g,
                    proteinPer100g = proteinPer100g,
                    carbsPer100g = carbsPer100g,
                    fatPer100g = fatPer100g,
                    isVerifiedByUser = verifiedByUser
                )
            )
        }
    }

    /** Speichert ein gescanntes/gewähltes FoodItem als persönlichen Standard für [aliasName]. */
    suspend fun saveAsStandard(aliasName: String, food: ch.nutrisnap.app.data.model.FoodItem) {
        save(
            originalName = aliasName,
            offProductId = food.barcode.orEmpty(),
            offProductName = listOfNotNull(food.brand, food.name).joinToString(" ").ifBlank { food.name },
            kcalPer100g = (food.calories ?: 0f).toDouble(),
            proteinPer100g = (food.protein ?: 0f).toDouble(),
            carbsPer100g = (food.carbs ?: 0f).toDouble(),
            fatPer100g = (food.fat ?: 0f).toDouble()
        )
    }

    suspend fun delete(normalizedName: String) {
        dao.deleteByName(normalizedName.trim().lowercase())
    }

    fun getAllVerified() = dao.getAllVerified()
}
