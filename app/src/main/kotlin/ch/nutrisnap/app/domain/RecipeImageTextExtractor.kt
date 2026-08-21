package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * On-device OCR → strukturiertes Rezept (wie All My Meals).
 *
 * Ablauf:
 * 1. [OnDeviceTextRecognizer] liest Text aus dem Bild (offline, schnell).
 * 2. Heuristik prüft, ob der Text rezept-artig ist (Mengen + Zutaten-Keywords).
 * 3. Bei Erfolg: [RecipeAiParser] strukturiert den Text (wie Social-Caption).
 * 4. Qualitäts-Score auf Zutaten; bei Bedarf Vision parallel und Merge.
 *
 * Vorteil gegenüber reinem Vision: bessere Zutatentreue bei Screenshots/Rezeptkarten,
 * niedrigere Latenz, weniger API-Kosten. Vision bleibt für Collagen, Layout und
 * Bilder ohne lesbaren Text.
 */
object RecipeImageTextExtractor {

    /** Mindestlänge OCR-Text, darunter lohnt kein Parse-Versuch. */
    private const val MIN_OCR_CHARS = 40

    /** Mindestens so viele „Mengen-Zeilen“, damit es nach Zutatenliste aussieht. */
    private const val MIN_QTY_LINES = 2

    /**
     * Ab diesem Zutaten-Score gilt OCR als „stark genug“, Vision kann übersprungen werden
     * (außer Aufrufer will explizit Multi-Rezept-Vision).
     */
    internal const val STRONG_INGREDIENT_SCORE = 8

    private val QTY_LINE = Regex(
        """(?i)^\s*(?:[-•*·]\s*)?(?:\d+[.,]?\d*|\d+\s*/\s*\d+|[¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞])\s*""" +
            """(?:g|kg|ml|l|el|tl|tsp|tbsp|cup|cups|oz|lb|stück|stk|prise|bund|dose|packung|scheibe|zehe|clove|cloves)?\b"""
    )

    private val RECIPE_KEYWORDS = Regex(
        """(?i)\b(?:zutaten|ingredients|zubereitung|anleitung|method|instructions|""" +
            """portionen|servings?|serves|für\s+\d+|makes\s+\d+|prep(?:\s*time)?|cook(?:\s*time)?)\b"""
    )

    /**
     * Roh-OCR + optionales strukturiertes Rezept.
     * OCR-Text wird auch an Vision weitergereicht, wenn der Parse schwach ist.
     */
    data class OcrAttempt(
        val rawText: String?,
        val recipes: List<RecipeFromImageResult>?
    )

    /**
     * Versucht, ein Rezept rein über OCR + Caption-Parser zu extrahieren.
     * @return ein-elementige Liste bei Erfolg, sonst null (Vision-Fallback).
     */
    suspend fun tryExtract(bitmap: Bitmap, apiKey: String = BuildConfig.GROQ_API_KEY): List<RecipeFromImageResult>? =
        tryExtractDetailed(bitmap, apiKey).recipes

    /**
     * OCR einmal ausführen: Rohtext + ggf. geparstes Rezept.
     */
    suspend fun tryExtractDetailed(
        bitmap: Bitmap,
        apiKey: String = BuildConfig.GROQ_API_KEY
    ): OcrAttempt = withContext(Dispatchers.Default) {
        val ocrText = OnDeviceTextRecognizer.recognizeOrNull(bitmap)
        if (ocrText.isNullOrBlank()) return@withContext OcrAttempt(null, null)
        if (!looksLikeRecipeText(ocrText) || apiKey.isBlank()) {
            return@withContext OcrAttempt(ocrText, null)
        }

        val parsed = runCatching {
            RecipeAiParser.parse(
                caption = ocrText,
                sourceUrl = null,
                platform = "bild",
                imageUrl = null,
                apiKey = apiKey,
                fastModel = false
            )
        }.getOrNull()

        val recipes = if (parsed != null && isUsableRecipe(parsed)) {
            listOf(parsed.toImageResult())
        } else null

        OcrAttempt(ocrText, recipes)
    }

    /**
     * OCR und optional Vision: bei starkem OCR-Ergebnis nur OCR,
     * sonst Vision (mit OCR-Rohtext als Hint); wenn beide da sind → Feld-Merge.
     *
     * @param visionProvider erhält optionalen OCR-Rohtext für präzisere Zutaten
     */
    suspend fun extractWithVisionFallback(
        bitmap: Bitmap,
        visionProvider: suspend (ocrHint: String?) -> List<RecipeFromImageResult>
    ): List<RecipeFromImageResult> = coroutineScope {
        val ocrAttempt = tryExtractDetailed(bitmap)
        val ocrList = ocrAttempt.recipes
        val ocrHint = ocrAttempt.rawText
        val ocrScore = ocrList?.firstOrNull()?.let { ingredientQualityScore(it.ingredients) } ?: -1

        // Starke Einzel-OCR → Vision sparen (schnell + texttreu)
        if (ocrList != null && ocrList.size == 1 && ocrScore >= STRONG_INGREDIENT_SCORE) {
            return@coroutineScope ocrList
        }

        val visionResult = runCatching { visionProvider(ocrHint) }
        val visionList = visionResult.getOrDefault(emptyList())
        when {
            ocrList.isNullOrEmpty() && visionList.isEmpty() -> {
                visionResult.exceptionOrNull()?.let { throw it }
                emptyList()
            }
            ocrList.isNullOrEmpty() -> visionList
            visionList.isEmpty() -> ocrList
            // Vision hat mehrere Rezepte (Collage) → Vision priorisieren
            visionList.size > 1 -> visionList
            // Beide Einzelrezepte → Merge der stärksten Felder
            else -> listOf(mergeBest(ocrList.first(), visionList.first()))
        }
    }

