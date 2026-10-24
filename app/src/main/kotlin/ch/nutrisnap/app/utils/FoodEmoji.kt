package ch.nutrisnap.app.utils

/**
 * Emoji-Platzhalter für Tagebuch-Einträge ohne Foto (Produktbild, Rezeptbild, Scan-Foto).
 * Reine Schlüsselwort-Zuordnung auf dem Lebensmittelnamen (Deutsch/Englisch), die
 * Reihenfolge ist wichtig: spezifische Treffer stehen vor allgemeinen.
 * Kein Treffer → null (die UI zeigt dann das neutrale Besteck-Icon).
 */
object FoodEmoji {

    private data class Rule(val emoji: String, val contains: List<String> = emptyList(), val wordStart: List<String> = emptyList())

    private val RULES: List<Rule> = listOf(
        Rule("🍕", listOf("pizza")),
        Rule("🍔", listOf("burger")),
        Rule("🍟", listOf("pommes", "fritten", "french fries")),
        Rule("🌭", listOf("hot dog", "hotdog", "wiener", "bratwurst", "cervelat", "wurst")),
        Rule("🍣", listOf("sushi", "maki")),
        Rule("🌯", listOf("burrito", "wrap", "tortilla", "taco", "döner", "kebab")),
        Rule("🍛", listOf("curry")),
        Rule("🍲", listOf("suppe", "eintopf", "brühe", "bouillon", "chili con")),
        Rule("🍝", listOf("nudel", "spaghetti", "pasta", "penne", "tagliatelle", "lasagne", "gnocchi", "ravioli", "makkaroni", "fusilli", "spätzle", "tortellini")),
        Rule("🍚", listOf("risotto", "basmati", "jasmin"), wordStart = listOf("reis")),
        Rule("🥗", listOf("salat")),
        Rule("🥐", listOf("croissant", "gipfeli", "gebäck")),
        Rule("🍰", listOf("kuchen", "torte", "muffin", "brownie", "cheesecake", "tiramisu")),
        Rule("🍪", listOf("keks", "cookie", "biscuit")),
        Rule("🍫", listOf("schokolade", "schoko", "riegel", "praline")),
        Rule("🍨", listOf("glace", "speiseeis", "ice cream", "gelato"), wordStart = listOf("eis ", "eiscreme")),
        Rule("🥤", listOf("proteinpulver", "whey", "shake", "smoothie", "limonade", "cola", "eistee", "saft", "sirup")),
        Rule("🥣", listOf("joghurt", "yogurt", "quark", "skyr", "müesli", "müsli", "haferflocken", "porridge", "oats", "cornflakes", "granola", "bircher")),
        Rule("🍞", listOf("brot", "brötchen", "toast", "semmel", "zopf", "baguette", "knäcke", "bagel")),
        Rule("🥩", listOf("fleischkäse")),
        Rule("🧀", listOf("käse", "mozzarella", "feta", "parmesan", "gouda", "emmentaler", "gruyère", "halloumi", "mascarpone", "frischkäse")),
        Rule("🥚", listOf("spiegelei", "rührei", "omelett", "eier", "hühnerei"), wordStart = listOf("ei ", "ei,")),
        Rule("🍗", listOf("hähnchen", "huhn", "pute", "truthahn", "poulet", "chicken", "geflügel", "chicken", "hühner")),
        Rule("🥓", listOf("speck", "schinken", "bacon", "salami", "bündnerfleisch", "prosciutto")),
        Rule("🥩", listOf("steak", "rind", "hack", "schwein", "schnitzel", "kotelett", "lamm", "filet", "fleisch")),
        Rule("🍤", listOf("garnele", "shrimp", "crevette", "scampi")),
        Rule("🐟", listOf("fisch", "lachs", "thunfisch", "forelle", "kabeljau", "dorsch", "sardine", "makrele", "hering")),
        Rule("🥔", listOf("kartoffel", "erdapfel", "potato")),
        Rule("🥕", listOf("karotte", "rüebli", "möhre", "carrot")),
        Rule("🥦", listOf("brokkoli", "broccoli", "blumenkohl")),
        Rule("🍅", listOf("tomate", "ketchup", "passata")),
        Rule("🥒", listOf("gurke", "zucchini", "cucumber")),
        Rule("🫑", listOf("paprika", "peperoni")),
        Rule("🧅", listOf("zwiebel", "lauch")),
        Rule("🧄", listOf("knoblauch")),
        Rule("🍄", listOf("pilz", "champignon", "steinpilz")),
        Rule("🌽", listOf("mais ", "maiskolben", "popcorn")),
        Rule("🎃", listOf("kürbis")),
        Rule("🥬", listOf("spinat", "kopfsalat", "rucola", "mangold", "kohl")),
        Rule("🥑", listOf("avocado", "guacamole")),
        Rule("🍆", listOf("aubergine")),
        Rule("🫘", listOf("linsen", "bohnen", "kichererbse", "erbsen", "tofu", "tempeh", "hummus", "edamame")),
        Rule("🍎", listOf("apfel", "äpfel", "apple")),
        Rule("🍌", listOf("banane", "banana")),
        Rule("🍓", listOf("erdbeer", "himbeer", "heidelbeer", "brombeer", "beeren", "berry")),
        Rule("🍊", listOf("orange", "mandarine", "clementine", "zitrone", "grapefruit")),
        Rule("🍐", listOf("birne")),
        Rule("🍇", listOf("traube", "rosine")),
        Rule("🍉", listOf("melone")),
        Rule("🍍", listOf("ananas")),
        Rule("🥝", listOf("kiwi")),
        Rule("🥭", listOf("mango")),
        Rule("🥜", listOf("nuss", "nüsse", "mandel", "kerne", "erdnuss", "cashew", "pistazie", "haselnuss", "walnuss", "nussmus", "erdnussbutter")),
        Rule("🧈", listOf("butter", "margarine")),
        Rule("🫒", listOf("olivenöl", "rapsöl", "sonnenblumenöl", "kokosöl", "olive")),
        Rule("🍯", listOf("honig", "ahornsirup", "konfitüre", "marmelade", "nutella")),
        Rule("🥛", listOf("milch", "hafer drink", "haferdrink", "sojadrink", "mandeldrink", "rahm", "sahne")),
        Rule("☕", listOf("kaffee", "espresso", "cappuccino", "latte")),
        Rule("🍵", listOf("tee ", "grüntee", "schwarztee")),
        Rule("💧", listOf("mineralwasser", "wasser")),
        Rule("🍺", listOf("bier")),
        Rule("🍷", listOf("wein", "prosecco", "champagner"))
    )

    /** Emoji für [name] oder null, wenn nichts passt. */
    fun forName(name: String): String? {
        if (name.isBlank()) return null
        // Leerzeichen als Rand, damit "ei " / "reis" auch am Anfang/Ende als Wortanfang erkannt werden
        val n = " " + name.trim().lowercase() + " "
        for (rule in RULES) {
            if (rule.contains.any { it in n }) return rule.emoji
            if (rule.wordStart.any { startsAtWord(n, it) }) return rule.emoji
        }
        return null
    }

    /** true, wenn [key] irgendwo in [text] an einem Wortanfang steht (davor kein Buchstabe). */
    private fun startsAtWord(text: String, key: String): Boolean {
        var i = text.indexOf(key)
        while (i >= 0) {
            if (i == 0 || !text[i - 1].isLetter()) return true
            i = text.indexOf(key, i + 1)
        }
        return false
    }
}
