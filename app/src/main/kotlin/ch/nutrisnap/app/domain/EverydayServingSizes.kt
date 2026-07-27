package ch.nutrisnap.app.domain

/**
 * Alltagseinheiten fuer die visuelle Portionsauswahl ("das sieht aus wie eine Handvoll"),
 * als Ergaenzung zu [FoodPortionPresets]. Jede Einheit liefert ein plausibles Gramm-
 * Aequivalent, damit Nutzer nicht woegen muessen. [emoji] dient als leichtgewichtiges
 * Referenzbild ohne zusaetzliche Bild-Assets.
 */
object EverydayServingSizes {

    data class ServingUnit(val emoji: String, val label: String, val grams: Float)

    val ALL: List<ServingUnit> = listOf(
        ServingUnit("🥄", "Teelöffel", 5f),
        ServingUnit("🥄", "Esslöffel", 15f),
        ServingUnit("🤏", "Prise", 1f),
        ServingUnit("✊", "Handvoll (klein)", 30f),
        ServingUnit("🤲", "Handvoll (gross)", 60f),
        ServingUnit("☕", "Tasse", 150f),
        ServingUnit("🍵", "Grosse Tasse", 240f),
        ServingUnit("🥛", "Glas", 200f),
        ServingUnit("🍶", "Schüssel (klein)", 250f),
        ServingUnit("🥣", "Schüssel (gross)", 400f),
        ServingUnit("🍞", "Scheibe Brot", 30f),
        ServingUnit("🧀", "Scheibe Käse", 20f),
        ServingUnit("🥓", "Scheibe Aufschnitt", 15f),
        ServingUnit("🥫", "Dose (klein)", 200f),
        ServingUnit("🥫", "Dose (gross)", 400f),
        ServingUnit("🍫", "Riegel", 50f),
        ServingUnit("🥖", "Baguette-Stück", 60f),
        ServingUnit("🍚", "Portion Reis/Pasta (gekocht)", 180f),
        ServingUnit("🥔", "Kartoffel (mittel)", 150f),
        ServingUnit("🥚", "Ei (M)", 53f),
        ServingUnit("🧈", "Klecks Butter/Aufstrich", 10f),
        ServingUnit("🍕", "Stück Pizza", 120f),
        ServingUnit("🥨", "Brezel", 80f),
        ServingUnit("🍪", "Keks", 15f),
        ServingUnit("🧁", "Muffin/Cupcake", 60f),
        ServingUnit("🍩", "Donut", 55f),
        ServingUnit("🥜", "Handvoll Nüsse", 30f),
        ServingUnit("🍺", "Glas Bier (3dl)", 300f),
        ServingUnit("🍷", "Glas Wein (1dl)", 100f),
        ServingUnit("🥤", "Becher Softdrink (2dl)", 200f),
        ServingUnit("🍦", "Kugel Glace", 60f),
        ServingUnit("🧇", "Waffel", 75f),
        ServingUnit("🥞", "Pfannkuchen", 60f),
        ServingUnit("🌭", "Hotdog-Wurst", 75f),
        ServingUnit("🍗", "Hühnerkeule", 120f),
        ServingUnit("🥩", "Fleischportion (handflächengross)", 150f),
        ServingUnit("🐟", "Fischfilet-Portion", 130f),
        ServingUnit("🍔", "Burger-Patty", 100f),
        ServingUnit("🌮", "Taco", 90f),
        ServingUnit("🧆", "Falafel-Kugel", 20f)
    )
}
