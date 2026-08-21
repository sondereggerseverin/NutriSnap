package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import ch.nutrisnap.app.BuildConfig
import ch.nutrisnap.app.data.model.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device OCR → strukturiertes Rezept (wie All My Meals).
 *
 * Ablauf:
 * 1. [OnDeviceTextRecognizer] liest Text aus dem Bild (offline, schnell).
 * 2. Heuristik prüft, ob der Text rezept-artig ist (Mengen + Zutaten-Keywords).
 * 3. Bei Erfolg: [RecipeAiParser] strukturiert den Text (wie Social-Caption).
 * 4. Bei zu wenig Text / unklarer Struktur: null → Aufrufer fällt auf Vision-LLM zurück.
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

    private val QTY_LINE = Regex(
        """(?i)^\s*(?:[-•*·]\s*)?(?:\d+[.,]?\d*|\d+\s*/\s*\d+|[¼½¾⅓⅔⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞])\s*""" +
            """(?:g|kg|ml|l|el|tl|tsp|tbsp|cup|cups|oz|lb|stück|stk|prise|bund|dose|packung|scheibe|zehe|clove|cloves)?\b"""
    )

    private val RECIPE_KEYWORDS = Regex(
        """(?i)\b(?:zutaten|ingredients|zubereitung|anleitung|method|instructions|""" +
            """portionen|servings?|serves|für\s+\d+|makes\s+\d+|prep(?:\s*time)?|cook(?:\s*time)?)\b"""
    )

    /**
     * Versucht, ein Rezept rein über OCR + Caption-Parser zu extrahieren.
     * @return ein-elementige Liste bei Erfolg, sonst null (Vision-Fallback).
     */
    suspend fun tryExtract(bitmap: Bitmap, apiKey: String = BuildConfig.GROQ_API_KEY): List<RecipeFromImageResult>? =
        withContext(Dispatchers.Default) {
            val ocrText = OnDeviceTextRecognizer.recognizeOrNull(bitmap) ?: return@withContext null
            if (!looksLikeRecipeText(ocrText)) return@withContext null
            if (apiKey.isBlank()) return@withContext null

            val parsed = runCatching {
                RecipeAiParser.parse(
                    caption = ocrText,
                    sourceUrl = null,
                    platform = "bild",
                    imageUrl = null,
                    apiKey = apiKey,
                    fastModel = false
                )
            }.getOrNull() ?: return@withContext null

            // Schwache AI-Ergebnisse (kaum Zutaten) → Vision behalten
            if (!isUsableRecipe(parsed)) return@withContext null

            listOf(parsed.toImageResult())
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
        // Mindestens 2 Zutatenzeilen, davon ≥1 mit Menge
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
