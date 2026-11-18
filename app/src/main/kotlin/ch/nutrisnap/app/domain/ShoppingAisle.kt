package ch.nutrisnap.app.domain

import java.text.Normalizer

/**
 * Ordnet Einkaufspositionen einem Supermarkt-Gang zu (Obst & Gemüse, Kühlregal, …),
 * damit die Liste in der Reihenfolge des Ladens abgearbeitet werden kann.
 *
 * Rein regelbasiert (Schlüsselwörter, DACH-Begriffe) – kein Netzwerk, keine KI, deterministisch.
 * Die Reihenfolge der Regeln ist wichtig: spezifische Begriffe stehen vor allgemeinen
 * («Kokosmilch» vor «Milch», «Paprikapulver» vor «Paprika»).
 */
object ShoppingAisle {

    enum class Aisle(val label: String) {
        PRODUCE("Obst & Gemüse"),
        BREAD("Brot & Backwaren"),
        DAIRY("Milch, Käse & Eier"),
        MEAT_FISH("Fleisch & Fisch"),
        DRY("Pasta, Reis & Konserven"),
        BAKING("Backen & Öle"),
        SPICES("Gewürze & Saucen"),
        BEVERAGES("Getränke"),
        FROZEN("Tiefkühl"),
        OTHER("Sonstiges")
    }

    private fun fold(raw: String): String {
        var s = raw.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return s
    }

    private fun tokens(raw: String): List<String> =
        fold(raw).replace(Regex("""\([^)]*\)"""), " ")
            .split(Regex("""[^a-z]+"""))
            .filter { it.length >= 2 }

    private fun matches(token: String, kw: String): Boolean =
        token == kw ||
            (kw.length >= 4 && token.startsWith(kw)) ||
            (kw.length >= 3 && token.endsWith(kw))

    /** Spezifische Regeln zuerst; erste passende gewinnt. */
    private val RULES: List<Pair<Aisle, List<String>>> = listOf(
        Aisle.FROZEN to listOf("tiefkuehl", "gefroren", "pommes", "eiscreme", "glace", "tk"),
        // Ausnahmen, die sonst von allgemeinen Begriffen geschluckt würden
        Aisle.DRY to listOf(
            "kokosmilch", "kokosnussmilch", "tomatenmark", "passata", "erdnussbutter", "nussmus",
            "mandelmus", "chips", "cracker", "dosentomaten", "konserve", "dose", "dosen"
        ),
        Aisle.PRODUCE to listOf("melone", "wassermelone"),
        Aisle.SPICES to listOf(
            "salz", "pfeffer", "gewuerz", "pulver", "zimt", "curry", "kurkuma", "kuemmel", "muskat",
            "oregano", "ketchup", "senf", "mayonnaise", "mayo", "sauce", "bruehe", "fond", "sambal",
            "harissa", "sriracha", "tahini", "pesto", "dressing", "worcester", "tabasco", "miso",
            "sojasauce", "paprikapulver", "chilipulver", "vanille"
        ),
        Aisle.BEVERAGES to listOf(
            "wasser", "mineral", "saft", "bier", "wein", "cola", "limonade", "sirup", "tee",
            "kaffee", "getraenk", "prosecco", "sprudel", "drink"
        ),
        Aisle.BAKING to listOf(
            "mehl", "zucker", "backpulver", "hefe", "natron", "schokolade", "kakao", "staerke",
            "gelatine", "oel", "essig", "puderzucker", "griess"
        ),
        Aisle.BREAD to listOf(
            "brot", "broetchen", "toast", "baguette", "zopf", "gipfeli", "croissant", "wrap",
            "tortilla", "fladenbrot", "pita", "brioche", "semmel"
        ),
        Aisle.MEAT_FISH to listOf(
            "fleisch", "huhn", "haehnchen", "poulet", "pute", "truthahn", "rind", "schwein", "hack",
            "speck", "schinken", "salami", "wurst", "lachs", "thunfisch", "fisch", "garnele",
            "crevette", "forelle", "kabeljau", "filet", "gefluegel", "lamm", "kalb", "chicken", "beef"
        ),
        Aisle.DAIRY to listOf(
            "milch", "joghurt", "quark", "rahm", "sahne", "butter", "kaese", "mozzarella", "parmesan",
            "feta", "ricotta", "skyr", "eier", "ei", "margarine", "schmand", "fraiche", "mascarpone",
            "halloumi", "tofu", "tempeh", "hummus", "gruyere", "emmentaler"
        ),
        Aisle.PRODUCE to listOf(
            "apfel", "birne", "banane", "zitrone", "limette", "orange", "mandarine", "clementine",
            "traube", "beere", "beeren", "ananas", "mango", "kiwi", "avocado", "pfirsich", "nektarine",
            "pflaume", "kirsche", "feige", "tomate", "gurke", "zucchini", "aubergine", "paprika",
            "zwiebel", "knoblauch", "lauch", "porree", "schalotte", "kartoffel", "karotte", "moehre",
            "rueebli", "sellerie", "brokkoli", "blumenkohl", "kohl", "spinat", "salat", "rucola",
            "fenchel", "kuerbis", "rettich", "radieschen", "spargel", "pilz", "champignon", "ingwer",
            "randen", "chicoree", "peperoni", "chili", "sprossen", "kresse", "basilikum", "petersilie",
            "schnittlauch", "koriander", "minze", "dill", "rosmarin", "thymian", "fruehlingszwiebel"
        ),
        Aisle.DRY to listOf(
            "nudel", "pasta", "spaghetti", "penne", "fusilli", "reis", "couscous", "bulgur", "quinoa",
            "linse", "kichererbse", "haferflocken", "flocken", "muesli", "cornflakes", "mais", "bohne",
            "nuss", "nuesse", "mandel", "walnuss", "cashew", "erdnuss", "kerne", "samen", "rosine",
            "trockenfrucht", "honig", "marmelade", "konfituere", "aufstrich", "polenta", "tomaten"
        )
    )

    fun classify(name: String): Aisle {
        val toks = tokens(name)
        if (toks.isEmpty()) return Aisle.OTHER
        for ((aisle, keywords) in RULES) {
            if (toks.any { t -> keywords.any { kw -> matches(t, kw) } }) return aisle
        }
        return Aisle.OTHER
    }
}
