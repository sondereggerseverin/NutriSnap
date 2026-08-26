package ch.nutrisnap.app.domain

/**
 * Parsing und Formatierung einzelner Zutatenzeilen (Menge / Einheit / Name).
 * Früher in RecipesScreen – jetzt domain-weit nutzbar (Detail, Verify, Tests).
 */

/** Normalisiert einen Zutatentext für robusten Abgleich: nur Kleinbuchstaben + Ziffern,
 *  keine Leerzeichen/Satzzeichen/Einheiten-Formatierung. So matchen "200g Haferflocken"
 *  und "200 g Haferflocken" trotz unterschiedlicher Formatierung. */
fun normalizeForCoverageMatch(s: String): String =
    s.lowercase()
        .replace(Regex("""^added_\d+_"""), "")
        .replace(Regex("""[^a-zäöüß0-9]"""), "")

data class ParsedIngredient(val amount: String, val unit: String, val name: String)
/** Anzeige-Einheiten im Dropdown (kurz, lesbar). */
val INGREDIENT_UNITS = listOf("g", "ml", "kg", "l", "EL", "TL", "Stück", "Prise", "Bund", "Dose", "Packung", "Scheibe", "Zehe")
private const val FRACTION_CHARS = "¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"
private val UNICODE_FRACTION_VALUES = mapOf(
    '¼' to 0.25f, '½' to 0.5f, '¾' to 0.75f,
    '⅓' to 0.33f, '⅔' to 0.67f,
    '⅕' to 0.2f, '⅖' to 0.4f, '⅗' to 0.6f, '⅘' to 0.8f,
    '⅙' to 0.17f, '⅚' to 0.83f,
    '⅛' to 0.13f, '⅜' to 0.38f, '⅝' to 0.63f, '⅞' to 0.88f
)
/** Yazio/Import-Langformen → kurze Einheit. */
private val UNIT_ALIASES = mapOf(
    "g" to "g", "gram" to "g", "grams" to "g", "gramm" to "g", "gramme" to "g",
    "kg" to "kg", "kilogram" to "kg", "kilogramm" to "kg",
    "ml" to "ml", "milliliter" to "ml", "millilitre" to "ml", "milliliters" to "ml",
    "l" to "l", "liter" to "l", "litre" to "l",
    "tsp" to "TL", "teaspoon" to "TL", "teaspoons" to "TL", "tl" to "TL",
    "tbsp" to "EL", "tbs" to "EL", "tablespoon" to "EL", "tablespoons" to "EL", "el" to "EL",
    // Französisch (Instagram FR): c. à soupe / c. à café
    "cas" to "EL", "c.a.s." to "EL", "c.a.s" to "EL", "cs" to "EL",
    "cac" to "TL", "c.a.c." to "TL", "c.a.c" to "TL", "cc" to "TL",
    // Italienisch: cucchiaio = EL, cucchiaino = TL
    "cucchiai" to "EL", "cucchiaio" to "EL", "cucchiaiate" to "EL",
    "cucchiaini" to "TL", "cucchiaino" to "TL",
    "stück" to "Stück", "stueck" to "Stück", "piece" to "Stück", "pieces" to "Stück",
    "cookie" to "Stück", "cookies" to "Stück", "biscuit" to "Stück", "biscuits" to "Stück",
    "keks" to "Stück", "kekse" to "Stück", "pc" to "Stück", "pcs" to "Stück",
    "prise" to "Prise", "pinch" to "Prise",
    "bund" to "Bund", "dose" to "Dose", "packung" to "Packung",
    "scheibe" to "Scheibe", "slice" to "Scheibe", "zehe" to "Zehe"
)
private val UNIT_PATTERN = UNIT_ALIASES.keys.sortedByDescending { it.length }.joinToString("|") {
    Regex.escape(it)
}
private val INGREDIENT_AMOUNT_REGEX = Regex(
    "^((?:\\d+(?:[.,]\\d+)?\\s+)?[$FRACTION_CHARS]|(?:\\d+\\s+)?\\d+/\\d+|\\d+(?:[.,]\\d+)?)" +
        "\\s*($UNIT_PATTERN)?\\s+(.+)",
    RegexOption.IGNORE_CASE
)
// Bereiche: "15-20 g", "15 – 20 g", FR "15 à 20 g"
private val INGREDIENT_RANGE_REGEX = Regex(
    "^(\\d+(?:[.,]\\d+)?)\\s*(?:[-–—]|\\bà\\b)\\s*(\\d+(?:[.,]\\d+)?)\\s*($UNIT_PATTERN)?\\b\\s*(.*)$",
    RegexOption.IGNORE_CASE
)

