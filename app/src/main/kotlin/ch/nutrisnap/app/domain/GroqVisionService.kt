package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import ch.nutrisnap.app.BuildConfig
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.selects.select
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

@Serializable
data class FridgeScanResult(
    val ingredients: List<String> = emptyList()
)

/** Einzelne, vom Foto separierte Zutat eines Gerichts (vor DB-Abgleich). */
@Serializable
data class DishIngredientCandidate(
    val name: String = "",
    val estimatedGrams: Float = 0f,
    /** "hoch", "mittel" oder "niedrig" — Sicherheit der Erkennung DIESER Zutat. */
    val confidence: String = "mittel"
)

/** Ergebnis der mehrstufigen Foto-Analyse: Gericht zerlegt in einzelne Zutaten. */
@Serializable
data class DishScanResult(
    val dishName: String = "",
    val ingredients: List<DishIngredientCandidate> = emptyList()
)

@Serializable
data class NutritionLabelResult(
    val caloriesPer100g: Float = 0f,
    val proteinPer100g: Float = 0f,
    val carbsPer100g: Float = 0f,
    val fatPer100g: Float = 0f,
    val fiberPer100g: Float = 0f,
    val sugarPer100g: Float = 0f,
    val saltPer100g: Float = 0f,
    val productName: String = "",
    val brand: String = ""
)

/** Aus einem Rezept-Foto / Screenshot extrahiertes Rezept. */
@Serializable
data class RecipeFromImageResult(
    val title: String = "",
    val description: String = "",
    val ingredients: String = "",
    val instructions: String = "",
    val servings: Int = 1,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val caloriesPerServing: Float? = null,
    val proteinPerServing: Float? = null,
    val carbsPerServing: Float? = null,
    val fatPerServing: Float? = null
)

/** Wrapper für ein oder mehrere Rezepte auf demselben Foto (Kochbuchseite, Collage, Screenshot). */
@Serializable
data class RecipesFromImageResult(
    val recipes: List<RecipeFromImageResult> = emptyList()
)

/**
 * Nutzt Groq's multimodales Vision-Modell um Fotos zu analysieren.
 * Gleicher kostenloser Groq-Tier wie GroqRecipeGeneratorService, gleicher API-Key
 * (BuildConfig.GROQ_API_KEY, via GitHub Actions Secret injiziert).
 */
