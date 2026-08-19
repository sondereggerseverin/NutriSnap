package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.IngredientMatch

/**
 * Abgleich IngredientMatch ↔ Zutatentext (Soft-Delete wenn Zeile fehlt).
 * Pure Logik – testbar ohne Room/ViewModel.
 */
object IngredientMatchSync {

    fun core(s: String): String =
        s.lowercase()
            .trim()
            .trimStart('•', '-', '*', ' ')
            .replace(Regex("""^\d+[.,]?\d*\s*(g|kg|ml|l|el|tl|tbsp|tsp|cup|oz)?\s*"""), "")
            .trim()

    /**
     * Liefert die Match-IDs, die im neuen Zutatentext nicht mehr vorkommen
     * und daher als gelöscht markiert werden sollen.
     */
    fun matchIdsToSoftDelete(
        existing: List<IngredientMatch>,
        newIngredients: String
    ): List<Long> {
        val textCores = newIngredients.lines()
            .map { core(it) }
            .filter { it.length >= 2 }
            .toSet()
        if (textCores.isEmpty()) return emptyList()
        return existing.mapNotNull { m ->
            if (m.isDeleted) return@mapNotNull null
            val rawC = core(m.ingredientRaw)
            val nameC = core(m.ingredientName)
            val stillPresent = textCores.any { t ->
                (rawC.length >= 3 && (t.contains(rawC) || rawC.contains(t))) ||
                    (nameC.length >= 3 && (t.contains(nameC) || nameC.contains(t)))
            }
            if (!stillPresent) m.id else null
        }
    }
}