/**
 * FR/IT/EN-Löffelmaße und Zählwaren vor dem Parsen normalisieren.
 * "4 c. à soupe de Skyr" → "4 EL Skyr"
 * "3 cucchiaini di sale" → "3 TL sale"
 */
fun normalizeCulinaryUnits(line: String): String {
    var r = line.trim()
    if (r.isBlank()) return r
    // IT: cucchiaio/i = Esslöffel, cucchiaino/i = Teelöffel (vor generischem "di")
    r = Regex(
        """(?i)(\d+[.,]?\d*)\s*(?:cucchiai|cucchiaio|cucchiaiate)\b"""
    ).replace(r) { "${it.groupValues[1]} EL" }
    r = Regex(
        """(?i)(\d+[.,]?\d*)\s*(?:cucchiaini|cucchiaino)\b"""
    ).replace(r) { "${it.groupValues[1]} TL" }
    // FR: c. à soupe / cuillère(s) à soupe → EL
    r = Regex(
        """(?i)(\d+[.,]?\d*)\s*(?:c\.\s*à\s*soupe|c\s*à\s*soupe|cuillères?\s*à\s*soupe|c\.a\.s\.?|cas)\b"""
    ).replace(r) { "${it.groupValues[1]} EL" }
    // FR: c. à café → TL
    r = Regex(
        """(?i)(\d+[.,]?\d*)\s*(?:c\.\s*à\s*café|c\s*à\s*café|c\.\s*à\s*cafe|cuillères?\s*à\s*café|c\.a\.c\.?|cac)\b"""
    ).replace(r) { "${it.groupValues[1]} TL" }
    // FR/IT Präposition nach Einheit: "de " / "di "
    r = Regex("""(?i)(\d+[.,]?\d*\s*(?:EL|TL|g|kg|ml|l|Stück))\s+(?:de|di)\s+""").replace(r) {
        "${it.groupValues[1]} "
    }
    // FR-Bereich "15 à 20 g" → "15-20 g"
    r = Regex(
        """(?i)(\d+[.,]?\d*)\s+à\s+(\d+[.,]?\d*)\s*(g|kg|ml|l|EL|TL)\b"""
    ).replace(r) { "${it.groupValues[1]}-${it.groupValues[2]} ${it.groupValues[3]}" }
    // Häufige IT-Zutatennamen → DE (nur wenn klar)
    r = r.replace(Regex("""(?i)\bfarina\s*00\b"""), "Mehl Type 00")
    r = r.replace(Regex("""(?i)\bfarina\b"""), "Mehl")
    r = r.replace(Regex("""(?i)\byogurt\s+greco\b"""), "griechischer Joghurt")
    r = r.replace(Regex("""(?i)\bjoghurt\s+greco\b"""), "griechischer Joghurt")
    r = r.replace(Regex("""(?i)\bsale\b"""), "Salz")
    r = r.replace(Regex("""(?i)\bolio\b"""), "Öl")
    r = r.replace(Regex("""(?i)\blievito\s*(per\s*dolci|in\s*polvere)?\b"""), "Backpulver")
    r = r.replace(Regex("""(?i)\bacqua\b"""), "Wasser")
    return r
}

/** Schneidet Abschnitts-Präfixe wie "Für die Sauce:" vor der eigentlichen Menge ab.
 *  Gemeinsam genutzt von [parseIngredientLine] und [RecipeNutritionAnalyzer.parseIngredientLine]. */
fun stripSectionPrefix(line: String): String =
    line.replace(
        Regex(
            """(?i)^(für\s+(die\s+|den\s+|das\s+)?|for\s+(the\s+)?)""" +
                """(hähnchen|haehnchen|chicken|sauce|soße|sosse|marinade|dressing|""" +
                """topping|teig|base|füllung|fuellung|beilage)""" +
                """\s*[:：\-]\s*"""
        ),
        ""
    ).trim()