class GroqVisionService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    companion object {
        /**
         * Groq Vision-Modelle in Prioritätsreihenfolge.
         * qwen3.6-27b ist offiziell dokumentiert, liefert aber bei manchen Keys
         * model_not_found (404) — deshalb 3.8 zuerst, dann Fallbacks.
         * @see https://console.groq.com/docs/vision
         */
        private val VISION_MODELS = listOf(
            "qwen/qwen3.8-27b",
            "qwen/qwen3.6-27b",
            "meta-llama/llama-4-scout-17b-16e-instruct"
        )
        /** Standard für Food-/Fridge-Fotos – hält Base64 unter typischen API-Limits. */
        private const val MAX_DIMENSION = 1024
        /**
         * Höhere Kante für textlastige Bilder (Rezeptkarte, Kochbuch, Screenshot).
         * Lesbarkeit von Zutatenlisten profitiert stärker von Auflösung als Food-Fotos.
         */
        private const val MAX_DIMENSION_TEXT = 1536
        /** Einfache Anführungszeichen als JSON-String-Delimiter → doppelte. */
        private val SINGLE_QUOTED_JSON = Regex("""'((?:[^'\\]|\\.)*)'""")
    }

    /** Komprimiert ein Foto auf eine fuer die API geeignete Groesse und kodiert es als Base64-JPEG. */
    fun bitmapToBase64Jpeg(bitmap: Bitmap, quality: Int = 70): String {
        val scaled = scaleDown(bitmap, MAX_DIMENSION)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * Vorbereitung für textlastige Vision-Calls (Rezept aus Bild/Screenshot/Kochbuch):
     * etwas höhere Auflösung, leichter Kontrast + Entsättigung (Screenshot-Noise),
     * höhere JPEG-Qualität. Payload bleibt typischerweise unter 4 MB.
     */
    fun bitmapToBase64JpegForText(bitmap: Bitmap, quality: Int = 85): String {
        val scaled = scaleDown(bitmap, MAX_DIMENSION_TEXT)
        val enhanced = runCatching { enhanceForTextReadability(scaled) }.getOrDefault(scaled)
        val stream = ByteArrayOutputStream()
        enhanced.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(60, 95), stream)
        if (enhanced !== scaled && enhanced !== bitmap) {
            runCatching { enhanced.recycle() }
        }
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }

    /**
     * Leichte Kontrast-Anhebung und leichte Entsättigung – hilft bei verwaschenen
     * Screenshots und schwach belichteten Kochbuchseiten, ohne Farben komplett zu zerstören
     * (Food-Fotos nutzen diesen Pfad nicht).
     */
    internal fun enhanceForTextReadability(src: Bitmap): Bitmap {
        val contrast = 1.22f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        // Kontrast-Matrix
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        // Leicht entsättigen (UI-Screenshots: farbiges Chrome stört Text weniger)
        val satMatrix = ColorMatrix().apply { setSaturation(0.82f) }
        contrastMatrix.postConcat(satMatrix)

        val config = src.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(src.width, src.height, config)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(contrastMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /**
     * Zerlegt ein Foto eines Gerichts in seine einzelnen sichtbaren Zutaten (statt eines
     * pauschalen Gesamteintrags). Robust gegenüber Tellergerichten/Bowls mit mehreren,
     * leicht überlappenden Komponenten. Deckt gleichzeitig die Stufen "Zutaten erkennen"
     * und "Zutaten trennen" ab, da beides ein einzelner Vision-Call ist.
     */
    suspend fun analyzeDishIngredients(base64Jpeg: String): Result<DishScanResult> = withContext(Dispatchers.IO) {
        val prompt = """
Du bist ein erfahrener Schweizer Ernährungsberater und Food-Vision-Experte. Analysiere das Foto eines Gerichts und zerlege es in seine einzelnen sichtbaren Bestandteile. Schätze die Portionsgrösse jeder Zutat in Gramm.

WICHTIGE SCHWEIZER PRIORITÄTEN (strikt beachten):

1. Bei cremigen, breiigen, klumpigen oder feinkörnigen Massen (grau, mauve, violett, beige, bräunlich):
   - Birchermüesli / Bircher / Overnight Oats / Müesli mit Joghurt hat HÖCHSTE Priorität.
   - Viele Birchermüesli (besonders mit Beeren, Zwetschgen, Heidelbeeren oder dunklem Joghurt) sind grau-violett oder mauve und können homogen/klumpig wirken – das ist NORMAL und kein Ausschlusskriterium.
   - Fleischsalat, Fleischpastete, Pâté oder Wurstsalat erst in Betracht ziehen, wenn klar sichtbare Fleisch-/Wurststreifen, Mayonnaise-Glanz und typische Randen-/Peperoni-Stückchen vorhanden sind UND keine Hafer-/Getreide-Textur erkennbar ist.
   - Farbe allein entscheidet NIE gegen Birchermüesli.

2. Bei dunklem Brot + rotem/glänzendem Aufstrich:
   - Typisch: Ruchbrot / Vollkornbrot / Parapan + Konfitüre / Marmelade.
   - Nicht automatisch als "Brot mit Fleischwurst" o.ä. interpretieren.

3. Bei dünnen, dunkelroten, trocken-faserigen Fleischscheiben:
   - Bevorzuge Trockenfleisch / Bündnerfleisch / Bündnerfleisch-ähnlich vor normalem Rohschinken.

4. Spiesse mit orangen Würfeln + roten Beeren + grüner Kugel = Melone + Erdbeere + Traube (klassisch).

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "dishName": "Kurze Gesamtbezeichnung des Gerichts (z.B. Schweizer Picknick-Platte)",
  "ingredients": [
    {"name": "Birchermüesli", "estimatedGrams": 120, "confidence": "mittel"},
    {"name": "Vollkornbrot mit Konfitüre", "estimatedGrams": 60, "confidence": "hoch"},
    {"name": "Trockenfleisch", "estimatedGrams": 40, "confidence": "hoch"},
    {"name": "Melone", "estimatedGrams": 100, "confidence": "hoch"},
    {"name": "Erdbeeren", "estimatedGrams": 30, "confidence": "hoch"}
  ]
}

confidence ist "hoch", "mittel" oder "niedrig". Erfinde keine Zutaten, die nicht sichtbar sind. Liste jede Zutat nur einmal.
""".trimIndent()
        // Hoeheres Token-Limit als Standard-1000: bei vielen kleinen Zutaten (Bowls, Mezze-Teller)
        // braucht die JSON-Antwort mit einem Eintrag pro Zutat mehr Platz als eine einzelne Schaetzung.
        callVisionRaw(prompt, listOf(base64Jpeg), maxTokens = 2000).mapCatching {
            json.decodeFromString<DishScanResult>(sanitizeLlmJson(it))
        }
    }

    /** Erkennt vorhandene Zutaten auf einem Foto (z.B. offener Kühlschrank/Vorratsschrank). */
    suspend fun analyzeFridgePhoto(base64Jpeg: String): Result<FridgeScanResult> = withContext(Dispatchers.IO) {
        val prompt = """
Du siehst ein Foto von einem Kühlschrank, Vorratsschrank oder einer Ansammlung von Lebensmitteln.
Identifiziere ALLE klar erkennbaren Lebensmittel/Zutaten auf dem Foto. Sei konkret (z.B. "Rüebli" statt "Gemüse",
"Naturejoghurt" statt "Milchprodukt"), aber erfinde nichts, was nicht wirklich zu sehen ist.
Ignoriere nicht-essbare Dinge.

Antworte NUR mit gültigem JSON (kein Markdown, keine Erklärungen).
Verwende ausschließlich doppelte Anführungszeichen ("):
{
  "ingredients": ["Rüebli", "Naturejoghurt", "Eier", "Zwiebeln"]
}
""".trimIndent()
        callVisionRaw(prompt, listOf(base64Jpeg)).mapCatching {
            json.decodeFromString<FridgeScanResult>(sanitizeLlmJson(it))
        }
    }

    /** Liest eine fotografierte Nährwerttabelle aus und gibt die Werte pro 100g zurück. */
    suspend fun analyzeNutritionLabel(base64Jpeg: String): Result<NutritionLabelResult> = withContext(Dispatchers.IO) {
        val prompt = """
Auf dem Foto ist eine Nährwerttabelle (von einer Lebensmittelverpackung) zu sehen.
Lies die Werte PRO 100g/100ml aus der Tabelle ab. Falls die Tabelle nur Werte pro Portion zeigt
und die Portionsgrösse erkennbar ist, rechne korrekt auf 100g um.

Lies auch Produktname und Marke, falls sichtbar (auch bei gedrehtem/spiegelverkehrtem Text).

Antworte NUR mit gültigem, VOLLSTÄNDIGEM JSON (kein Markdown, keine Erklärungen).
Verwende ausschließlich doppelte Anführungszeichen ("), niemals einfache (').
Alle Zahlenfelder sind Pflicht (fehlend = 0). productName/brand dürfen "" sein.
{
  "caloriesPer100g": 250,
  "proteinPer100g": 12.0,
  "carbsPer100g": 30.0,
  "fatPer100g": 8.0,
  "fiberPer100g": 3.0,
  "sugarPer100g": 5.0,
  "saltPer100g": 1.0,
  "productName": "Produktname",
  "brand": "Marke"
}
""".trimIndent()
        // 2048: kurze Label-Antwort, aber genug Reserve falls das Modell
        // trotzdem Reasoning-Tokens verbraucht (sonst abgeschnittenes JSON).
        callVisionRaw(prompt, listOf(base64Jpeg), maxTokens = 2048).mapCatching {
            parseNutritionLabelJson(it)
        }
    }

    /**
     * Liest ein oder mehrere Rezepte von einem Foto/Screenshot (Rezeptkarte, Kochbuchseite,
     * Blog-Screenshot, Collage). Gibt eine Liste strukturierter Rezepte zurück.
     * Ein einzelnes Rezept kommt als Liste mit einem Element.
     *
     * @param ocrHint optionaler Rohtext aus on-device OCR – hilft bei Mengen/Zutatennamen,
     *                wenn das Bild unscharf oder textlastig ist (All-My-Meals-Style).
     */
    suspend fun extractRecipesFromImage(
        base64Jpeg: String,
        ocrHint: String? = null
    ): Result<List<RecipeFromImageResult>> =
        extractRecipesFromImages(listOf(base64Jpeg), ocrHint)

    /**
     * Mehrere Screenshots/Fotos desselben oder mehrerer Rezepte (z.B. TikTok-Caption
     * über 2–3 Screenshots). Bilder + optionaler kombinierter OCR-Hinweis.
     */
    suspend fun extractRecipesFromImages(
        base64Jpegs: List<String>,
        ocrHint: String? = null
    ): Result<List<RecipeFromImageResult>> =
        withContext(Dispatchers.IO) {
            val images = base64Jpegs.filter { it.isNotBlank() }
            if (images.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Keine Bilder übergeben"))
            }
            val multiNote = if (images.size > 1) {
                """
Du siehst ${images.size} Screenshots/Fotos. Oft ist es EIN Rezept, das über mehrere
Bilder verteilt ist (Caption „more“, Zutaten auf Bild 1, Schritte auf Bild 2).
Führe zusammengehörige Teile zu EINEM Rezept zusammen. Nur wenn klar getrennte
verschiedene Gerichte sichtbar sind, mehrere Einträge in "recipes".
"""
            } else ""
            val ocrBlock = ocrHint?.trim()?.takeIf { it.length >= 20 }?.let { text ->
                val clipped = text.take(6000)
                """

Zusätzlicher OCR-Rohtext vom Gerät (kann Fehler enthalten, Mengen/Namen aber oft treuer als reine Bildanalyse).
Nutze ihn zur Korrektur von Zutaten und Mengenangaben, wenn er zum Bild passt.
Erfinde nichts, was weder im Bild noch im OCR-Text steht.

--- OCR START ---
$clipped
--- OCR ENDE ---
"""
            }.orEmpty()

            val prompt = """
Du siehst ein Foto oder einen Screenshot von einem oder mehreren Rezepten
(Rezeptkarte, Kochbuchseite, Blog, Social Media wie TikTok/Instagram/Reels, Notiz, Collage).
$multiNote
Aufgabe:
- Wenn MEHRERE klar getrennte Rezepte sichtbar sind (z.B. zwei Rezepte auf einer Kochbuchseite,
  Collage, mehrere Karten), extrahiere JEDES als eigenen Eintrag in "recipes".
- Wenn nur EIN Rezept sichtbar ist (auch über mehrere Caption-Abschnitte / „more“-Text),
  liefere genau einen Eintrag in "recipes".
- Teile nicht willkürlich Abschnitte desselben Rezepts (z.B. Teig/Füllung oder Oats/Topping)
  in mehrere Rezepte — Abschnitte gehören in ingredients mit Überschrift.

Social-Media-Screenshots (TikTok, Instagram, Reels):
- UI-Chrome komplett ignorieren: Statusleiste, Like/Comment/Share-Zahlen, Profilbild,
  Suchleiste, Tab-Leiste, „LIVE“, „For You“, „Following“, Watermarks, Sticker.
- Nur Rezeptinhalt: Titel, Zutaten, Mengen, Zubereitungsschritte, sichtbare Nährwerte.
- Werbung, Ebook-Links, „Link in bio“, Rabattcodes, Hashtags, Emotes ohne Inhalt weglassen.
- Wenn Mengen sowohl imperial als auch metrisch stehen (z.B. „1/2 cup (125 g)“),
  die metrische Angabe bevorzugen.

Regeln pro Rezept:
- IMMER auf Deutsch ausgeben: title, description, ingredients, instructions.
  Englische Quelltexte sauber ins Deutsche übersetzen (Zutatenamen + Anleitung).
- ingredients: jede Zutat in einer eigenen Zeile, mit Menge in g/ml/EL/TL wo möglich.
  US-Mengen (cup, tbsp, tsp, oz) in sinnvolle metrische Werte umrechnen, wenn im Bild
  keine Metrik steht (übliche Küchenumrechnung). Gruppiere optional mit Überschriften
  wie „Oats:“ / „Topping:“ wenn das Bild Abschnitte zeigt.
- instructions: nummerierte Schritte auf Deutsch, einer pro Zeile (1. … 2. …).
- Wenn Nährwerte pro Portion sichtbar sind (kcal, Protein, KH, Fett, Ballaststoffe), übernimm sie.
- Wenn Anzahl Portionen / servings sichtbar ist, übernimm sie; sonst servings = 1.
- Zeiten in Minuten umrechnen falls nötig (z.B. 1 h → 60).
- Titel: klarer Rezeptname auf Deutsch; wenn keiner lesbar, kurze sinnvolle Bezeichnung.
- Erfinde keine Zutaten/Schritte/Nährwerte, die weder im Bild noch im OCR stehen.
  Unleserliche Teile weglassen.
$ocrBlock
Antworte NUR mit folgendem JSON (kein Markdown):
{
  "recipes": [
    {
      "title": "Rezepttitel auf Deutsch",
      "description": "Kurze Beschreibung auf Deutsch falls vorhanden, sonst leer",
      "ingredients": "100 g Hüttenkäse\n60 ml Milch\n...",
      "instructions": "1. Ofen vorheizen…\n2. Alles vermengen…",
      "servings": 1,
      "prepTimeMinutes": 15,
      "cookTimeMinutes": 0,
      "caloriesPerServing": 142,
      "proteinPerServing": 13,
      "carbsPerServing": 14,
      "fatPerServing": 3
    }
  ]
}
""".trimIndent()
            callVisionRaw(prompt, images, maxTokens = 4000).mapCatching { raw ->
                parseRecipesFromImageJson(raw).map { extracted ->
                    val normalized = normalizeExtractedRecipe(extracted)
                    // Zweiter Text-Pass: saubere DE/metrische Zutaten & Schritte ohne Bild
                    polishExtractedRecipe(normalized)
                }
            }
        }

    /**
     * Nach Vision: reiner Text-Cleanup (kein Bild). Deutsch, metrisch, Promo raus,
     * nummerierte Schritte – wie manuelles „Claude-Format“.
     */
    internal suspend fun polishExtractedRecipe(raw: RecipeFromImageResult): RecipeFromImageResult {
        if (raw.ingredients.isBlank() && raw.instructions.isBlank()) return raw
        val prompt = """
Du bereinigst ein extrahiertes Rezept. Ausgabe NUR als JSON (kein Markdown).

Regeln:
- title, description, ingredients, instructions auf Deutsch
- ingredients: eine Zutat pro Zeile, Mengen in g/ml/EL/TL; Abschnittsüberschriften erlaubt (z.B. „Topping:“)
- instructions: nummeriert (1. … 2. …), klar und knapp
- STRENG entfernen: TikTok/Instagram-UI (For You, Following, Community, LIVE, Inbox, Profile,
  Like-/Share-Zahlen wie 230.7K), Creator-Namen, Ebook-Werbung, Link in bio, Hashtags
- Keine englischen UI-Reste in Zutaten oder Anleitung
- Nährwerte und servings nur behalten wenn sinnvoll; sonst weglassen/null
- Erfinde keine neuen Zutaten; nur glätten, übersetzen, metrisch machen
- Wenn Eingabe voller Müll ist, behalte nur klar erkennbare Lebensmittelzeilen mit Mengen

Eingabe:
${JSONObject().apply {
            put("title", raw.title)
            put("description", raw.description)
            put("ingredients", raw.ingredients)
            put("instructions", raw.instructions)
            put("servings", raw.servings)
            raw.prepTimeMinutes?.let { put("prepTimeMinutes", it) }
            raw.proteinPerServing?.let { put("proteinPerServing", it) }
            raw.caloriesPerServing?.let { put("caloriesPerServing", it) }
        }}

JSON-Schema:
{"title":"…","description":"…","ingredients":"…","instructions":"…","servings":1,"prepTimeMinutes":null,"caloriesPerServing":null,"proteinPerServing":null,"carbsPerServing":null,"fatPerServing":null}
""".trimIndent()

        val textResult = if (GeminiService.isUsable()) {
            GeminiService.generateText(prompt = prompt, temperature = 0.2, maxTokens = 2500)
        } else {
            Result.failure(Exception("Kein Text-Modell"))
        }
        val cleaned = textResult.getOrNull()?.trim()
            ?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim()
            ?: return raw
        return runCatching {
            val parsed = json.decodeFromString<RecipeFromImageResult>(cleaned)
            normalizeExtractedRecipe(
                parsed.copy(
                    // Nährwerte: poliertes behalten wenn gesetzt, sonst Original
                    caloriesPerServing = parsed.caloriesPerServing ?: raw.caloriesPerServing,
                    proteinPerServing = parsed.proteinPerServing ?: raw.proteinPerServing,
                    carbsPerServing = parsed.carbsPerServing ?: raw.carbsPerServing,
                    fatPerServing = parsed.fatPerServing ?: raw.fatPerServing,
                    servings = if (parsed.servings > 0) parsed.servings else raw.servings
                )
            )
        }.getOrDefault(raw)
    }

    /**
     * Rückwärtskompatibel: liefert das erste erkannte Rezept (oder Fehler wenn keines).
     * Für neue Flows bevorzugt [extractRecipesFromImage] verwenden.
     */
    suspend fun extractRecipeFromImage(
        base64Jpeg: String,
        ocrHint: String? = null
    ): Result<RecipeFromImageResult> =
        extractRecipesFromImages(listOf(base64Jpeg), ocrHint).mapCatching { list ->
            list.firstOrNull()
                ?: throw IllegalStateException("Kein Rezept im Bild erkannt")
        }

    /**
     * Parst Vision-JSON robust: neues Format {"recipes":[...]} und Legacy-Einzelobjekt.
     */
    private fun parseRecipesFromImageJson(rawInput: String): List<RecipeFromImageResult> {
        val raw = sanitizeLlmJson(rawInput)
        // Primär: Array-Wrapper
        runCatching {
            val wrapped = json.decodeFromString<RecipesFromImageResult>(raw)
            if (wrapped.recipes.isNotEmpty()) {
                return wrapped.recipes.filter { it.title.isNotBlank() || it.ingredients.isNotBlank() }
            }
        }
        // Fallback: Modell liefert noch ein einzelnes Rezept-Objekt ohne "recipes"
        runCatching {
            val single = json.decodeFromString<RecipeFromImageResult>(raw)
            if (single.title.isNotBlank() || single.ingredients.isNotBlank()) {
                return listOf(single)
            }
        }
        // Fallback: manuell "recipes"-Array aus org.json lesen (falls Serialization streikt)
        runCatching {
            val root = JSONObject(raw)
            if (root.has("recipes")) {
                val arr = root.getJSONArray("recipes")
                val out = mutableListOf<RecipeFromImageResult>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val item = json.decodeFromString<RecipeFromImageResult>(obj.toString())
                    if (item.title.isNotBlank() || item.ingredients.isNotBlank()) out.add(item)
                }
                if (out.isNotEmpty()) return out
            }
        }
        return emptyList()
    }

    /**
     * Post-Processing nach Vision-Extrakt (deterministisch, offline):
     * - Junk-Filter / Zeilen-Split wie Caption-Import ([RecipeAiParser.formatIngredientText])
     * - Imperial → metrisch (cups/tbsp/… → g/ml) via [RecipeGermanMetricConverter]
     * - Whitespace/Leerzeilen bereinigen
     * - servings mindestens 1
     * - leerer Titel → kurzer Fallback aus erster Zutatenzeile
     * - negative/unsinnige Nährwerte verwerfen
     */
    internal fun normalizeExtractedRecipe(raw: RecipeFromImageResult): RecipeFromImageResult {
        fun cleanBlock(text: String): String =
            text.lines()
                .map { it.trimEnd() }
                .dropWhile { it.isBlank() }
                .dropLastWhile { it.isBlank() }
                .joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")

        // Gleicher Cleanup wie Social-Caption-Import (Header, Makros, Promo, Multi-Zutaten-Zeilen)
        val ingredientsFormatted = runCatching {
            RecipeAiParser.formatIngredientText(raw.ingredients)
        }.getOrDefault(raw.ingredients)
        val instructionsFormatted = runCatching {
            RecipeAiParser.formatInstructionsText(raw.instructions)
        }.getOrDefault(raw.instructions)

        val ingredientsMetric = runCatching {
            RecipeGermanMetricConverter.convertUnitsToMetric(ingredientsFormatted)
        }.getOrDefault(ingredientsFormatted)
        val instructionsMetric = runCatching {
            RecipeGermanMetricConverter.convertUnitsToMetric(instructionsFormatted)
        }.getOrDefault(instructionsFormatted)

        val ingredients = cleanBlock(ingredientsMetric)
        val instructions = cleanBlock(instructionsMetric)
        val description = cleanBlock(raw.description)
        val title = raw.title.trim().ifBlank {
            ingredients.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() && !it.endsWith(":") }
                ?.take(48)
                ?.let { "Rezept: $it" }
                ?: "Rezept aus Bild"
        }

        fun saneNutrient(v: Float?): Float? =
            v?.takeIf { it.isFinite() && it >= 0f && it < 10_000f }

        return raw.copy(
            title = title,
            description = description,
            ingredients = ingredients,
            instructions = instructions,
            servings = raw.servings.coerceAtLeast(1),
            prepTimeMinutes = raw.prepTimeMinutes?.takeIf { it in 0..24 * 60 },
            cookTimeMinutes = raw.cookTimeMinutes?.takeIf { it in 0..24 * 60 },
            caloriesPerServing = saneNutrient(raw.caloriesPerServing),
            proteinPerServing = saneNutrient(raw.proteinPerServing),
            carbsPerServing = saneNutrient(raw.carbsPerServing),
            fatPerServing = saneNutrient(raw.fatPerServing)
        )
    }

    /**
     * Ruft Gemini (primary) und Groq (fallback) PARALLEL auf und nimmt das erste
     * erfolgreiche Ergebnis. Vorher liefen beide Calls sequenziell (Gemini bis zu
     * 28s Timeout, danach erst Groq) — das war die Ursache der ~30s-Verzögerung.
     * Jetzt läuft Groq bereits mit, während Gemini noch wartet; schlägt einer der
     * beiden fehl, wird auf das Ergebnis des anderen gewartet statt neu zu starten.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun callVisionRaw(
        prompt: String,
        base64Jpegs: List<String>,
        maxTokens: Int = 1000
    ): Result<String> = coroutineScope {
        val images = base64Jpegs.filter { it.isNotBlank() }
        if (images.isEmpty()) {
            return@coroutineScope Result.failure(IllegalArgumentException("Keine Bilder"))
        }
        val groqDeferred: Deferred<Result<String>> =
            async(Dispatchers.IO) { callGroqVision(prompt, images, maxTokens) }

        // Quota-Pause: Gemini überspringen, nur Groq (kein nutzloser 429-Roundtrip).
        if (!GeminiService.isUsable()) return@coroutineScope groqDeferred.await()

        val geminiDeferred: Deferred<Result<String>> = async(Dispatchers.IO) {
            GeminiService.generateVision(
                prompt = prompt,
                base64Jpegs = images,
                temperature = 0.3,
                maxTokens = maxTokens
            )
        }

        val result = select<Result<String>> {
            geminiDeferred.onAwait { r -> if (r.isSuccess) r else groqDeferred.await() }
            groqDeferred.onAwait { r -> if (r.isSuccess) r else geminiDeferred.await() }
        }
        geminiDeferred.cancel()
        groqDeferred.cancel()
        // Beide fehlgeschlagen: kurze, lesbare Meldung statt API-JSON
        if (result.isFailure) {
            val msg = result.exceptionOrNull()?.message.orEmpty()
            if (msg.contains("{") || msg.length > 180) {
                return@coroutineScope Result.failure(
                    Exception("Analyse fehlgeschlagen. Bitte erneut versuchen.")
                )
            }
        }
        result
    }

    private fun callGroqVision(
        prompt: String,
        base64Jpegs: List<String>,
        maxTokens: Int = 1000
    ): Result<String> {
        return try {
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) return Result.failure(Exception(
                "Kein GROQ_API_KEY in local.properties konfiguriert"
            ))

            val content = JSONArray().apply {
                put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                // Max. 4 Screenshots – Payload-Limit; Reihenfolge = Nutzer-Auswahl
                base64Jpegs.take(4).forEach { b64 ->
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$b64")
                        })
                    })
                }
            }

            var lastError: Exception? = null
            for (modelId in VISION_MODELS) {
                val requestJson = JSONObject().apply {
                    put("model", modelId)
                    put("temperature", 0.3)
                    put("max_completion_tokens", maxTokens)
                    // Nur Qwen-Familie: Reasoning abschalten, sonst fressen Thinking-Tokens
                    // das gesamte max_completion_tokens-Budget → abgeschnittenes JSON.
                    if (modelId.startsWith("qwen/")) {
                        put("reasoning_effort", "none")
                    }
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", content)
                        })
                    })
                }.toString()

                val requestBody = requestJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val notFound = response.code == 404 ||
                        bodyStr.contains("model_not_found", ignoreCase = true) ||
                        bodyStr.contains("does not exist", ignoreCase = true)
                    if (notFound) {
                        lastError = Exception("Modell $modelId nicht verfügbar")
                        continue
                    }
                    val short = bodyStr.take(160).replace(Regex("\\s+"), " ")
                    return Result.failure(
                        Exception("Groq Vision fehlgeschlagen (${response.code}): $short")
                    )
                }
                if (bodyStr.isBlank()) {
                    lastError = Exception("Leere Antwort von $modelId")
                    continue
                }
                val root = JSONObject(bodyStr)
                val text = root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content", "")
                if (text.isBlank()) {
                    lastError = Exception("Leerer Content von $modelId")
                    continue
                }
                return Result.success(sanitizeLlmJson(text))
            }
            Result.failure(
                lastError ?: Exception("Kein Groq-Vision-Modell verfügbar")
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Robustes Parsing der Nährwert-JSON-Antwort (auch bei abgeschnittenem JSON). */
    private fun parseNutritionLabelJson(raw: String): NutritionLabelResult {
        val cleaned = repairTruncatedJson(sanitizeLlmJson(raw))
        runCatching {
            return json.decodeFromString<NutritionLabelResult>(cleaned)
        }
        // Fallback: org.json – einzelne Felder opt*
        val obj = runCatching { JSONObject(cleaned) }.getOrElse {
            throw IllegalArgumentException(
                "Nährwert-JSON unvollständig. Bitte Foto nochmal scannen."
            )
        }
        fun num(key: String): Float =
            obj.optDouble(key, 0.0).toFloat().takeIf { !it.isNaN() } ?: 0f
        return NutritionLabelResult(
            caloriesPer100g = num("caloriesPer100g"),
            proteinPer100g = num("proteinPer100g"),
            carbsPer100g = num("carbsPer100g"),
            fatPer100g = num("fatPer100g"),
            fiberPer100g = num("fiberPer100g"),
            sugarPer100g = num("sugarPer100g"),
            saltPer100g = num("saltPer100g"),
            productName = obj.optString("productName", ""),
            brand = obj.optString("brand", "")
        )
    }

    /**
     * Schließt abgeschnittenes JSON so weit wie möglich:
     * offene Strings beenden, fehlende Klammern ergänzen.
     * Reicht oft, damit opt*-Parsing die bereits gelesenen Felder rettet.
     */
    private fun repairTruncatedJson(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return "{}"
        // Offenen String am Ende schließen (häufig bei Token-Limit)
        if (s.count { it == '"' } % 2 != 0) s += '"'
        // Fehlende schließende Klammern
        val openObj = s.count { it == '{' }
        val closeObj = s.count { it == '}' }
        if (openObj > closeObj) s += "}".repeat(openObj - closeObj)
        val openArr = s.count { it == '[' }
        val closeArr = s.count { it == ']' }
        if (openArr > closeArr) s += "]".repeat(openArr - closeArr)
        // Trailing Komma vor } entfernen
        s = s.replace(Regex(",\\s*}"), "}")
        s = s.replace(Regex(",\\s*]"), "]")
        return s
    }

    /**
     * Bereinigt LLM-JSON, das oft leicht invalid ist:
     * Markdown-Fences, Smart-Quotes, einfache Anführungszeichen als String-Delimiter,
     * führender/nachgestellter Fließtext um das Objekt herum.
     */
    private fun sanitizeLlmJson(raw: String): String {
        var s = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        // Typografische Anführungszeichen → ASCII
        s = s
            .replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u201e', '"').replace('\u00ab', '"').replace('\u00bb', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')
        // Äußerstes JSON-Objekt extrahieren (falls Modell Text drumherum schreibt)
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1)
        }
        // '…' String-Literale → "…"
        s = SINGLE_QUOTED_JSON.replace(s) { m ->
            val inner = m.groupValues[1]
                .replace("\"", "\\\"")
                .replace("\\'", "'")
            "\"$inner\""
        }
        return s
    }
}
