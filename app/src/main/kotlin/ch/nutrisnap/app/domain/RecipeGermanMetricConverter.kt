package ch.nutrisnap.app.domain

import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Übersetzt Rezept-Zutaten und Zubereitung ins Deutsche und rechnet
 * imperial/US-Mengen (cups, tbsp, oz, °F …) in metrische Einheiten um.
 */
object RecipeGermanMetricConverter {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

    @Serializable
    data class ConvertedRecipe(
        val ingredients: String = "",
        val instructions: String = "",
        val title: String = "",
        val description: String = ""
    )

    /**
     * Schnelle Offline-Umrechnung nur für Einheiten (kein Übersetzen).
     * Unterstützt Brüche wie 1/4, ½, 1 1/2.
     */
    fun convertUnitsToMetric(text: String): String {
        return try {
            text.lines().joinToString("\n") { line ->
                runCatching { convertLineToMetric(line) }.getOrDefault(line)
            }
        } catch (_: Exception) {
            text
        }
    }

    /**
     * Flüssigkeiten → ml, feste Zutaten → g (mit typischen Dichten).
     * Cups/tbsp/tsp werden nicht pauschal als Volumen belassen.
     */
    private fun convertLineToMetric(line: String): String {
        var r = line
        val unicodeFractions = mapOf(
            '¼' to "1/4", '½' to "1/2", '¾' to "3/4",
            '⅓' to "1/3", '⅔' to "2/3", '⅛' to "1/8",
            '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8"
        )
        unicodeFractions.forEach { (u, a) -> r = r.replace(u.toString(), a) }

        fun parseAmount(raw: String): Double? {
            val s = raw.trim().replace(',', '.')
            val mixed = Regex("""^(\d+)\s+(\d+)/(\d+)$""").matchEntire(s)
            if (mixed != null) {
                val whole = mixed.groupValues[1].toDouble()
                val num = mixed.groupValues[2].toDouble()
                val den = mixed.groupValues[3].toDouble()
                if (den != 0.0) return whole + num / den
            }
            val frac = Regex("""^(\d+)/(\d+)$""").matchEntire(s)
            if (frac != null) {
                val num = frac.groupValues[1].toDouble()
                val den = frac.groupValues[2].toDouble()
                if (den != 0.0) return num / den
            }
            return s.toDoubleOrNull()
        }

        fun fmt(v: Double, unit: String): String {
            val rounded = when {
                v >= 100 -> v.toInt().toString()
                v >= 10 -> {
                    val t = (v * 10).toInt() / 10.0
                    if (t == t.toInt().toDouble()) t.toInt().toString() else "%.1f".format(t)
                }
                else -> {
                    val t = (v * 10).toInt() / 10.0
                    if (t == t.toInt().toDouble()) t.toInt().toString() else "%.1f".format(t)
                }
            }
            return "$rounded$unit"
        }

        // °F → °C zuerst
        r = Regex("""(\d+(?:[.,]\d+)?)\s*°\s*F\b""").replace(r) { mr ->
            val v = mr.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@replace mr.value
            "${((v - 32) * 5 / 9).toInt()}°C"
        }
        r = Regex("""(\d+(?:[.,]\d+)?)\s*degrees?\s*F(?:ahrenheit)?\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            val v = mr.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@replace mr.value
            "${((v - 32) * 5 / 9).toInt()}°C"
        }

        // oz / lb immer Gewicht
        r = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:lbs?|pounds?)\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            parseAmount(mr.groupValues[1])?.let { fmt(it * 453.6, " g") } ?: mr.value
        }
        r = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:fl\.?\s*oz)\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            parseAmount(mr.groupValues[1])?.let { fmt(it * 29.57, " ml") } ?: mr.value
        }
        r = Regex("""(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(?:oz|ounces?)\b""", RegexOption.IGNORE_CASE).replace(r) { mr ->
            parseAmount(mr.groupValues[1])?.let { fmt(it * 28.35, " g") } ?: mr.value
        }

        // cup / tbsp / tsp: abhängig von der Zutat (Name nach der Einheit)
        val volPattern = Regex(
            """(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)\s*(cups?|tbsp|tbs|tablespoons?|tsp|teaspoons?)\b\s*(.*)""",
            RegexOption.IGNORE_CASE
        )
        r = volPattern.replace(r) { mr ->
            val amt = parseAmount(mr.groupValues[1]) ?: return@replace mr.value
            val unit = mr.groupValues[2].lowercase()
            val rest = mr.groupValues[3]
            val name = rest.lowercase()

            val isLiquid = LIQUID_KEYWORDS.any { name.contains(it) }
            val densityPerCup = densityGPerCup(name) // null = unbekannt

            when {
                unit.startsWith("cup") -> {
                    when {
                        isLiquid -> fmt(amt * 240, " ml") + (if (rest.isNotBlank()) " $rest" else "")
                        densityPerCup != null -> fmt(amt * densityPerCup, " g") + (if (rest.isNotBlank()) " $rest" else "")
                        else -> fmt(amt * 240, " g") + (if (rest.isNotBlank()) " $rest" else "") // Feststoffe default: g≈ml-Volumen
                    }
                }
                unit.startsWith("tbsp") || unit == "tbs" || unit.startsWith("table") -> {
                    when {
                        isLiquid -> fmt(amt * 15, " ml") + (if (rest.isNotBlank()) " $rest" else "")
                        densityPerCup != null -> fmt(amt * densityPerCup / 16.0, " g") + (if (rest.isNotBlank()) " $rest" else "")
                        else -> fmt(amt * 15, " g") + (if (rest.isNotBlank()) " $rest" else "")
                    }
                }
                else -> { // tsp
                    when {
                        isLiquid -> fmt(amt * 5, " ml") + (if (rest.isNotBlank()) " $rest" else "")
                        densityPerCup != null -> fmt(amt * densityPerCup / 48.0, " g") + (if (rest.isNotBlank()) " $rest" else "")
                        else -> fmt(amt * 5, " g") + (if (rest.isNotBlank()) " $rest" else "")
                    }
                }
            }
        }

        return r
    }

    /** g pro US-Cup für gängige feste Zutaten. */
    private fun densityGPerCup(nameLower: String): Double? {
        val rules = listOf(
            listOf("flour", "mehl") to 120.0,
            listOf("sugar", "zucker", "coconut sugar") to 200.0,
            listOf("powdered sugar", "icing", "confectioner", "puderzucker") to 120.0,
            listOf("butter", "butter") to 227.0,
            listOf("cottage cheese", "hüttenkäse", "huttenkase") to 225.0,
            listOf("greek yogurt", "joghurt", "yogurt", "yoghurt") to 245.0,
            listOf("honey", "honig") to 340.0,
            listOf("protein powder", "proteinpulver", "whey") to 120.0,
            listOf("cinnamon", "zimt") to 125.0,
            listOf("baking powder", "backpulver") to 220.0,
            listOf("baking soda", "natron") to 220.0,
            listOf("salt", "salz") to 290.0,
            listOf("cocoa", "kakao") to 85.0,
            listOf("oat", "hafer") to 90.0,
            listOf("rice", "reis") to 185.0,
            listOf("cheese", "käse", "kase") to 110.0
        )
        for ((keys, dens) in rules) {
            if (keys.any { nameLower.contains(it) }) return dens
        }
        return null
    }

    private val LIQUID_KEYWORDS = listOf(
        "milk", "milch", "water", "wasser", "oil", "öl", "ol ", " cream", "sahne",
        "broth", "brühe", "bruhe", "stock", "juice", "saft", "vinegar", "essig",
        "wine", "wein", "beer", "bier", "syrup", "sirup", "extract", "vanilleextrakt",
        "vanilla extract", "soy sauce", "sojasauce", "stock", "fond"
    )


    /**
     * Offline-Übersetzung gängiger Zutatennamen und Abschnittsüberschriften.
     * Wird immer angewendet (auch als Nachbearbeitung nach KI).
     */
    private val NAME_MAP: List<Pair<Regex, String>> = listOf(
        // Abschnitte (inkl. "For the …")
        Regex("""(?i)^for\s+the\s+sauce\s*:?\s*$""") to "Für die Sauce:",
        Regex("""(?i)^for\s+the\s+marinade\s*:?\s*$""") to "Für die Marinade:",
        Regex("""(?i)^for\s+the\s+dressing\s*:?\s*$""") to "Für das Dressing:",
        Regex("""(?i)^for\s+the\s+topping\s*:?\s*$""") to "Für den Belag:",
        Regex("""(?i)^for\s+the\s+(.+?)\s+mash\s*:?\s*$""") to "Für den $1-Stampf:",
        Regex("""(?i)^for\s+the\s+(.+?)\s*:?\s*$""") to "Für $1:",
        Regex("""(?i)^dough\s*:?\s*$""") to "Teig:",
        Regex("""(?i)^filling\s*:?\s*$""") to "Füllung:",
        Regex("""(?i)^frosting\s*:?\s*$""") to "Glasur:",
        Regex("""(?i)^topping\s*:?\s*$""") to "Belag:",
        Regex("""(?i)^sauce\s*:?\s*$""") to "Sauce:",
        Regex("""(?i)^marinade\s*:?\s*$""") to "Marinade:",
        Regex("""(?i)^batter\s*:?\s*$""") to "Teigmasse:",
        Regex("""(?i)^glaze\s*:?\s*$""") to "Glasur:",
        Regex("""(?i)^crust\s*:?\s*$""") to "Boden:",
        Regex("""(?i)^streusel\s*:?\s*$""") to "Streusel:",
        Regex("""(?i)^mash\s*:?\s*$""") to "Stampf:",
        Regex("""(?i)^syrup\s*:?\s*$""") to "Sirup:",
        Regex("""(?i)^icing\s*:?\s*$""") to "Glasur:",
        Regex("""(?i)^cream\s+cheese\s+frosting\s*:?\s*$""") to "Frischkäse-Frosting:",
        Regex("""(?i)^coffee\s+cream\s+cheese\s+frosting\s*:?\s*$""") to "Kaffee-Frischkäse-Frosting:",
        Regex("""(?i)^cinnamon\s+coffee\s+filling\s*:?\s*$""") to "Zimt-Kaffee-Füllung:",
        Regex("""(?i)^coffee\s+syrup\s*:?\s*$""") to "Kaffee-Sirup:",
        Regex("""(?i)^coffee\s+filling\s*:?\s*$""") to "Kaffee-Füllung:",
        Regex("""(?i)^(.+?)\s+filling\s*:?\s*$""") to "$1-Füllung:",
        Regex("""(?i)^(.+?)\s+frosting\s*:?\s*$""") to "$1-Frosting:",
        Regex("""(?i)^(.+?)\s+syrup\s*:?\s*$""") to "$1-Sirup:",
        Regex("""(?i)^(.+?)\s+sauce\s*:?\s*$""") to "$1-Sauce:",
        Regex("""(?i)^(.+?)\s+dough\s*:?\s*$""") to "$1-Teig:",
        // Mehrwort-Zutaten (längere zuerst — wichtig vor Einzelwort-Ersetzungen)
        Regex("""(?i)\bchicken\s+thigh\s+fillets?\b""") to "Hähnchen-Oberschenkel-Filets",
        Regex("""(?i)\bchicken\s+thighs?\b""") to "Hähnchen-Oberschenkel",
        Regex("""(?i)\bthigh\s+fillets?\b""") to "Oberschenkel-Filets",
        Regex("""(?i)\bchicken\s+breast\s+fillets?\b""") to "Hähnchenbrustfilets",
        Regex("""(?i)\bchicken\s+breast\b""") to "Hähnchenbrust",
        Regex("""(?i)\bboneless\s+skinless\s+chicken\b""") to "Hähnchen ohne Haut und Knochen",
        Regex("""(?i)\btaco\s+seasoning\b""") to "Taco-Gewürz",
        Regex("""(?i)\bchilli?\s+flakes?\b""") to "Chiliflocken",
        Regex("""(?i)\bred\s+pepper\s+flakes?\b""") to "Chiliflocken",
        Regex("""(?i)\bsweet\s+corn\b""") to "Zuckermais",
        Regex("""(?i)\bsweetcorn\b""") to "Zuckermais",
        Regex("""(?i)\bdrained\s+weight\b""") to "Abtropfgewicht",
        Regex("""(?i)\bblack\s+beans?\b""") to "schwarze Bohnen",
        Regex("""(?i)\bsmall\s+handful\s+of\b""") to "eine kleine Handvoll",
        Regex("""(?i)\bhandful\s+of\b""") to "eine Handvoll",
        Regex("""(?i)\ba\s+handful\b""") to "eine Handvoll",
        Regex("""(?i)\bgreek\s+joghurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bsweet\s+potato(?:es)?\b""") to "Süsskartoffeln",
        Regex("""(?i)\bsweet\s+kartoffeln\b""") to "Süsskartoffeln",
        Regex("""(?i)\bcottage\s+cheese\b""") to "Hüttenkäse",
        Regex("""(?i)\bgreek\s+yogurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bgreek\s+yoghurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bplain\s+yogurt\b""") to "Naturjoghurt",
        Regex("""(?i)\braw\s+milk\b""") to "Rohmilch",
        Regex("""(?i)\bwhole\s+milk\b""") to "Vollmilch",
        Regex("""(?i)\bskim\s+milk\b""") to "Magermilch",
        Regex("""(?i)\balmond\s+milk\b""") to "Mandelmilch",
        Regex("""(?i)\boat\s+milk\b""") to "Hafermilch",
        Regex("""(?i)\braw\s+honey\b""") to "Rohhonig",
        Regex("""(?i)\blarge\s+farm\s+fresh\s+eggs?\b""") to "grosse frische Eier",
        Regex("""(?i)\bfarm\s+fresh\s+eggs?\b""") to "frische Eier",
        Regex("""(?i)\blarge\s+eggs?\b""") to "grosse Eier",
        Regex("""(?i)\bmedium\s+eggs?\b""") to "mittlere Eier",
        Regex("""(?i)\bmelted\s+butter\b""") to "geschmolzene Butter",
        Regex("""(?i)\bunsalted\s+butter\b""") to "ungesalzene Butter",
        Regex("""(?i)\bsalted\s+butter\b""") to "gesalzene Butter",
        Regex("""(?i)\borganic\s+vanilla\s+extract\b""") to "Bio-Vanilleextrakt",
        Regex("""(?i)\bvanilla\s+extract\b""") to "Vanilleextrakt",
        Regex("""(?i)\bvanilla\s+protein\s+powder\b""") to "Vanille-Proteinpulver",
        Regex("""(?i)\bprotein\s+powder\b""") to "Proteinpulver",
        Regex("""(?i)\bbaking\s+powder\b""") to "Backpulver",
        Regex("""(?i)\bbaking\s+soda\b""") to "Natron",
        Regex("""(?i)\breal\s+salt\b""") to "Salz",
        Regex("""(?i)\bsea\s+salt\b""") to "Meersalz",
        Regex("""(?i)\bground\s+cinnamon\b""") to "gemahlener Zimt",
        Regex("""(?i)\borganic\s+ground\s+cinnamon\b""") to "Bio-Zimt, gemahlen",
        Regex("""(?i)\bwhole\s+wheat\s+flour\b""") to "Vollkornmehl",
        Regex("""(?i)\ball[- ]purpose\s+flour\b""") to "Weizenmehl Type 405",
        Regex("""(?i)\bbread\s+flour\b""") to "Brotmehl",
        Regex("""(?i)\bcoconut\s+oil\b""") to "Kokosöl",
        Regex("""(?i)\bolive\s+oil\b""") to "Olivenöl",
        Regex("""(?i)\bvegetable\s+oil\b""") to "Pflanzenöl",
        Regex("""(?i)\bbrown\s+sugar\b""") to "brauner Zucker",
        Regex("""(?i)\bpowdered\s+sugar\b""") to "Puderzucker",
        Regex("""(?i)\bgranulated\s+sugar\b""") to "Kristallzucker",
        Regex("""(?i)\bheavy\s+cream\b""") to "Schlagrahm",
        Regex("""(?i)\bsour\s+cream\b""") to "Sauerrahm",
        Regex("""(?i)\bcream\s+cheese\b""") to "Frischkäse",
        Regex("""(?i)\bpeanut\s+butter\b""") to "Erdnussbutter",
        Regex("""(?i)\bmaple\s+syrup\b""") to "Ahornsirup",
        Regex("""(?i)\bsoy\s+sauce\b""") to "Sojasauce",
        Regex("""(?i)\bgarlic\s+powder\b""") to "Knoblauchpulver",
        Regex("""(?i)\bonion\s+powder\b""") to "Zwiebelpulver",
        Regex("""(?i)\bblack\s+pepper\b""") to "schwarzer Pfeffer",
        Regex("""(?i)\bred\s+onion\b""") to "rote Zwiebel",
        Regex("""(?i)\bgreen\s+onion\b""") to "Frühlingszwiebel",
        Regex("""(?i)\bbell\s+pepper\b""") to "Paprika",
        Regex("""(?i)\bground\s+beef\b""") to "Rinderhackfleisch",
        // Einzelwörter
        Regex("""(?i)\bcoriander\b""") to "Koriander",
        Regex("""(?i)\bcilantro\b""") to "Koriander",
        Regex("""(?i)\bfillets?\b""") to "Filets",
        Regex("""(?i)\bthighs?\b""") to "Oberschenkel",
        Regex("""(?i)\bseasoning\b""") to "Gewürz",
        Regex("""(?i)\bflakes?\b""") to "Flocken",
        Regex("""(?i)\bmash\b""") to "Stampf",
        Regex("""(?i)\beggs?\b""") to "Eier",
        Regex("""(?i)\bbutter\b""") to "Butter",
        Regex("""(?i)\bflour\b""") to "Mehl",
        Regex("""(?i)\bsugar\b""") to "Zucker",
        Regex("""(?i)\bsalt\b""") to "Salz",
        Regex("""(?i)\bpepper\b""") to "Pfeffer",
        Regex("""(?i)\bmilk\b""") to "Milch",
        Regex("""(?i)\bhoney\b""") to "Honig",
        Regex("""(?i)\bcinnamon\b""") to "Zimt",
        Regex("""(?i)\bvanilla\b""") to "Vanille",
        Regex("""(?i)\boil\b""") to "Öl",
        Regex("""(?i)\bwater\b""") to "Wasser",
        Regex("""(?i)\byogurt\b""") to "Joghurt",
        Regex("""(?i)\byoghurt\b""") to "Joghurt",
        Regex("""(?i)\bjoghurt\b""") to "Joghurt",
        Regex("""(?i)\bonion\b""") to "Zwiebel",
        Regex("""(?i)\bgarlic\b""") to "Knoblauch",
        Regex("""(?i)\btomato(?:es)?\b""") to "Tomaten",
        Regex("""(?i)\bcheese\b""") to "Käse",
        Regex("""(?i)\brice\b""") to "Reis",
        Regex("""(?i)\bpasta\b""") to "Pasta",
        Regex("""(?i)\boats?\b""") to "Haferflocken",
        Regex("""(?i)\balmonds?\b""") to "Mandeln",
        Regex("""(?i)\bwalnuts?\b""") to "Walnüsse",
        Regex("""(?i)\blemon\b""") to "Zitrone",
        Regex("""(?i)\blime\b""") to "Limette",
        Regex("""(?i)\borange\b""") to "Orange",
        Regex("""(?i)\bbanana\b""") to "Banane",
        Regex("""(?i)\bapple\b""") to "Apfel",
        Regex("""(?i)\bspinach\b""") to "Spinat",
        Regex("""(?i)\bbroccoli\b""") to "Brokkoli",
        Regex("""(?i)\bcarrot\b""") to "Karotte",
        Regex("""(?i)\bpotato(?:es)?\b""") to "Kartoffeln",
        Regex("""(?i)\bchicken\b""") to "Hähnchen",
        Regex("""(?i)\bhuhn\b""") to "Hähnchen",
        Regex("""(?i)\bbeef\b""") to "Rindfleisch",
        Regex("""(?i)\bpork\b""") to "Schwein",
        Regex("""(?i)\bsalmon\b""") to "Lachs",
        Regex("""(?i)\berythritol\b""") to "Erythrit",
        Regex("""(?i)\bfat[- ]free\b""") to "fettarm",
        Regex("""(?i)\bnon[- ]fat\b""") to "fettfrei",
        Regex("""(?i)\blow[- ]fat\b""") to "fettarm",
        Regex("""(?i)\bwhipped\b""") to "aufgeschlagen",
        Regex("""(?i)\bcream cheese\b""") to "Frischkäse",
        Regex("""(?i)\bgreek yogurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bgreek yoghurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bprotein powder\b""") to "Proteinpulver",
        Regex("""(?i)\bsweetener\b""") to "Süssungsmittel",
        Regex("""(?i)\bpudding mix\b""") to "Puddingpulver",
        Regex("""(?i)\bsugar[- ]free\b""") to "zuckerfrei",
        Regex("""(?i)\bzero calorie\b""") to "kalorienfrei",
        Regex("""(?i)\beggs?\b""") to "Ei",
        Regex("""(?i)\bbiscuit\b""") to "Keks",
        Regex("""(?i)\bspread\b""") to "Aufstrich",
        Regex("""(?i)\bmix all the ingredients\b""") to "Alle Zutaten vermengen",
        Regex("""(?i)\bpour the mixture\b""") to "Die Mischung giessen",
        Regex("""(?i)\bpreheat\b""") to "Vorheizen",
        Regex("""(?i)\bbake\b""") to "Backen",
        Regex("""(?i)\buntil cool\b""") to "bis abgekühlt",
        Regex("""(?i)\bcake baking dish\b""") to "Kuchenform",
        Regex("""(?i)\blightly greased\b""") to "leicht gefettet",
        Regex("""(?i)\blined\b""") to "ausgelegt",
        Regex("""(?i)\bbaking dish\b""") to "Backform",
        Regex("""(?i)\blarge tray\b""") to "grosses Blech",
        Regex("""(?i)\bmelted\b""") to "geschmolzen",
        Regex("""(?i)\bcrushed\b""") to "zerkrümelt",
        Regex("""(?i)\bhigh protein\b""") to "proteinreich",
        Regex("""(?i)\bfat loss\b""") to "Fettabbau",
        Regex("""(?i)\bper serving\b""") to "pro Portion",
        Regex("""(?i)\bfat[- ]free\b""") to "fettarm",
        Regex("""(?i)\bnon[- ]fat\b""") to "fettfrei",
        Regex("""(?i)\blow[- ]fat\b""") to "fettarm",
        Regex("""(?i)\bwhipped\b""") to "aufgeschlagen",
        Regex("""(?i)\bcream cheese\b""") to "Frischkäse",
        Regex("""(?i)\bgreek yogurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bgreek yoghurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bprotein powder\b""") to "Proteinpulver",
        Regex("""(?i)\bsweetener\b""") to "Süssungsmittel",
        Regex("""(?i)\bpudding mix\b""") to "Puddingpulver",
        Regex("""(?i)\bsugar[- ]free\b""") to "zuckerfrei",
        Regex("""(?i)\bzero calorie\b""") to "kalorienfrei",
        Regex("""(?i)\beggs?\b""") to "Ei",
        Regex("""(?i)\bbiscuit\b""") to "Keks",
        Regex("""(?i)\bspread\b""") to "Aufstrich",
        Regex("""(?i)\bmix all the ingredients\b""") to "Alle Zutaten vermengen",
        Regex("""(?i)\bpour the mixture\b""") to "Die Mischung giessen",
        Regex("""(?i)\bpreheat\b""") to "Vorheizen",
        Regex("""(?i)\bbake\b""") to "Backen",
        Regex("""(?i)\buntil cool\b""") to "bis abgekühlt",
        Regex("""(?i)\blightly greased\b""") to "leicht gefettet",
        Regex("""(?i)\bbaking dish\b""") to "Backform",
        Regex("""(?i)\bmelted\b""") to "geschmolzen",
        Regex("""(?i)\bcrushed\b""") to "zerkrümelt",
        Regex("""(?i)\bhigh protein\b""") to "proteinreich",
        Regex("""(?i)\bper serving\b""") to "pro Portion",
        Regex("""(?i)\bfat[- ]free\b""") to "fettarm",
        Regex("""(?i)\bnon[- ]fat\b""") to "fettfrei",
        Regex("""(?i)\blow[- ]fat\b""") to "fettarm",
        Regex("""(?i)\bwhipped\b""") to "aufgeschlagen",
        Regex("""(?i)\bcream cheese\b""") to "Frischkäse",
        Regex("""(?i)\bgreek yogurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bgreek yoghurt\b""") to "griechischer Joghurt",
        Regex("""(?i)\bprotein powder\b""") to "Proteinpulver",
        Regex("""(?i)\bsweetener\b""") to "Süssungsmittel",
        Regex("""(?i)\bpudding mix\b""") to "Puddingpulver",
        Regex("""(?i)\bsugar[- ]free\b""") to "zuckerfrei",
        Regex("""(?i)\bzero calorie\b""") to "kalorienfrei",
        Regex("""(?i)\beggs?\b""") to "Ei",
        Regex("""(?i)\bbiscuit\b""") to "Keks",
        Regex("""(?i)\bspread\b""") to "Aufstrich",
        Regex("""(?i)\bmix all the ingredients\b""") to "Alle Zutaten vermengen",
        Regex("""(?i)\bpour the mixture\b""") to "Die Mischung giessen",
        Regex("""(?i)\bpreheat\b""") to "Vorheizen",
        Regex("""(?i)\bbake\b""") to "Backen",
        Regex("""(?i)\buntil cool\b""") to "bis abgekühlt",
        Regex("""(?i)\blightly greased\b""") to "leicht gefettet",
        Regex("""(?i)\bbaking dish\b""") to "Backform",
        Regex("""(?i)\bmelted\b""") to "geschmolzen",
        Regex("""(?i)\bcrushed\b""") to "zerkrümelt",
        Regex("""(?i)\bhigh protein\b""") to "proteinreich",
        Regex("""(?i)\bper serving\b""") to "pro Portion",
        Regex("""(?i)\berytrit\b""") to "Erythrit",
    )

    fun translateNamesToGerman(text: String): String {
        return text.lines().joinToString("\n") { line ->
            var r = line
            for ((regex, de) in NAME_MAP) {
                r = regex.replace(r, de)
            }
            r
        }
    }

    /** Offline: Einheiten metrisch + Namen deutsch (ohne KI). */
    fun convertOfflineFull(text: String): String =
        translateNamesToGerman(convertUnitsToMetric(text))

    /**
     * KI: Zutaten + Zubereitung ins Deutsche übersetzen und metrisch umrechnen.
     * Titel optional mitübersetzen.
     *
     * Gemini und Groq laufen PARALLEL (select-Race); das erste erfolgreiche
     * Ergebnis gewinnt. Verhindert den bekannten 30s-Timeout, wenn nur Gemini
     * sequenziell aufgerufen wurde und dann erst der Fallback greifen konnte.
     */
    suspend fun convertWithAi(recipe: Recipe): Result<ConvertedRecipe> {
        val prompt = buildConvertPrompt(recipe)

        val rawResult = callLlm(prompt)
        val raw = rawResult.getOrElse {
            // Beide Provider fehlgeschlagen → offline nur Einheiten + Namensmap
            return Result.success(offlineFallback(recipe))
        }

        return runCatching {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val obj = JSONObject(cleaned)
            val ing = obj.optString("ingredients").ifBlank { recipe.ingredients }
            val ins = obj.optString("instructions").ifBlank { recipe.instructions }
            // Nachbearbeitung: Reste wie "dough"/"raw milk" offline nachziehen
            val desc = obj.optString("description").ifBlank { recipe.description }
            ConvertedRecipe(
                title = obj.optString("title").ifBlank { recipe.title },
                description = translateNamesToGerman(desc),
                ingredients = convertOfflineFull(ing),
                instructions = translateNamesToGerman(convertUnitsToMetric(ins))
            )
        }.recover {
            offlineFallback(recipe)
        }
    }

    private fun buildConvertPrompt(recipe: Recipe): String = """
Du bist ein Schweizer/deutscher Koch-Assistent. Übersetze das Rezept VOLLSTÄNDIG ins Deutsche und rechne alle Mengen metrisch um.

PFLICHT — 100 % Deutsch, null Englisch:
- JEDEN Zutatennamen komplett auf Deutsch. Keine Mischformen wie „Huhn Thigh Fillets“.
  Beispiele: chicken thigh fillets → Hähnchen-Oberschenkel-Filets,
  taco seasoning → Taco-Gewürz, chilli flakes → Chiliflocken,
  sweetcorn (drained weight) → Zuckermais (Abtropfgewicht),
  black beans → schwarze Bohnen, small handful of coriander → eine kleine Handvoll Koriander,
  greek yoghurt → griechischer Joghurt, sweet potatoes → Süsskartoffeln,
  salted butter → gesalzene Butter.
- Abschnittsüberschriften übersetzen und ALS EIGENE ZEILE behalten (ohne Bullet, ohne Zutat darunter):
  „For the Sauce“ → „Für die Sauce:“,
  „For the Sweet Potato Mash“ → „Für den Süsskartoffel-Stampf:“,
  „DOUGH“ → „Teig:“, „CINNAMON COFFEE FILLING“ → „Zimt-Kaffee-Füllung:“,
  „COFFEE SYRUP“ → „Kaffee-Sirup:“, „COFFEE CREAM CHEESE FROSTING“ → „Kaffee-Frischkäse-Frosting:“,
  dough → Teig, filling → Füllung, topping → Belag, frosting → Frosting/Glasur, syrup → Sirup.
  WICHTIG: Abschnittszeilen NIEMALS weglassen oder mit der nächsten Zutat zusammenführen.
  Format: eine Header-Zeile, darunter die Zutaten dieses Abschnitts (je Zeile eine Zutat).
- ALLE Zubereitungsschritte auf Deutsch.
- FESTE Zutaten in g, FLÜSSIGE in ml (nicht cups/tbsp/oz/°F).
- Markennamen dürfen bleiben. Keine Zutaten erfinden. Mengen sinnvoll runden.
- Wenn ein Wort unsicher ist: beste deutsche Küchenbezeichnung wählen, nicht Englisch stehen lassen.

Originaltitel: ${recipe.title}
Beschreibung:
${recipe.description}

Zutaten:
${recipe.ingredients}

Zubereitung:
${recipe.instructions}

Antworte NUR mit JSON:
{
  "title": "Deutscher Titel",
  "description": "Deutsche Beschreibung",
  "ingredients": "Zeile pro Zutat...",
  "instructions": "1. ...\n2. ..."
}
""".trimIndent()

    private fun offlineFallback(recipe: Recipe): ConvertedRecipe = ConvertedRecipe(
        title = translateNamesToGerman(recipe.title),
        description = translateNamesToGerman(recipe.description),
        ingredients = convertOfflineFull(recipe.ingredients),
        instructions = convertOfflineFull(recipe.instructions)
    )

    /**
     * Parallel-Race: Gemini und Groq gleichzeitig; erstes erfolgreiches Resultat gewinnt.
     * Identisches Muster wie in RecipeAiParser / GroqRecipeGeneratorService / GroqVisionService.
     */
    private suspend fun callLlm(prompt: String): Result<String> = coroutineScope {
        val apiKey = runCatching { BuildConfig.GROQ_API_KEY }.getOrElse { "" }

        if (!GeminiService.isAvailable()) {
            return@coroutineScope if (apiKey.isNotBlank()) callGroq(prompt, apiKey)
            else Result.failure(Exception("Weder Gemini- noch Groq-API-Key konfiguriert"))
        }

        val geminiJob: Deferred<Result<String>> = async {
            GeminiService.generateText(
                prompt = prompt,
                temperature = 0.2,
                maxTokens = 3000
            )
        }
        val groqJob: Deferred<Result<String>> = async {
            if (apiKey.isBlank()) Result.failure(Exception("Kein GROQ_API_KEY"))
            else callGroq(prompt, apiKey)
        }

        val (winnerJob, winnerResult) = select<Pair<Deferred<Result<String>>, Result<String>>> {
            geminiJob.onAwait { geminiJob to it }
            groqJob.onAwait { groqJob to it }
        }
        val loserJob = if (winnerJob === geminiJob) groqJob else geminiJob

        if (winnerResult.isSuccess) {
            loserJob.cancel()
            winnerResult
        } else {
            // Erster Provider fehlgeschlagen — auf den zweiten warten statt sofort aufzugeben
            loserJob.await()
        }
    }

    private fun callGroq(prompt: String, apiKey: String): Result<String> {
        return try {
            val requestJson = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.2)
                put("max_tokens", 3000)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                })
            }.toString()

            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(GROQ_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return Result.failure(Exception("Leere Groq-Antwort"))
            if (!response.isSuccessful) {
                return Result.failure(Exception("Groq Fehler ${response.code}: $bodyStr"))
            }

            val content = JSONObject(bodyStr)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(
                content.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
