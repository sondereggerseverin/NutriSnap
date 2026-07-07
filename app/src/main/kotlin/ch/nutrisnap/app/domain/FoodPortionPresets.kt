package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.FoodItem

/**
 * Portionsvorschläge für die Mengen-Eingabe beim Tagebuch-Eintrag.
 * Zwei Quellen:
 * - Fixe Verpackungsgrösse: kommt von OpenFoodFacts (`serving_size`), z.B. "1 Portion (50g)"
 *   bei einem Proteinriegel — siehe [FoodItem.servingSize]/[FoodItem.servingUnit].
 * - Generische Grössen für frisches Obst/Gemüse (Klein/Mittel/Gross), analog zu gängigen
 *   Trackern: da es keine verlässliche Gewichtsquelle pro einzelner Sorte gibt, wird bewusst
 *   mit groben, plausiblen Richtwerten pro Kategorie gearbeitet statt pro Lebensmittel.
 */
object FoodPortionPresets {

    data class Preset(val label: String, val grams: Float)

    private val FRUIT_KEYWORDS = setOf(
        "apfel", "banane", "orange", "mandarine", "birne", "pfirsich", "aprikose",
        "kiwi", "pflaume", "nektarine", "grapefruit", "mango", "ananas", "melone",
        "traube", "zitrone", "limette", "granatapfel", "feige", "avocado"
    )
    private val VEGETABLE_KEYWORDS = setOf(
        "karotte", "rüebli", "tomate", "gurke", "zucchini", "peperoni", "paprika",
        "kartoffel", "zwiebel", "aubergine", "brokkoli", "blumenkohl", "kohlrabi",
        "rande", "sellerie", "lauch", "fenchel", "rettich", "radieschen"
    )

    private val FRUIT_PRESETS     = listOf(Preset("Klein", 75f), Preset("Mittel", 150f), Preset("Gross", 200f))
    private val VEGETABLE_PRESETS = listOf(Preset("Klein", 50f), Preset("Mittel", 100f), Preset("Gross", 150f))

    fun forFood(food: FoodItem): List<Preset> {
        val presets = mutableListOf<Preset>()
        if (food.servingSize != 100f || food.servingUnit != "g") {
            presets += Preset("1 Portion (${food.servingSize.toInt()}${food.servingUnit})", food.servingSize)
        }
        val n = food.name.lowercase()
        when {
            FRUIT_KEYWORDS.any { n.contains(it) }     -> presets += FRUIT_PRESETS
            VEGETABLE_KEYWORDS.any { n.contains(it) } -> presets += VEGETABLE_PRESETS
        }
        return presets
    }
}
