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
private val INGREDIENT_RANGE_REGEX = Regex(
    "^(\\d+(?:[.,]\\d+)?)\\s*[-–—]\\s*(\\d+(?:[.,]\\d+)?)\\s*($UNIT_PATTERN)?\\b\\s*(.*)$",
    RegexOption.IGNORE_CASE
)

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
    "piece", "pieces", "stück", "stueck", "stange", "scheibe", "slice",
    // Frühstücks-/Keks-Produkte (häufig in IG-Meal-Prep ohne Einheit)
    "weetbix", "weetabix", "weet-bix", "weet bix",
    "biscuit", "biscuits", "cookie", "cookies", "keks", "kekse",
    "biscoff", "lotus",
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
    // "of X" / "of the X" am Anfang oder nach Menge
    s = s.replace(Regex("""(?i)^(?:of\s+(?:the\s+)?)"""), "")
    s = s.replace(Regex("""(?i)\s+of\s+(?=the\s+)?"""), " ")
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
    val trimmed = stripSectionPrefix(line.trimStart('•', '-', ' ', '*'))
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
    // "1 heaped teaspoon of cream cheese" / "2 tablespoons of Greek Yogurt"
    // Adjektiv zwischen Zahl und Einheit → Einheit extrahieren, Filler strippen
    val adjUnit = Regex(
        """^(\d+(?:[.,]\d+)?|\d+/\d+|[¼½¾⅓⅔])\s+(?:heaped|level|rounded|scant|packed|approx\.?|approximately|about)?\s*""" +
            """(teaspoons?|tablespoons?|tsp|tbsp|tbs|TL|EL)\b(?:\s+of)?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    ).find(trimmed)
    if (adjUnit != null) {
        val name = cleanIngredientName(adjUnit.groupValues[3])
        return ParsedIngredient(
            amount = parseAmountToken(adjUnit.groupValues[1]),
            unit = normalizeUnit(adjUnit.groupValues[2], name),
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
    return ParsedIngredient(amount = "", unit = "g", name = cleanIngredientName(trimmed))
}

fun joinIngredientLine(parsed: ParsedIngredient): String {
    val amt = parsed.amount.trim()
    val name = cleanIngredientName(parsed.name)
    val unit = normalizeUnit(parsed.unit, name)
    return if (amt.isNotBlank()) "$amt $unit $name" else name
}