/** Zählbare Lebensmittel: "2 chicken breasts" / "2 weetbix" → Stück, nicht g. */
private val COUNTABLE_NAME_HINTS = listOf(
    "breast", "brust", "filet", "fillet", "thigh", "schenkel",
    "egg", "eggs", "ei", "eier",
    "onion", "zwiebel", "shallot", "schalotte",
    "clove", "cloves", "zehe", "zehen", "garlic", "knoblauch",
    "tomato", "tomate", "potato", "kartoffel", "avocado",
    "banana", "banane", "apple", "apfel", "lime", "lemon", "zitrone",
    // Gemüse/Obst oft ohne Einheit in IG-Captions ("2 rote Paprika", "2 Karotten")
    "paprika", "peperoni", "pepper", "bell pepper",
    "karotte", "karotten", "carrot", "carrots", "rüebli", "ruebli", "möhre", "moehre",
    "gurke", "cucumber", "zucchini", "aubergine", "eggplant",
    "brokkoli", "broccoli", "blumenkohl", "cauliflower",
    "lauch", "leek", "fenchel", "sellerie", "celery",
    "piece", "pieces", "stück", "stueck", "stange", "scheibe", "slice",
    // Frühstücks-/Keks-Produkte (häufig in IG-Meal-Prep ohne Einheit)
    "weetbix", "weetabix", "weet-bix", "weet bix",
    "biscuit", "biscuits", "cookie", "cookies", "keks", "kekse",
    "biscoff", "lotus", "spéculoos", "speculoos", "spekulatius",
    "galette", "galettes", "riz", // galettes de riz = Reiswaffeln → Stück
    "cracker", "crackers", "wafer", "wafers",
    "scoop", "scoops", "kugel", "kugeln",
    "bar", "bars", "riegel",
    "tortilla", "tortillas", "wrap", "wraps",
    "leaf", "leaves", "blatt", "blätter", "blatter"
)

private fun isCountableName(name: String): Boolean {
    val n = name.lowercase()
    return COUNTABLE_NAME_HINTS.any { it in n }
}

private fun normalizeUnit(raw: String, nameHint: String = ""): String {
    if (raw.isBlank()) {
        // "2 Hähnchenbrüste" / "1 Schalotte" → Stück, nicht fälschlich g
        return if (isCountableName(nameHint)) "Stück" else "g"
    }
    return UNIT_ALIASES[raw.trim().lowercase()] ?: raw.trim()
}

/**
 * Filler-Wörter und leere Klammern aus Zutatennamen entfernen.
 * Typische AI-/Caption-Reste: "of", "heaped", "level", "approx", "crushed", "whole", "melted".
 */
private fun cleanIngredientName(raw: String): String {
    var s = raw.trim()
        .replace(Regex("""\s*\(\s*null\s*\)""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\(\s*\)"""), "")
        .trim()
    // Führende/eingebettete Mengen-Filler und Präpositionen
    s = s.replace(
        Regex(
            """(?i)\b(?:heaped|level|rounded|scant|packed|approx\.?|approximately|about|ca\.?|roughly|optional)\b"""
        ),
        " "
    )
    // "of X" / "of the X" am Anfang oder mitten im Namen
    // Kein quantifizierter Lookahead — Android ICU wirft PatternSyntaxException.
    s = s.replace(Regex("""(?i)^of(?:\s+the)?\s+"""), "")
    s = s.replace(Regex("""(?i)\s+of(?:\s+the)?\s+"""), " ")
    // Zustandswörter, die als Name-Präfix hängen bleiben
    s = s.replace(
        Regex("""(?i)\b(?:crushed|whole|melted|chopped|diced|minced|sliced|grated|fresh|dried)\b"""),
        " "
    )
    return s.replace(Regex("""\s{2,}"""), " ").trim()
}

/** Wandelt "1 ¼", "¼", "2/3", "1 1/8" oder "1.5" in einen reinen Dezimalstring um. */
private fun parseAmountToken(raw: String): String {
    val trimmed = raw.trim()
    val fractionChar = trimmed.lastOrNull { it in FRACTION_CHARS }
    if (fractionChar != null) {
        val wholePart = trimmed.dropLast(1).trim().replace(',', '.').toFloatOrNull() ?: 0f
        val value = wholePart + (UNICODE_FRACTION_VALUES[fractionChar] ?: 0f)
        return formatAmount(value)
    }
    if (trimmed.contains('/')) {
        val parts = trimmed.split(Regex("\\s+"))
        val slashParts = parts.last().split('/')
        val num = slashParts.getOrNull(0)?.toFloatOrNull()
        val den = slashParts.getOrNull(1)?.toFloatOrNull()
        if (num != null && den != null && den != 0f) {
            val wholePart = if (parts.size > 1) parts.dropLast(1).joinToString(" ").replace(',', '.').toFloatOrNull() ?: 0f else 0f
            return formatAmount(wholePart + num / den)
        }
    }
    return trimmed.replace(',', '.')
}

