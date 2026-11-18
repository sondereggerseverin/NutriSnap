package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.InventoryItem
import ch.nutrisnap.app.data.repository.IngredientKey

/**
 * Gleicht Rezeptzutaten mit dem Vorrat ab und schlägt vor, was nach dem Kochen
 * abgebucht werden soll («Made it» → Vorrat aktualisieren).
 *
 * Bewusst konservativ: Nur Mengen, die sich sicher umrechnen lassen (g/kg/ml/l bzw.
 * Stück bei Stück-Angabe im Rezept), werden automatisch vorgeschlagen. Bei Packung/Dose
 * oder unklaren Einheiten ist [Deduction.measurable] false – die Auswahl bleibt dann beim
 * Nutzer und es wird nie etwas stillschweigend abgezogen.
 */
object InventoryMatcher {

    data class Deduction(
        val inventoryId: Long,
        val inventoryName: String,
        val ingredientName: String,
        val unit: String,
        val currentQty: Float,
        /** Vorgeschlagene neue Menge (>= 0), in der Einheit des Vorrats. */
        val newQty: Float,
        /** true = Menge sicher berechnet; false = nur Vorschlag (-1 bzw. aufgebraucht). */
        val measurable: Boolean
    ) {
        val usedQty: Float get() = (currentQty - newQty).coerceAtLeast(0f)
        val fullyUsed: Boolean get() = newQty <= 0.0001f
    }

    private val WEIGHT_UNITS = mapOf(
        "g" to 1f, "gr" to 1f, "gramm" to 1f, "ml" to 1f,
        "kg" to 1000f, "l" to 1000f, "liter" to 1000f
    )
    private val COUNT_UNITS = setOf("stk", "stk.", "stück", "stueck", "st", "x", "stuck")

    private fun stem(word: String): String {
        var w = word
        for (suffix in listOf("en", "n", "e", "s")) {
            if (w.length > 4 && w.endsWith(suffix)) { w = w.dropLast(suffix.length); break }
        }
        return w
    }

    /** Vergleichs-Token über die zentrale [IngredientKey]-Normalisierung + einfache Plural-Stämme. */
    private fun tokens(raw: String): Set<String> =
        IngredientKey.of(raw).split(' ')
            .filter { it.length >= 2 && !it.all(Char::isDigit) }
            .map { stem(it) }
            .toSet()

    /**
     * Zwei Namen passen, wenn alle Wortstämme des kürzeren Namens im längeren vorkommen
     * («Zwiebel» ↔ «Rote Zwiebeln», «Milch» ↔ «Milch 3.5%»). Bewusst konservativ:
     * «Käse» passt NICHT auf «Frischkäse».
     */
    fun namesMatch(a: String, b: String): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val (small, big) = if (ta.size <= tb.size) ta to tb else tb to ta
        return big.containsAll(small)
    }

    /**
     * @param ingredientLines Zutatenzeilen des Rezepts (eine pro Zeile)
     * @param inventory aktueller Vorrat
     * @param factor Skalierung (z. B. gekochte Portionen / Rezeptportionen), Default 1
     */
    fun match(
        ingredientLines: List<String>,
        inventory: List<InventoryItem>,
        factor: Float = 1f
    ): List<Deduction> {
        if (inventory.isEmpty()) return emptyList()
        // Verbrauch pro Vorrats-Id aufsummieren (mehrere Zeilen können dieselbe Position treffen)
        val remaining = inventory.associate { it.id to it.quantity }.toMutableMap()
        val measurableFlag = mutableMapOf<Long, Boolean>()
        val firstIngredient = mutableMapOf<Long, String>()

        for (line in ingredientLines) {
            val parsed = RecipeNutritionAnalyzer.parseIngredientLine(line) ?: continue
            val item = inventory.firstOrNull { namesMatch(parsed.name, it.name) } ?: continue
            val unit = item.unit.trim().lowercase()
            val left = remaining[item.id] ?: continue
            if (left <= 0f) continue
            firstIngredient.putIfAbsent(item.id, parsed.name)

            when {
                unit in WEIGHT_UNITS -> {
                    val perUnit = WEIGHT_UNITS.getValue(unit)
                    val usedInUnit = (parsed.amountG * factor) / perUnit
                    remaining[item.id] = (left - usedInUnit).coerceAtLeast(0f)
                    measurableFlag[item.id] = true
                }
                unit in COUNT_UNITS && parsed.count != null -> {
                    remaining[item.id] = (left - parsed.count * factor).coerceAtLeast(0f)
                    measurableFlag[item.id] = true
                }
                else -> {
                    // Packung/Dose/unklar: Vorschlag -1, aber nicht als sicher markiert
                    if (measurableFlag[item.id] != true) {
                        remaining[item.id] = (item.quantity - 1f).coerceAtLeast(0f)
                        measurableFlag[item.id] = false
                    }
                }
            }
        }

        return inventory.mapNotNull { item ->
            val newQty = remaining[item.id] ?: return@mapNotNull null
            val flag = measurableFlag[item.id] ?: return@mapNotNull null
            if (newQty >= item.quantity - 0.0001f) return@mapNotNull null
            Deduction(
                inventoryId = item.id,
                inventoryName = item.name,
                ingredientName = firstIngredient[item.id] ?: item.name,
                unit = item.unit,
                currentQty = item.quantity,
                newQty = newQty,
                measurable = flag
            )
        }
    }
}
