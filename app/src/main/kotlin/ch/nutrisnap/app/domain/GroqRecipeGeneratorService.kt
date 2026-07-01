package ch.nutrisnap.app.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ch.nutrisnap.app.BuildConfig
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Serializable
data class RecipeIngredient(
    val name: String = "",
    val amount: String = "",
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
)

@Serializable
data class GeneratedRecipe(
    val title: String = "",
    val description: String = "",
    val ingredients: List<String> = emptyList(),
    val structuredIngredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String> = emptyList(),
    val servings: Int = 2,
    val prepTimeMinutes: Int = 30,
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f
) {
    fun effectiveIngredients(): List<RecipeIngredient> =
        if (structuredIngredients.isNotEmpty()) structuredIngredients
        else ingredients.map { RecipeIngredient(name = it, amount = "") }
}

private fun isUrl(input: String): Boolean {
    val trimmed = input.trim()
    return trimmed.startsWith("http://") || trimmed.startsWith("https://")
}

class GroqRecipeGeneratorService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun generateRecipe(userInput: String): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildPrompt(userInput))
    }

    /** Generiert ein Rezept aus einer Liste vorhandener Zutaten (z.B. "was ist im Kühlschrank"). */
    suspend fun generateFromIngredients(
        ingredients: List<String>,
        note: String = ""
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildIngredientsPrompt(ingredients, note))
    }

    /**
     * Generiert ein Gericht, das möglichst genau in die noch übrigen Tages-Makros passt
     * ("Fill Up"). mealLabel z.B. "Abendessen" oder "Snack".
     */
    suspend fun generateFillUp(
        remainingCalories: Float,
        remainingProtein: Float,
        remainingCarbs: Float,
        remainingFat: Float,
        mealLabel: String
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildFillUpPrompt(remainingCalories, remainingProtein, remainingCarbs, remainingFat, mealLabel))
    }

    /** Überrascht mit einem komplett zufälligen, alltagstauglichen Rezept. */
    suspend fun generateRandom(): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildRandomPrompt())
    }

    private fun buildPrompt(userInput: String): String {
        val isCaption = !isUrl(userInput) && userInput.length > 80

        val taskDescription = if (isCaption) {
            """
Der Benutzer hat folgenden Text eingefügt (z.B. einen kopierten Instagram/TikTok-Caption oder ein Rezept aus dem Internet):

\"\"\"
$userInput
\"\"\"

Extrahiere daraus alle Zutaten und Zubereitungsschritte. Falls Mengenangaben fehlen, schätze realistische Werte.
Falls kein Rezepttitel erkennbar ist, erfinde einen passenden deutschen Namen.
""".trimIndent()
        } else {
            "Erstelle ein realistisches Rezept für: $userInput"
        }

        return """
Du bist ein erfahrener Ernährungsberater und Koch.
$taskDescription

Berechne die Nährwerte EXAKT basierend auf echten Zutatenmengen.
Referenzwerte pro 100g: Hühnerbrust=165kcal/31gP, Parmesan=431kcal/38gP,
Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP,
Reis=130kcal/3gP, Ei=155kcal/13gP, Butter=717kcal/1gP.

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "title": "Rezeptname",
  "description": "Kurze Beschreibung",
  "structuredIngredients": [
    {"name": "Hühnerbrust", "amount": "200g", "calories": 330, "protein": 62.0, "carbs": 0.0, "fat": 7.0}
  ],
  "ingredients": ["200g Hühnerbrust"],
  "steps": ["Schritt 1", "Schritt 2"],
  "servings": 4,
  "prepTimeMinutes": 30,
  "calories": 650,
  "protein": 55.0,
  "carbs": 45.0,
  "fat": 25.0
}

calories/protein/carbs/fat auf Toplevel = Werte PRO PORTION.
Werte in structuredIngredients = GESAMT für die gesamte Zutatenmenge.
""".trimIndent()
    }

    private val JSON_SCHEMA_HINT = """
Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "title": "Rezeptname",
  "description": "Kurze Beschreibung",
  "structuredIngredients": [
    {"name": "Hühnerbrust", "amount": "200g", "calories": 330, "protein": 62.0, "carbs": 0.0, "fat": 7.0}
  ],
  "ingredients": ["200g Hühnerbrust"],
  "steps": ["Schritt 1", "Schritt 2"],
  "servings": 2,
  "prepTimeMinutes": 30,
  "calories": 650,
  "protein": 55.0,
  "carbs": 45.0,
  "fat": 25.0
}

calories/protein/carbs/fat auf Toplevel = Werte PRO PORTION.
Werte in structuredIngredients = GESAMT für die gesamte Zutatenmenge.
""".trimIndent()

    private fun buildIngredientsPrompt(ingredients: List<String>, note: String): String {
        val list = ingredients.filter { it.isNotBlank() }.joinToString("\n") { "- $it" }
        val noteBlock = if (note.isNotBlank()) "\nZusätzlicher Wunsch: $note" else ""
        return """
Du bist ein erfahrener Koch, spezialisiert auf Resteverwertung ("was kann ich mit dem kochen, das ich gerade zuhause habe").

Diese Zutaten sind vorhanden:
$list
$noteBlock

Erstelle ein realistisches, alltagstaugliches Rezept, das MÖGLICHST VIELE dieser Zutaten verwendet.
Du darfst übliche Grundzutaten annehmen, die in fast jedem Haushalt vorhanden sind (Salz, Pfeffer, Öl, Wasser, Gewürze),
auch wenn sie nicht in der Liste stehen. Falls für ein rundes Gericht 1-2 zusätzliche Zutaten fehlen, die typischerweise
im Haushalt vorhanden sind, kannst du sie ergänzen — aber baue das Rezept primär um die vorhandenen Zutaten herum.
Erfinde KEINE exotischen Zutaten, die die Person offensichtlich nicht hat.

Berechne die Nährwerte EXAKT basierend auf echten Zutatenmengen.
Referenzwerte pro 100g: Hühnerbrust=165kcal/31gP, Parmesan=431kcal/38gP,
Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP,
Reis=130kcal/3gP, Ei=155kcal/13gP, Butter=717kcal/1gP.

$JSON_SCHEMA_HINT
""".trimIndent()
    }

    private fun buildFillUpPrompt(
        remainingCalories: Float,
        remainingProtein: Float,
        remainingCarbs: Float,
        remainingFat: Float,
        mealLabel: String
    ): String {
        return """
Du bist ein erfahrener Ernährungsberater und Koch. Die Person hat heute noch folgendes Kalorien-/Makro-Budget übrig
und möchte damit ihr(e) $mealLabel bestreiten:

- Kalorien: ca. ${remainingCalories.roundToIntSafe()} kcal
- Protein: ca. ${remainingProtein.roundToIntSafe()} g
- Kohlenhydrate: ca. ${remainingCarbs.roundToIntSafe()} g
- Fett: ca. ${remainingFat.roundToIntSafe()} g

Erfinde ein leckeres, alltagstaugliches Gericht, dessen Nährwerte PRO PORTION so nah wie möglich an diesem Budget liegen
(Toleranz ca. ±10%). Bevorzuge eine ausgewogene, proteinreiche Mahlzeit. Wenn das Budget sehr klein ist (< 300 kcal),
schlage einen Snack statt einer ganzen Mahlzeit vor. Setze "servings" auf 1, damit die Toplevel-Werte direkt einer Portion entsprechen.

Berechne die Nährwerte EXAKT basierend auf echten Zutatenmengen.
Referenzwerte pro 100g: Hühnerbrust=165kcal/31gP, Parmesan=431kcal/38gP,
Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP,
Reis=130kcal/3gP, Ei=155kcal/13gP, Butter=717kcal/1gP.

$JSON_SCHEMA_HINT
""".trimIndent()
    }

    private fun buildRandomPrompt(): String {
        val cuisines = listOf(
            "italienisch", "asiatisch (wok)", "mexikanisch", "mediterran", "indisch (mild)",
            "skandinavisch", "amerikanisch (BBQ-Style)", "griechisch", "orientalisch",
            "schweizerisch/alpin", "japanisch", "thailändisch", "spanisch"
        )
        val styles = listOf(
            "schnell (unter 20 Min.)", "proteinreich & fitnessfreundlich", "gemütliches Comfort Food",
            "vegetarisch", "One-Pot", "für Meal Prep geeignet", "leicht & sommerlich", "herzhaft & deftig"
        )
        val cuisine = cuisines.random()
        val style = styles.random()
        return """
Überrasche mich mit einem kreativen, aber alltagstauglichen Rezept. Stil: $cuisine, $style.
Es soll mit haushaltsüblichen, in der Schweiz/Europa gut erhältlichen Zutaten machbar sein.

Berechne die Nährwerte EXAKT basierend auf echten Zutatenmengen.
Referenzwerte pro 100g: Hühnerbrust=165kcal/31gP, Parmesan=431kcal/38gP,
Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP,
Reis=130kcal/3gP, Ei=155kcal/13gP, Butter=717kcal/1gP.

$JSON_SCHEMA_HINT
""".trimIndent()
    }

    private fun Float.roundToIntSafe(): Int = this.coerceAtLeast(0f).let { Math.round(it) }

    private fun tryProvider(prompt: String): Result<GeneratedRecipe> {
        return try {
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) return Result.failure(Exception(
                "Kein GROQ_API_KEY in local.properties konfiguriert"
            ))

            val requestJson = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.7)
                put("max_tokens", 2000)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
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
            val bodyStr = response.body?.string() ?: return Result.failure(Exception("Leere Antwort"))
            if (!response.isSuccessful) return Result.failure(Exception("API Fehler ${response.code}: $bodyStr"))

            val root = JSONObject(bodyStr)
            val content = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val cleaned = content.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

            val recipe = json.decodeFromString<GeneratedRecipe>(cleaned)
            Result.success(recipe)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