    /**
     * Zutaten-Qualität: Mengenzeilen, Zeilenanzahl, Abzug für Junk.
     * Höher = bessere Extraktion für Tracking/Nährwerte.
     */
    internal fun ingredientQualityScore(ingredients: String): Int {
        val lines = ingredients.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0
        var score = 0
        var qty = 0
        var junk = 0
        for (line in lines) {
            if (RecipeAiParser.isJunkIngredientLine(line) || RecipeAiParser.isPromoIngredientNoise(line)) {
                junk++
                continue
            }
            score += 1
            if (QTY_LINE.containsMatchIn(line)) {
                qty++
                score += 2
            }
        }
        score += (qty * 1).coerceAtMost(6)
        score -= junk * 2
        return score.coerceAtLeast(0)
    }

    /**
     * Nimmt pro Feld die stärkere Variante:
     * - ingredients: höherer Quality-Score
     * - instructions: längere sinnvolle Anleitung
     * - title / description / macros: nicht-leer bevorzugt, sonst Gegenstück
     */
    internal fun mergeBest(a: RecipeFromImageResult, b: RecipeFromImageResult): RecipeFromImageResult {
        val aIng = ingredientQualityScore(a.ingredients)
        val bIng = ingredientQualityScore(b.ingredients)
        val ingredients = if (aIng >= bIng) a.ingredients else b.ingredients

        val instructions = pickLongerUseful(a.instructions, b.instructions)
        val title = pickBetterTitle(a.title, b.title)
        val description = a.description.trim().takeIf { it.isNotBlank() }
            ?: b.description.trim()

        val servings = when {
            a.servings > 1 -> a.servings
            b.servings > 1 -> b.servings
            else -> maxOf(a.servings, b.servings).coerceAtLeast(1)
        }

        return RecipeFromImageResult(
            title = title,
            description = description,
            ingredients = ingredients,
            instructions = instructions,
            servings = servings,
            prepTimeMinutes = a.prepTimeMinutes ?: b.prepTimeMinutes,
            cookTimeMinutes = a.cookTimeMinutes ?: b.cookTimeMinutes,
            caloriesPerServing = a.caloriesPerServing ?: b.caloriesPerServing,
            proteinPerServing = a.proteinPerServing ?: b.proteinPerServing,
            carbsPerServing = a.carbsPerServing ?: b.carbsPerServing,
            fatPerServing = a.fatPerServing ?: b.fatPerServing
        )
    }

    private fun pickLongerUseful(x: String, y: String): String {
        val xt = x.trim()
        val yt = y.trim()
        if (xt.isBlank()) return yt
        if (yt.isBlank()) return xt
        // Mehr Schritte / längerer Text meist besser
        val xSteps = xt.lines().count { it.isNotBlank() }
        val ySteps = yt.lines().count { it.isNotBlank() }
        return when {
            xSteps != ySteps -> if (xSteps > ySteps) xt else yt
            else -> if (xt.length >= yt.length) xt else yt
        }
    }

    private fun isPlaceholderTitle(t: String): Boolean {
        val lower = t.lowercase().trim()
        return lower in setOf("rezept", "recipe", "rezept aus bild", "instagram rezept", "untitled")
    }

    /** Längerer, nicht-generischer Titel gewinnt. */
    private fun pickBetterTitle(a: String, b: String): String {
        val at = a.trim()
        val bt = b.trim()
        val aOk = at.isNotBlank() && !isPlaceholderTitle(at)
        val bOk = bt.isNotBlank() && !isPlaceholderTitle(bt)
        return when {
            aOk && bOk -> if (bt.length > at.length) bt else at
            aOk -> at
            bOk -> bt
            else -> at.ifBlank { bt }
        }
    }

    /** Heuristik: genug Text + Mengenzeilen oder klare Rezept-Keywords. */
    internal fun looksLikeRecipeText(text: String): Boolean {
        val cleaned = text.trim()
        if (cleaned.length < MIN_OCR_CHARS) return false
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 3) return false
        val qtyCount = lines.count { QTY_LINE.containsMatchIn(it) }
        if (qtyCount >= MIN_QTY_LINES) return true
        return RECIPE_KEYWORDS.containsMatchIn(cleaned) && qtyCount >= 1
    }

    private fun isUsableRecipe(r: Recipe): Boolean {
        val ingredients = r.ingredients.trim()
        if (ingredients.isBlank() || ingredients.equals("Zutaten nicht gefunden.", ignoreCase = true)) {
            return false
        }
        val ingredientLines = ingredients.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (ingredientLines.size < 2) return false
        val qtyHits = ingredientLines.count { QTY_LINE.containsMatchIn(it) }
        return qtyHits >= 1 || ingredientLines.size >= 3
    }

    private fun Recipe.toImageResult(): RecipeFromImageResult {
        val servingsSafe = servings.coerceAtLeast(1)
        val calPer = totalCalories?.let { if (servingsSafe > 0) it / servingsSafe else it }
        return RecipeFromImageResult(
            title = title,
            description = description,
            ingredients = ingredients,
            instructions = instructions,
            servings = servingsSafe,
            prepTimeMinutes = prepTimeMinutes,
            cookTimeMinutes = null,
            caloriesPerServing = calPer,
            proteinPerServing = proteinPerServing,
            carbsPerServing = carbsPerServing,
            fatPerServing = fatPerServing
        )
    }
}