private fun formatAmount(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString() else "%.2f".format(value)

fun parseIngredientLine(line: String): ParsedIngredient {
    val trimmed = normalizeCulinaryUnits(
        stripSectionPrefix(line.trimStart('•', '-', ' ', '*'))
    )
    if (trimmed.isBlank()) return ParsedIngredient(amount = "", unit = "g", name = "")

    val rangeMatch = INGREDIENT_RANGE_REGEX.find(trimmed)
    if (rangeMatch != null) {
        val a = rangeMatch.groupValues[1].replace(',', '.').toFloatOrNull() ?: 0f
        val b = rangeMatch.groupValues[2].replace(',', '.').toFloatOrNull() ?: 0f
        val avg = if (a > 0f && b > 0f) (a + b) / 2f else maxOf(a, b)
        val name = cleanIngredientName(rangeMatch.groupValues[4].ifBlank { trimmed })
        return ParsedIngredient(
            amount = formatAmount(avg),
            unit = normalizeUnit(rangeMatch.groupValues[3], name),
            name = name
        )
    }

    // MUSS vor INGREDIENT_AMOUNT_REGEX stehen: der generische Regex matched
    // "1 heaped teaspoon of X" mit leerer Einheit und Name="heaped teaspoon of X"
    // → fälschlich 1 g. Hier Adjektiv + EL/TL sauber extrahieren.
    val adjUnit = Regex(
        """^(?:(\d+(?:[.,]\d+)?)|(\d+/\d+)|([¼½¾⅓⅔]))\s+""" +
            """(?:(?:heaped|level|rounded|scant|packed|approx\.?|approximately|about|ca\.?)\s+)?""" +
            """(teaspoons?|tablespoons?|tsp\.?|tbsp\.?|tbs\.?|TL|EL)\s*(?:of\s+)?(.+)$""",
        RegexOption.IGNORE_CASE
    ).find(trimmed)
    if (adjUnit != null) {
        val amountRaw = adjUnit.groupValues[1].ifBlank {
            adjUnit.groupValues[2].ifBlank { adjUnit.groupValues[3] }
        }
        val name = cleanIngredientName(adjUnit.groupValues[5])
        return ParsedIngredient(
            amount = parseAmountToken(amountRaw),
            unit = normalizeUnit(adjUnit.groupValues[4], name),
            name = name
        )
    }

    val m = INGREDIENT_AMOUNT_REGEX.find(trimmed)
    if (m != null) {
        val name = cleanIngredientName(m.groupValues[3])
        return ParsedIngredient(
            amount = parseAmountToken(m.groupValues[1]),
            unit = normalizeUnit(m.groupValues[2], name),
            name = name
        )
    }
    val loose = Regex(
        """^(\d+(?:[.,]\d+)?)\s*($UNIT_PATTERN)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    ).find(trimmed)
    if (loose != null) {
        val name = cleanIngredientName(loose.groupValues[3])
        return ParsedIngredient(
            amount = loose.groupValues[1].replace(',', '.'),
            unit = normalizeUnit(loose.groupValues[2], name),
            name = name
        )
    }
    // "2 chicken breasts" / "2 weetbix" ohne erkannte Einheit
    val countOnly = Regex(
        """^(\d+(?:[.,]\d+)?)\s+(.+)$"""
    ).find(trimmed)
    if (countOnly != null) {
        val name = cleanIngredientName(countOnly.groupValues[2])
        val unit = if (isCountableName(name)) "Stück" else "g"
        return ParsedIngredient(
            amount = countOnly.groupValues[1].replace(',', '.'),
            unit = unit,
            name = name
        )
    }
    // "Heaped teaspoon of melted biscoff" ohne führende Zahl → 1 TL …
    val bareUnit = Regex(
        """^(?:(heaped|level|rounded|scant|packed)\s+)?""" +
            """(teaspoons?|tablespoons?|tsp\.?|tbsp\.?|tbs\.?|TL|EL)\s*(?:of\s+)?(.+)$""",
        RegexOption.IGNORE_CASE
    ).find(trimmed)
    if (bareUnit != null) {
        val name = cleanIngredientName(bareUnit.groupValues[3])
        return ParsedIngredient(
            amount = "1",
            unit = normalizeUnit(bareUnit.groupValues[2], name),
            name = name
        )
    }
    return ParsedIngredient(amount = "", unit = "g", name = cleanIngredientName(trimmed))
}

fun joinIngredientLine(parsed: ParsedIngredient): String {
    val amt = parsed.amount.trim()
    val name = cleanIngredientName(parsed.name)
    val unit = normalizeUnit(parsed.unit, name)
    return if (amt.isNotBlank()) "$amt $unit $name" else name
}
