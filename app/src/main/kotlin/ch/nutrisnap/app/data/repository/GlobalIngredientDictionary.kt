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
        // Schreibvarianten (Klammern, Reihenfolge, «gerieben», «roh» …) auf denselben Standard abbilden
        val key = IngredientKey.of(ingredientName)
        if (key.isBlank()) return null
        val viaKey = keyIndex()[key] ?: return null
        dao.incrementUsage(viaKey.normalizedName)
        return viaKey
    }

    /** Schlüssel → Eintrag. Geprüfte Standards haben Vorrang vor automatisch gecachten Treffern. */
    private suspend fun keyIndex(): Map<String, GlobalIngredientMatch> {
        sharedIndex?.let { return it }
        val map = HashMap<String, GlobalIngredientMatch>()
        for (e in dao.getAllOnce().sortedBy { it.isVerifiedByUser }) {
            val k = IngredientKey.of(e.normalizedName)
            if (k.isNotBlank()) map[k] = e
        }
        sharedIndex = map
        return map
    }

    private fun invalidateIndex() { sharedIndex = null }

    companion object {
        // Mehrere ViewModels erzeugen eigene Dictionary-Instanzen – der Index wird gemeinsam genutzt.
        @Volatile private var sharedIndex: Map<String, GlobalIngredientMatch>? = null
    }

    suspend fun save(
        originalName: String,
        offProductId: String,
        offProductName: String,
        kcalPer100g: Double,
        proteinPer100g: Double,
        carbsPer100g: Double,
        fatPer100g: Double,
        fiberPer100g: Double = 0.0,
        verifiedByUser: Boolean = true
    ) {
        val normalized = originalName.trim().lowercase()
        if (normalized.isBlank()) return
        invalidateIndex()
        // Automatischen Cache für offensichtlichen Rezept-Müll ablehnen
        // (Anweisungen, Hashtags, Satzfragmente) – manuelle Standards bleiben erlaubt
        if (!verifiedByUser && looksLikeJunkAlias(normalized)) return
        val existing = dao.findByName(normalized)
        // Nutzer-Standard nie durch automatischen Netzwerk-Cache überschreiben
        if (existing?.isVerifiedByUser == true && !verifiedByUser) return
        if (existing != null && verifiedByUser) {
            dao.verifyAndUpdate(normalized, offProductId, offProductName, kcalPer100g, proteinPer100g, carbsPer100g, fatPer100g, fiberPer100g)
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
                    fiberPer100g = fiberPer100g,
                    isVerifiedByUser = verifiedByUser
                )
            )
        }
    }

    /** Heuristik: Alias sieht aus wie Anweisungstext / Hashtag / Satzfragment, kein Lebensmittelname. */
    private fun looksLikeJunkAlias(normalized: String): Boolean {
        if (normalized.length > 60) return true
        if (normalized.startsWith("#") || normalized.contains("#")) return true
        if (normalized.startsWith(".") || normalized.startsWith("-") || normalized.startsWith("(")) return true
        // Viele Wörter → oft Anweisungssätze
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size >= 6) return true
        // Typische Anweisungs-Wörter
        val instructionHints = listOf(
            "divide", "finish", "let set", "chill", "refrigerate", "bake", "preheat",
            "ingredients for", "servings", "portionen", "nach wahl", "oder nach",
            "for 4", "for 5", "zwischen", "zwischen den"
        )
        if (instructionHints.any { normalized.contains(it) }) return true
        return false
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
            fatPer100g = (food.fat ?: 0f).toDouble(),
            fiberPer100g = (food.fiber ?: 0f).toDouble()
        )
    }

    suspend fun exportAll(): List<GlobalIngredientMatch> = dao.getAllOnce()

    /** Import: upsert aller Einträge (REPLACE). Gibt Anzahl geschriebener Zeilen zurück. */
    suspend fun importAll(items: List<GlobalIngredientMatch>): Int {
        invalidateIndex()
        var n = 0
        for (item in items) {
            val normalized = item.normalizedName.ifBlank { item.originalName.trim().lowercase() }
            if (normalized.isBlank()) continue
            dao.upsert(
                item.copy(
                    normalizedName = normalized,
                    originalName = item.originalName.trim().ifBlank { normalized },
                    isVerifiedByUser = true
                )
            )
            n++
        }
        return n
    }

    suspend fun delete(normalizedName: String) {
        invalidateIndex()
        dao.deleteByName(normalizedName.trim().lowercase())
    }

    fun getAllVerified() = dao.getAllVerified()
}
