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

    /** Einheiten-Vorschlag für die Prüfansicht von Zutaten (z. B. "2 Scheiben", "1 EL", "3 Scoops"). */
    private data class UnitRule(val keys: List<String>, val presets: List<Preset>)

    private val UNIT_RULES: List<UnitRule> = listOf(
        UnitRule(listOf("proteinpulver", "protein powder", "whey", "casein", "eiweisspulver", "eiweißpulver"),
            listOf(Preset("1 Scoop", 30f), Preset("2 Scoops", 60f), Preset("3 Scoops", 90f))),
        UnitRule(listOf("olivenöl", "rapsöl", "sonnenblumenöl", "kokosöl", "speiseöl", "öl,", "oil"),
            listOf(Preset("1 TL", 5f), Preset("1 EL", 14f), Preset("2 EL", 28f))),
        UnitRule(listOf("butter", "margarine"),
            listOf(Preset("1 TL", 5f), Preset("1 EL", 15f), Preset("1 Portion", 10f))),
        UnitRule(listOf("honig", "ahornsirup", "agavendicksaft"),
            listOf(Preset("1 TL", 7f), Preset("1 EL", 21f))),
        UnitRule(listOf("zucker"),
            listOf(Preset("1 TL", 4f), Preset("1 EL", 12f))),
        UnitRule(listOf("toast"),
            listOf(Preset("1 Scheibe", 30f), Preset("2 Scheiben", 60f))),
        UnitRule(listOf("brot"),
            listOf(Preset("1 Scheibe", 40f), Preset("2 Scheiben", 80f), Preset("3 Scheiben", 120f))),
        UnitRule(listOf("frischkäse", "hüttenkäse", "streichkäse"),
            listOf(Preset("1 EL", 20f), Preset("1 Portion", 30f))),
        UnitRule(listOf("schokolade"),
            listOf(Preset("1 Rippe", 10f), Preset("1 Tafel", 100f))),
        UnitRule(listOf("käse", "gouda", "emmentaler", "gruyère", "cheddar"),
            listOf(Preset("1 Scheibe", 25f), Preset("2 Scheiben", 50f), Preset("1 Portion", 30f))),
        UnitRule(listOf("schinken", "salami", "bündnerfleisch", "aufschnitt", "putenbrust"),
            listOf(Preset("1 Scheibe", 20f), Preset("2 Scheiben", 40f), Preset("3 Scheiben", 60f))),
        UnitRule(listOf("haferflocken", "müesli", "müsli", "cornflakes", "granola"),
            listOf(Preset("1 EL", 8f), Preset("1 Handvoll", 30f), Preset("1 Tasse", 90f))),
        UnitRule(listOf("milch", "haferdrink", "hafer drink", "sojadrink", "mandeldrink"),
            listOf(Preset("1 Schluck", 50f), Preset("1 Glas", 200f), Preset("1 Tasse", 250f))),
        UnitRule(listOf("joghurt", "quark", "skyr"),
            listOf(Preset("1 EL", 20f), Preset("1 Becher", 150f), Preset("1 Schale", 250f))),
        UnitRule(listOf("nüsse", "nuss", "mandel", "cashew", "erdnuss"),
            listOf(Preset("1 Handvoll", 30f), Preset("10 Stück", 15f))),
        UnitRule(listOf("ei", "eier"),
            listOf(Preset("1 Ei", 55f), Preset("2 Eier", 110f), Preset("3 Eier", 165f)))
    )

    /**
     * Typische Einheiten für eine Zutat (Scheibe, EL, Scoop, Glas …) plus Klein/Mittel/Gross bei Obst/Gemüse.
     * Bewusst grobe Richtwerte; die Menge bleibt per ±/Eingabe frei anpassbar. Leere Liste = kein Vorschlag.
     */
    fun forIngredient(name: String): List<Preset> {
        val n = " " + name.lowercase().trim() + " "
        // "ei" nur als ganzes Wort (nicht in "reis", "eiweiss", "weizen")
        val rule = UNIT_RULES.firstOrNull { r ->
            r.keys.any { k ->
                if (k == "ei" || k == "eier") Regex("(^|[^a-zäöüß])$k($|[^a-zäöüß])").containsMatchIn(n) else k in n
            }
        }
        if (rule != null) return rule.presets
        return when {
            FRUIT_KEYWORDS.any { n.contains(it) }     -> FRUIT_PRESETS
            VEGETABLE_KEYWORDS.any { n.contains(it) } -> VEGETABLE_PRESETS
            else -> emptyList()
        }
    }

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
