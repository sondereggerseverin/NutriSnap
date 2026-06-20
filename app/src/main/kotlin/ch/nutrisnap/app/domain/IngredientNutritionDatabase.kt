package ch.nutrisnap.app.domain

/**
 * Curated nutrition reference (per 100g, raw/uncooked unless noted) for common
 * recipe ingredients. This is the PRIMARY lookup source for [RecipeNutritionAnalyzer]
 * because OpenFoodFacts is optimized for branded/packaged products and frequently
 * has no good match for generic ingredients like "Hähnchenbrustfilet" or "Zwiebel" —
 * which previously caused recipes to show "0/14 Zutaten gefunden".
 *
 * Keys are lowercase, normalized search terms (German + English). Lookup is done
 * via substring matching, picking the longest/most specific matching key so that
 * e.g. "fettarmer joghurt" matches "fettarmer joghurt" before the generic "joghurt".
 */
object IngredientNutritionDatabase {

    data class Entry(
        val calories: Float,
        val protein:  Float,
        val carbs:    Float,
        val fat:      Float,
        val fiber:    Float = 0f
    )

    /** Per-100g values, mostly USDA / standard reference data. */
    private val entries: Map<String, Entry> = mapOf(

        // ── Poultry & Meat ──────────────────────────────────────────────────
        "hähnchenbrustfilet"   to Entry(120f, 22.5f, 0f,   2.6f),
        "hähnchenbrust"        to Entry(120f, 22.5f, 0f,   2.6f),
        "hühnerbrust"          to Entry(120f, 22.5f, 0f,   2.6f),
        "chicken breast"       to Entry(120f, 22.5f, 0f,   2.6f),
        "hähnchenschenkel"     to Entry(177f, 18.2f, 0f,  10.7f),
        "chicken thigh"        to Entry(177f, 18.2f, 0f,  10.7f),
        "hähnchen"             to Entry(150f, 21f,   0f,   7f),
        "pute"                 to Entry(120f, 24f,   0f,   1.7f),
        "turkey breast"        to Entry(120f, 24f,   0f,   1.7f),
        "rinderhack"           to Entry(254f, 17.2f, 0f,  20f),
        "ground beef"          to Entry(254f, 17.2f, 0f,  20f),
        "rindfleisch"          to Entry(217f, 26.1f, 0f,  12f),
        "beef"                 to Entry(217f, 26.1f, 0f,  12f),
        "schweinefleisch"      to Entry(242f, 27f,   0f,  14f),
        "pork"                 to Entry(242f, 27f,   0f,  14f),
        "hackfleisch gemischt" to Entry(259f, 17.5f, 0f,  21f),
        "speck"                to Entry(541f, 37f,   1.4f, 42f),
        "bacon"                to Entry(541f, 37f,   1.4f, 42f),
        "lammfleisch"          to Entry(294f, 16.6f, 0f,  25f),
        "lamb"                 to Entry(294f, 16.6f, 0f,  25f),

        // ── Fish & Seafood ───────────────────────────────────────────────────
        "lachs"                to Entry(208f, 20f,   0f,  13f),
        "salmon"               to Entry(208f, 20f,   0f,  13f),
        "thunfisch"            to Entry(116f, 26f,   0f,   1f),
        "tuna"                 to Entry(116f, 26f,   0f,   1f),
        "garnelen"             to Entry(99f,  24f,   0.2f, 0.3f),
        "shrimp"               to Entry(99f,  24f,   0.2f, 0.3f),
        "kabeljau"             to Entry(82f,  18f,   0f,   0.7f),
        "cod"                  to Entry(82f,  18f,   0f,   0.7f),

        // ── Eggs & Dairy ─────────────────────────────────────────────────────
        "ei"                    to Entry(155f, 13f,   1.1f, 11f),
        "eier"                  to Entry(155f, 13f,   1.1f, 11f),
        "egg"                   to Entry(155f, 13f,   1.1f, 11f),
        "eiweiss"               to Entry(52f,  10.9f, 0.7f, 0.2f),
        "egg white"             to Entry(52f,  10.9f, 0.7f, 0.2f),
        "fettarmer joghurt"     to Entry(56f,  4.5f,  6.5f, 1.5f),
        "magerjoghurt"          to Entry(45f,  5f,    4f,   0.2f),
        "griechischer joghurt"  to Entry(97f,  9f,    4f,   5f),
        "greek yogurt"          to Entry(97f,  9f,    4f,   5f),
        "joghurt"               to Entry(63f,  3.8f,  4.7f, 3.3f),
        "yogurt"                to Entry(63f,  3.8f,  4.7f, 3.3f),
        "frischkäse light"      to Entry(155f, 8f,    4f,  12f),
        "frischkäse"            to Entry(241f, 6f,    4f,  23f),
        "cream cheese"          to Entry(241f, 6f,    4f,  23f),
        "quark"                 to Entry(67f,  12f,   4f,   0.3f),
        "magerquark"            to Entry(67f,  12f,   4f,   0.2f),
        "hüttenkäse"            to Entry(98f,  11f,   3.4f, 4.3f),
        "cottage cheese"        to Entry(98f,  11f,   3.4f, 4.3f),
        "milch"                 to Entry(64f,  3.4f,  4.8f, 3.6f),
        "milk"                  to Entry(64f,  3.4f,  4.8f, 3.6f),
        "schlagsahne"           to Entry(292f, 2.1f,  3.4f, 31f),
        "sahne"                 to Entry(292f, 2.1f,  3.4f, 31f),
        "cream"                 to Entry(292f, 2.1f,  3.4f, 31f),
        "creme fraiche"         to Entry(290f, 2.4f,  3f,   30f),
        "butter"                to Entry(717f, 0.9f,  0.1f, 81f),
        "mozzarella"            to Entry(280f, 28f,   3.1f, 17f),
        "parmesan"              to Entry(392f, 35.8f, 4.1f, 26f),
        "feta"                  to Entry(264f, 14.2f, 4f,   21f),
        "cheddar"               to Entry(403f, 25f,   1.3f, 33f),
        "reibekäse"             to Entry(350f, 25f,   2f,   28f),

        // ── Grains, Rice, Pasta ──────────────────────────────────────────────
        "basmati reis"          to Entry(356f, 7.5f,  78f,  0.7f, 1.3f),
        "basmati rice"          to Entry(356f, 7.5f,  78f,  0.7f, 1.3f),
        "reis"                  to Entry(360f, 7f,    79f,  0.7f, 1.3f),
        "rice"                  to Entry(360f, 7f,    79f,  0.7f, 1.3f),
        "vollkornreis"          to Entry(357f, 7.5f,  76f,  2.7f, 3.5f),
        "nudeln"                to Entry(360f, 12.5f, 71f,  1.5f, 3f),
        "pasta"                 to Entry(360f, 12.5f, 71f,  1.5f, 3f),
        "vollkornnudeln"        to Entry(348f, 14f,   66f,  2.5f, 8f),
        "couscous"              to Entry(376f, 12.8f, 77f,  0.6f, 5f),
        "quinoa"                to Entry(368f, 14.1f, 64f,  6.1f, 7f),
        "haferflocken"          to Entry(372f, 13.5f, 59f,  7f,   10f),
        "oats"                  to Entry(372f, 13.5f, 59f,  7f,   10f),
        "mehl"                  to Entry(364f, 10f,   76f,  1f,   2.7f),
        "flour"                 to Entry(364f, 10f,   76f,  1f,   2.7f),
        "vollkornmehl"          to Entry(340f, 13.2f, 72f,  2.5f, 10.7f),
        "brot"                  to Entry(265f, 9f,    49f,  3.2f, 2.7f),
        "bread"                 to Entry(265f, 9f,    49f,  3.2f, 2.7f),
        "vollkornbrot"          to Entry(247f, 13f,   41f,  3.4f, 7f),
        "tortilla"              to Entry(310f, 8f,    50f,  7f,   3f),
        "wrap"                  to Entry(310f, 8f,    50f,  7f,   3f),

        // ── Legumes ──────────────────────────────────────────────────────────
        "kichererbsen"          to Entry(164f, 8.9f,  27f,  2.6f, 7.6f),
        "chickpeas"             to Entry(164f, 8.9f,  27f,  2.6f, 7.6f),
        "linsen"                to Entry(116f, 9f,    20f,  0.4f, 7.9f),
        "lentils"               to Entry(116f, 9f,    20f,  0.4f, 7.9f),
        "schwarze bohnen"       to Entry(132f, 8.9f,  24f,  0.5f, 8.7f),
        "black beans"           to Entry(132f, 8.9f,  24f,  0.5f, 8.7f),
        "kidneybohnen"          to Entry(127f, 8.7f,  23f,  0.5f, 6.4f),
        "kidney beans"          to Entry(127f, 8.7f,  23f,  0.5f, 6.4f),
        "tofu"                  to Entry(76f,  8f,    1.9f, 4.8f, 0.3f),
        "edamame"               to Entry(121f, 11.9f, 8.9f, 5.2f, 5.2f),

        // ── Vegetables ───────────────────────────────────────────────────────
        "zwiebel"               to Entry(40f,  1.1f,  9.3f, 0.1f, 1.7f),
        "onion"                 to Entry(40f,  1.1f,  9.3f, 0.1f, 1.7f),
        "knoblauch"             to Entry(149f, 6.4f,  33f,  0.5f, 2.1f),
        "garlic"                to Entry(149f, 6.4f,  33f,  0.5f, 2.1f),
        "tomate"                to Entry(18f,  0.9f,  3.9f, 0.2f, 1.2f),
        "tomato"                to Entry(18f,  0.9f,  3.9f, 0.2f, 1.2f),
        "passierte tomaten"     to Entry(32f,  1.6f,  6f,   0.3f, 1.5f),
        "tomatenmark"           to Entry(82f,  4.3f,  18f,  0.5f, 4.1f),
        "tomato paste"          to Entry(82f,  4.3f,  18f,  0.5f, 4.1f),
        "paprika"               to Entry(31f,  1f,    6f,   0.3f, 2.1f),
        "bell pepper"           to Entry(31f,  1f,    6f,   0.3f, 2.1f),
        "zucchini"              to Entry(17f,  1.2f,  3.1f, 0.3f, 1f),
        "aubergine"             to Entry(25f,  1f,    6f,   0.2f, 3f),
        "eggplant"              to Entry(25f,  1f,    6f,   0.2f, 3f),
        "brokkoli"              to Entry(34f,  2.8f,  7f,   0.4f, 2.6f),
        "broccoli"              to Entry(34f,  2.8f,  7f,   0.4f, 2.6f),
        "spinat"                to Entry(23f,  2.9f,  3.6f, 0.4f, 2.2f),
        "spinach"               to Entry(23f,  2.9f,  3.6f, 0.4f, 2.2f),
        "karotte"               to Entry(41f,  0.9f,  10f,  0.2f, 2.8f),
        "möhre"                 to Entry(41f,  0.9f,  10f,  0.2f, 2.8f),
        "carrot"                to Entry(41f,  0.9f,  10f,  0.2f, 2.8f),
        "kartoffel"             to Entry(77f,  2f,    17f,  0.1f, 2.2f),
        "potato"                to Entry(77f,  2f,    17f,  0.1f, 2.2f),
        "süsskartoffel"         to Entry(86f,  1.6f,  20f,  0.1f, 3f),
        "sweet potato"          to Entry(86f,  1.6f,  20f,  0.1f, 3f),
        "champignon"            to Entry(22f,  3.1f,  3.3f, 0.3f, 1f),
        "mushroom"              to Entry(22f,  3.1f,  3.3f, 0.3f, 1f),
        "gurke"                 to Entry(15f,  0.7f,  3.6f, 0.1f, 0.5f),
        "cucumber"              to Entry(15f,  0.7f,  3.6f, 0.1f, 0.5f),
        "salat"                 to Entry(15f,  1.4f,  2.9f, 0.2f, 1.3f),
        "lettuce"               to Entry(15f,  1.4f,  2.9f, 0.2f, 1.3f),
        "mais"                  to Entry(86f,  3.3f,  19f,  1.4f, 2.7f),
        "corn"                  to Entry(86f,  3.3f,  19f,  1.4f, 2.7f),
        "erbsen"                to Entry(81f,  5.4f,  14f,  0.4f, 5.1f),
        "peas"                  to Entry(81f,  5.4f,  14f,  0.4f, 5.1f),

        // ── Fruits ───────────────────────────────────────────────────────────
        "banane"                to Entry(89f,  1.1f,  23f,  0.3f, 2.6f),
        "banana"                to Entry(89f,  1.1f,  23f,  0.3f, 2.6f),
        "apfel"                 to Entry(52f,  0.3f,  14f,  0.2f, 2.4f),
        "apple"                 to Entry(52f,  0.3f,  14f,  0.2f, 2.4f),
        "zitrone"               to Entry(29f,  1.1f,  9.3f, 0.3f, 2.8f),
        "lemon"                 to Entry(29f,  1.1f,  9.3f, 0.3f, 2.8f),
        "limette"               to Entry(30f,  0.7f,  11f,  0.2f, 2.8f),
        "lime"                  to Entry(30f,  0.7f,  11f,  0.2f, 2.8f),
        "avocado"               to Entry(160f, 2f,    8.5f, 14.7f,6.7f),
        "beeren"                to Entry(43f,  0.7f,  10f,  0.3f, 2.4f),
        "berries"               to Entry(43f,  0.7f,  10f,  0.3f, 2.4f),

        // ── Oils, Fats, Nuts ─────────────────────────────────────────────────
        "olivenöl"              to Entry(884f, 0f,    0f,  100f),
        "olive oil"             to Entry(884f, 0f,    0f,  100f),
        "öl"                    to Entry(884f, 0f,    0f,  100f),
        "oil"                   to Entry(884f, 0f,    0f,  100f),
        "kokosöl"               to Entry(862f, 0f,    0f,  100f),
        "coconut oil"           to Entry(862f, 0f,    0f,  100f),
        "erdnussbutter"         to Entry(588f, 25f,   20f,  50f, 6f),
        "peanut butter"         to Entry(588f, 25f,   20f,  50f, 6f),
        "mandeln"               to Entry(579f, 21f,   22f,  50f, 12.5f),
        "almonds"               to Entry(579f, 21f,   22f,  50f, 12.5f),
        "walnüsse"              to Entry(654f, 15f,   14f,  65f, 6.7f),
        "walnuts"               to Entry(654f, 15f,   14f,  65f, 6.7f),
        "erdnüsse"              to Entry(567f, 26f,   16f,  49f, 8.5f),
        "peanuts"               to Entry(567f, 26f,   16f,  49f, 8.5f),
        "chiasamen"             to Entry(486f, 17f,   42f,  31f, 34f),
        "chia seeds"            to Entry(486f, 17f,   42f,  31f, 34f),
        "sesam"                 to Entry(573f, 18f,   23f,  50f, 12f),
        "sesame"                to Entry(573f, 18f,   23f,  50f, 12f),
        "kokosmilch"            to Entry(230f, 2.3f,  5.5f, 24f, 2.2f),
        "coconut milk"          to Entry(230f, 2.3f,  5.5f, 24f, 2.2f),

        // ── Sauces, Condiments, Sweeteners ───────────────────────────────────
        "sojasauce"             to Entry(53f,  8f,    4.9f, 0.6f),
        "soy sauce"             to Entry(53f,  8f,    4.9f, 0.6f),
        "honig"                 to Entry(304f, 0.3f,  82f,  0f),
        "honey"                 to Entry(304f, 0.3f,  82f,  0f),
        "ahornsirup"            to Entry(260f, 0f,    67f,  0.2f),
        "maple syrup"           to Entry(260f, 0f,    67f,  0.2f),
        "ketchup"               to Entry(112f, 1.2f,  26f,  0.2f, 0.4f),
        "senf"                  to Entry(66f,  4.4f,  6f,   3.3f, 3.3f),
        "mustard"               to Entry(66f,  4.4f,  6f,   3.3f, 3.3f),
        "mayonnaise"            to Entry(680f, 1f,    1f,   75f),
        "zucker"                to Entry(387f, 0f,    100f, 0f),
        "sugar"                 to Entry(387f, 0f,    100f, 0f),
        "balsamico"             to Entry(88f,  0.5f,  17f,  0f),

        // ── Spices, Herbs, Seasonings (negligible macros, but for completeness) ──
        "salz"                  to Entry(0f, 0f, 0f, 0f),
        "salt"                  to Entry(0f, 0f, 0f, 0f),
        "pfeffer"               to Entry(251f, 10f, 64f, 3.3f, 25f),
        "pepper"                to Entry(251f, 10f, 64f, 3.3f, 25f),
        "paprikapulver"         to Entry(282f, 14f, 54f, 13f, 35f),
        "paprika powder"        to Entry(282f, 14f, 54f, 13f, 35f),
        "kreuzkümmel"           to Entry(375f, 18f, 44f, 22f, 11f),
        "cumin"                 to Entry(375f, 18f, 44f, 22f, 11f),
        "kurkuma"               to Entry(354f, 8f,  65f, 10f, 21f),
        "turmeric"              to Entry(354f, 8f,  65f, 10f, 21f),
        "garam masala"          to Entry(379f, 13f, 57f, 15f, 22f),
        "chilipulver"           to Entry(282f, 13f, 50f, 14f, 35f),
        "chili powder"          to Entry(282f, 13f, 50f, 14f, 35f),
        "zimt"                  to Entry(247f, 4f,  81f, 1.2f,53f),
        "cinnamon"              to Entry(247f, 4f,  81f, 1.2f,53f),
        "oregano"               to Entry(265f, 9f,  69f, 4.3f,43f),
        "basilikum"             to Entry(23f,  3.2f,2.7f,0.6f, 1.6f),
        "basil"                 to Entry(23f,  3.2f,2.7f,0.6f, 1.6f),
        "koriander"             to Entry(23f,  2.1f,3.7f,0.5f, 2.8f),
        "cilantro"              to Entry(23f,  2.1f,3.7f,0.5f, 2.8f),
        "coriander"             to Entry(23f,  2.1f,3.7f,0.5f, 2.8f),
        "petersilie"            to Entry(36f,  3f,  6.3f,0.8f, 3.3f),
        "parsley"               to Entry(36f,  3f,  6.3f,0.8f, 3.3f),
        "ingwer"                to Entry(80f,  1.8f,18f, 0.8f, 2f),
        "ginger"                to Entry(80f,  1.8f,18f, 0.8f, 2f),
        "knoblauchpulver"       to Entry(331f, 17f, 73f, 0.7f,9f),
        "zwiebelpulver"         to Entry(341f, 10f, 79f, 1f,  15f),
        "brühe"                 to Entry(5f,   0.5f,0.5f,0.1f),
        "broth"                 to Entry(5f,   0.5f,0.5f,0.1f),

        // ── Protein / Fitness products ────────────────────────────────────────
        "proteinpasta"          to Entry(340f, 32f,  42f,  3f,  8f),
        "protein pasta"         to Entry(340f, 32f,  42f,  3f,  8f),
        "proteinpulver"         to Entry(380f, 75f,  10f,  5f),
        "protein powder"        to Entry(380f, 75f,  10f,  5f),
        "proteinriegel"         to Entry(350f, 30f,  40f,  8f),
        "protein bar"           to Entry(350f, 30f,  40f,  8f),
        "whey"                  to Entry(370f, 75f,  8f,   4f),

        // ── Dairy alternatives & Fermented ───────────────────────────────────
        "skyr"                  to Entry(63f,  11f,  4f,   0.2f),
        "skyr alternativ"       to Entry(63f,  11f,  4f,   0.2f),
        "skyr-alternative"      to Entry(63f,  11f,  4f,   0.2f),
        "skyr alternative"      to Entry(63f,  11f,  4f,   0.2f),
        "sojamilch"             to Entry(33f,  3.3f, 2.8f, 1.7f),
        "soy milk"              to Entry(33f,  3.3f, 2.8f, 1.7f),
        "hafermilch"            to Entry(44f,  1f,   7.7f, 1.5f),
        "oat milk"              to Entry(44f,  1f,   7.7f, 1.5f),
        "mandeldrink"           to Entry(20f,  0.5f, 2.5f, 1.1f),
        "almond milk"           to Entry(20f,  0.5f, 2.5f, 1.1f),

        // ── Meat alternatives ─────────────────────────────────────────────────
        "veganes hack"          to Entry(130f, 15f,  5f,   5f,  2f),
        "vegan hack"            to Entry(130f, 15f,  5f,   5f,  2f),
        "erbsenprotein hack"    to Entry(135f, 17f,  4f,   5f,  2f),
        "beyond meat"           to Entry(225f, 17f,  5f,   14f),
        "tempeh"                to Entry(193f, 19f,  9f,   11f, 4.6f),
        "seitan"                to Entry(142f, 25f,  5.4f, 2f,  0.6f),

        // ── Vegetables (additional) ───────────────────────────────────────────
        "lauchzwiebel"          to Entry(32f,  1.8f, 7.3f, 0.2f, 2.6f),
        "lauchzwiebeln"         to Entry(32f,  1.8f, 7.3f, 0.2f, 2.6f),
        "frühlingszwiebel"      to Entry(32f,  1.8f, 7.3f, 0.2f, 2.6f),
        "spring onion"          to Entry(32f,  1.8f, 7.3f, 0.2f, 2.6f),
        "green onion"           to Entry(32f,  1.8f, 7.3f, 0.2f, 2.6f),
        "scallion"              to Entry(32f,  1.8f, 7.3f, 0.2f, 2.6f),
        "gewürzgurke"           to Entry(14f,  0.6f, 2.4f, 0.2f, 0.8f),
        "gewürzgurken"          to Entry(14f,  0.6f, 2.4f, 0.2f, 0.8f),
        "pickle"                to Entry(14f,  0.6f, 2.4f, 0.2f, 0.8f),
        "cherrytomaten"         to Entry(18f,  0.9f, 3.9f, 0.2f, 1.2f),
        "cherry tomatoes"       to Entry(18f,  0.9f, 3.9f, 0.2f, 1.2f),
        "rucola"                to Entry(25f,  2.6f, 3.7f, 0.7f, 1.6f),
        "rocket"                to Entry(25f,  2.6f, 3.7f, 0.7f, 1.6f),
        "arugula"               to Entry(25f,  2.6f, 3.7f, 0.7f, 1.6f),
        "eisbergsalat"          to Entry(14f,  0.9f, 3f,   0.1f, 1.2f),
        "fenchel"               to Entry(31f,  1.2f, 7.3f, 0.2f, 3.1f),
        "fennel"                to Entry(31f,  1.2f, 7.3f, 0.2f, 3.1f),
        "sellerie"              to Entry(16f,  0.7f, 3.5f, 0.2f, 1.6f),
        "celery"                to Entry(16f,  0.7f, 3.5f, 0.2f, 1.6f),
        "pak choi"              to Entry(13f,  1.5f, 2.2f, 0.2f, 1f),
        "bok choy"              to Entry(13f,  1.5f, 2.2f, 0.2f, 1f),

        // ── Sauces & Condiments (additional) ─────────────────────────────────
        "worcestershire"        to Entry(78f,  1f,   18f,  0.1f),
        "dijon"                 to Entry(66f,  4.4f, 6f,   3.3f),
        "hot sauce"             to Entry(15f,  0.8f, 3f,   0.2f),
        "sambal"                to Entry(60f,  1.8f, 8f,   2.5f),
        "sriracha"              to Entry(93f,  3f,   15f,  1.5f),
        "tahini"                to Entry(595f, 17f,  21f,  54f,  9.3f),
        "hummus"                to Entry(177f, 8f,   14f,  11f,  6f),
        "pesto"                 to Entry(490f, 6f,   5f,   49f),
        "tomatensosse"          to Entry(45f,  1.5f, 8f,   1f,   2f),
        "tomato sauce"          to Entry(45f,  1.5f, 8f,   1f,   2f),

        // ── Nuts & Seeds (additional) ─────────────────────────────────────────
        "cashews"               to Entry(553f, 18f,  30f,  44f,  3.3f),
        "cashewnüsse"           to Entry(553f, 18f,  30f,  44f,  3.3f),
        "sonnenblumenkerne"     to Entry(584f, 21f,  20f,  51f,  8.6f),
        "sunflower seeds"       to Entry(584f, 21f,  20f,  51f,  8.6f),
        "kürbiskerne"           to Entry(559f, 30f,  11f,  49f,  6f),
        "pumpkin seeds"         to Entry(559f, 30f,  11f,  49f,  6f),
        "leinsamen"             to Entry(534f, 18f,  29f,  42f,  27f),
        "flaxseed"              to Entry(534f, 18f,  29f,  42f,  27f),
        "hanfsamen"             to Entry(553f, 32f,  8.7f, 49f,  4f),
        "hemp seeds"            to Entry(553f, 32f,  8.7f, 49f,  4f),

        // ── Sweeteners & Baking ───────────────────────────────────────────────
        "agavensirup"           to Entry(310f, 0.3f, 75f,  0f),
        "agave"                 to Entry(310f, 0.3f, 75f,  0f),
        "stevia"                to Entry(0f,   0f,   0f,   0f),
        "backpulver"            to Entry(53f,  0f,   28f,  0f),
        "baking powder"         to Entry(53f,  0f,   28f,  0f),
        "natron"                to Entry(0f,   0f,   0f,   0f),
        "baking soda"           to Entry(0f,   0f,   0f,   0f),
        "vanille"               to Entry(288f, 0.1f, 13f,  0.1f),
        "vanilla"               to Entry(288f, 0.1f, 13f,  0.1f),
        "kakao"                 to Entry(228f, 20f,  58f,  14f,  33f),
        "cocoa"                 to Entry(228f, 20f,  58f,  14f,  33f),
        "dunkle schokolade"     to Entry(546f, 5f,   60f,  31f,  11f),
        "dark chocolate"        to Entry(546f, 5f,   60f,  31f,  11f),
        "schokolade"            to Entry(535f, 5f,   56f,  33f,  3f),

        // ── Beverages & Liquids ───────────────────────────────────────────────
        "kaffee"                to Entry(1f,   0.1f, 0f,   0f),
        "coffee"                to Entry(1f,   0.1f, 0f,   0f),
        "wasser"                to Entry(0f,   0f,   0f,   0f),
        "water"                 to Entry(0f,   0f,   0f,   0f),
        "zitronensaft"          to Entry(22f,  0.4f, 6.9f, 0.2f),
        "lemon juice"           to Entry(22f,  0.4f, 6.9f, 0.2f),
        "orangensaft"           to Entry(45f,  0.7f, 10f,  0.2f),
        "orange juice"          to Entry(45f,  0.7f, 10f,  0.2f),
        "apfelessig"            to Entry(21f,  0f,   0.9f, 0f),
        "apple cider vinegar"   to Entry(21f,  0f,   0.9f, 0f),
        "essig"                 to Entry(18f,  0f,   0.6f, 0f),
        "vinegar"               to Entry(18f,  0f,   0.6f, 0f)
    )

    /** Keys sorted longest-first so the most specific entry wins. */
    private val sortedKeys: List<String> by lazy { entries.keys.sortedByDescending { it.length } }

    /**
     * Looks up nutrition for a free-text ingredient name. Returns the most
     * specific (longest) matching entry, or null if nothing matches.
     */
    fun lookup(searchTerm: String): Entry? {
        val normalized = searchTerm.trim().lowercase()
        if (normalized.isBlank()) return null

        // Exact match first
        entries[normalized]?.let { return it }

        // Substring match — search term contains a known ingredient name
        for (key in sortedKeys) {
            if (normalized.contains(key)) return entries[key]
        }
        // Fallback: known ingredient name contains the (short) search term
        for (key in sortedKeys) {
            if (key.contains(normalized) && normalized.length >= 4) return entries[key]
        }
        return null
    }
}
