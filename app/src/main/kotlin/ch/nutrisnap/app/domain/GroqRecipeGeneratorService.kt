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

/** Timing-Wunsch für den Tagesplan bzgl. Training. */
enum class WorkoutTiming { NONE, PRE, POST, BOTH }

/** Kochgerät, für das ein Rezept erstellt bzw. angepasst werden soll. */
enum class CookingMethod(val label: String) {
    STOVETOP("Pfanne/Herd"),
    OVEN("Backofen"),
    STEAM_OVEN("Dampfgarer/Kombi-Dampfgarer"),
    SMART("Smart-Mix")
}

@Serializable
data class PlannedMeal(
    val mealType: String = "LUNCH",
    val title: String = "",
    val description: String = "",
    /** "Pre-Workout" / "Post-Workout" / leer */
    val timing: String = "",
    val calories: Float = 0f,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val fiber: Float = 0f
)

@Serializable
data class DayPlan(
    val meals: List<PlannedMeal> = emptyList(),
    val totalCalories: Float = 0f,
    val totalProtein: Float = 0f,
    val totalCarbs: Float = 0f,
    val totalFat: Float = 0f,
    val totalFiber: Float = 0f,
    val note: String = ""
)

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

    suspend fun generateRecipe(
        userInput: String,
        cookingMethod: CookingMethod = CookingMethod.STOVETOP,
        applianceModel: String = ""
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildPrompt(userInput) + applianceHint(cookingMethod, applianceModel))
    }

    /** Generiert ein Rezept aus einer Liste vorhandener Zutaten (z.B. "was ist im Kühlschrank"). */
    suspend fun generateFromIngredients(
        ingredients: List<String>,
        note: String = "",
        cookingMethod: CookingMethod = CookingMethod.STOVETOP,
        applianceModel: String = ""
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildIngredientsPrompt(ingredients, note) + applianceHint(cookingMethod, applianceModel))
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
        mealLabel: String,
        cookingMethod: CookingMethod = CookingMethod.STOVETOP,
        applianceModel: String = ""
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildFillUpPrompt(remainingCalories, remainingProtein, remainingCarbs, remainingFat, mealLabel) + applianceHint(cookingMethod, applianceModel))
    }

    /** Überrascht mit einem komplett zufälligen, alltagstauglichen Rezept. */
    suspend fun generateRandom(
        cookingMethod: CookingMethod = CookingMethod.STOVETOP,
        applianceModel: String = ""
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildRandomPrompt() + applianceHint(cookingMethod, applianceModel))
    }

    /** Generiert einen kompletten Tages-Essensplan basierend auf Kalorien-/Makroziel und Präferenzen. */
    suspend fun generateDayPlan(
        targetCalories: Float,
        targetProtein: Float,
        targetFiber: Float,
        includeBreakfast: Boolean,
        mealCount: Int,
        highVolume: Boolean,
        workoutTiming: WorkoutTiming,
        mustUseIngredients: List<String>,
        extraNotes: String,
        cookingMethod: CookingMethod = CookingMethod.STOVETOP,
        applianceModel: String = ""
    ): Result<DayPlan> = withContext(Dispatchers.IO) {
        tryDayPlanProvider(
            prompt = buildDayPlanPrompt(
                targetCalories, targetProtein, targetFiber, includeBreakfast,
                mealCount, highVolume, workoutTiming, mustUseIngredients, extraNotes
            ) + applianceHint(cookingMethod, applianceModel)
        )
    }

    /**
     * Schreibt NUR die Zubereitung eines bestehenden Rezepts auf ein anderes Kochgerät um
     * (z.B. Reis in der Pfanne → Reis im Dampfgarer). Zutaten und Nährwerte bleiben fix,
     * nur steps/description/prepTimeMinutes werden ans Zielgerät angepasst.
     */
    suspend fun adaptRecipeMethod(
        recipe: GeneratedRecipe,
        targetMethod: CookingMethod,
        applianceModel: String = ""
    ): Result<GeneratedRecipe> = withContext(Dispatchers.IO) {
        tryProvider(prompt = buildAdaptPrompt(recipe, targetMethod, applianceModel))
            .map { adapted -> adapted.copy(
                structuredIngredients = recipe.effectiveIngredients(),
                ingredients = recipe.ingredients,
                calories = recipe.calories, protein = recipe.protein,
                carbs = recipe.carbs, fat = recipe.fat, servings = recipe.servings
            ) }
    }

    /** Baut den geräte-spezifischen Zusatzhinweis für den Prompt. Leer für STOVETOP ohne Gerätemodell. */
    private fun applianceHint(method: CookingMethod, applianceModel: String): String {
        val modelPart = if (applianceModel.isNotBlank()) " (konkretes Modell: $applianceModel)" else ""
        return when (method) {
            CookingMethod.STOVETOP -> "\n\nZubereitung: klassisch auf dem Herd/in der Pfanne."
            CookingMethod.OVEN -> "\n\nZubereitung: im Backofen$modelPart. Gib in den Schritten konkrete Ofen-Temperatur (°C, Ober-/Unterhitze oder Heissluft) und Backzeit an."
            CookingMethod.STEAM_OVEN -> "\n\nZubereitung: im Kombi-Dampfgarer$modelPart. Wähle ein passendes Programm (z.B. Dampfgaren, Heissluft mit Beschwaden, Zartgaren mit Dampf, Profi-Backen) und gib in den Schritten Programmname, Temperatur (°C) und Garzeit an."
            CookingMethod.SMART -> """

Zubereitung: Wähle für JEDE Komponente des Gerichts (z.B. Protein, Beilage, Gemüse) einzeln
das am besten geeignete Gerät aus - Pfanne/Herd, Backofen oder Kombi-Dampfgarer$modelPart.
Nutze NICHT zwangsläufig nur ein Gerät für alles. Wenn es Zeit spart oder das Ergebnis
verbessert, verteile die Komponenten auf mehrere Geräte, die PARALLEL laufen (z.B. Fleisch
scharf in der Pfanne anbraten, während die Beilage gleichzeitig im Ofen/Dampfgarer gart) -
zeitlich unabhängige Schritte sollen nicht unnötig nacheinander im selben Gerät passieren.
Beginne JEDEN Zubereitungsschritt mit dem gewählten Gerät in Klammern plus einer sehr kurzen
Begründung (3-6 Wörter), damit der Nutzer die Wahl auf einen Blick nachvollziehen und bei
Bedarf abweichen kann, z.B.:
"(Pfanne – bräunt schön knusprig) Hähnchenbrust bei starker Hitze anbraten..."
"(Dampfgarer – bleibt saftig & schonend) Süsskartoffeln 20 Min. bei 100°C dampfgaren..."
Wenn zwei Schritte auf unterschiedlichen Geräten zeitlich parallel laufen sollen, mach das
in den Schritten explizit klar (z.B. "gleichzeitig", "während Schritt 1 läuft").
"""
        }
    }

    private fun buildPrompt(userInput: String): String {
        val isCaption = !isUrl(userInput) && userInput.length > 80

        val taskDescription = if (isCaption) {
            """
Du bist ein erfahrener Ernährungsberater und Koch.
Der Benutzer hat folgenden Text eingefügt (z.B. einen kopierten Instagram/TikTok-Caption oder ein Rezept aus dem Internet):

""${'"'}
$userInput
""${'"'}

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
    {"name": "Zutat", "amount": "100g", "calories": 100, "protein": 10.0, "carbs": 5.0, "fat": 2.0}
  ],
  "ingredients": ["100g Zutat"],
  "steps": ["Schritt 1"],
  "servings": 2,
  "prepTimeMinutes": 30,
  "calories": 300,
  "protein": 25.0,
  "carbs": 35.0,
  "fat": 10.0
}
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

    private fun buildDayPlanPrompt(
        targetCalories: Float,
        targetProtein: Float,
        targetFiber: Float,
        includeBreakfast: Boolean,
        mealCount: Int,
        highVolume: Boolean,
        workoutTiming: WorkoutTiming,
        mustUseIngredients: List<String>,
        extraNotes: String
    ): String {
        val breakfastHint = if (includeBreakfast)
            "Das Frühstück soll enthalten sein."
        else
            "Kein Frühstück einplanen (z.B. Intervallfasten) — nur Mittagessen/Abendessen/Snacks."

        val volumeHint = if (highVolume)
            "Bevorzuge High-Volume-Gerichte (viel Volumen/Sättigung bei den vorgegebenen Kalorien, z.B. viel Gemüse, magere Proteine, wenig Fett/Öl)."
        else ""

        val workoutHint = when (workoutTiming) {
            WorkoutTiming.NONE -> ""
            WorkoutTiming.PRE -> "Die Person trainiert HEUTE. Eine Mahlzeit soll als Pre-Workout-Mahlzeit markiert werden (leicht verdaulich, moderate Carbs, timing=\"Pre-Workout\")."
            WorkoutTiming.POST -> "Die Person trainiert HEUTE. Eine Mahlzeit soll als Post-Workout-Mahlzeit markiert werden (proteinreich, timing=\"Post-Workout\")."
            WorkoutTiming.BOTH -> "Die Person trainiert HEUTE. Plane sowohl eine Pre-Workout- als auch eine Post-Workout-Mahlzeit ein (timing=\"Pre-Workout\" bzw. \"Post-Workout\")."
        }

        val ingredientsHint = if (mustUseIngredients.isNotEmpty())
            "Diese Zutaten sollen über den Tag verteilt vorkommen: ${mustUseIngredients.joinToString(", ")}."
        else ""

        val notesHint = if (extraNotes.isNotBlank()) "Zusätzliche Wünsche: $extraNotes" else ""

        return """
Du bist ein erfahrener Ernährungsberater. Erstelle einen kompletten Tages-Essensplan mit genau $mealCount Mahlzeiten.

Tagesziele:
- Kalorien: ca. ${targetCalories.roundToIntSafe()} kcal
- Protein: ca. ${targetProtein.roundToIntSafe()} g
- Ballaststoffe: ca. ${targetFiber.roundToIntSafe()} g

$breakfastHint
$volumeHint
$workoutHint
$ingredientsHint
$notesHint

Jedes Element im "meals"-Array braucht ein mealType-Feld mit genau einem der Werte:
"BREAKFAST", "LUNCH", "DINNER", "SNACK".
Die Summe aller Mahlzeiten-Kalorien soll möglichst nah am Tagesziel liegen (Toleranz ca. ±10%).

Berechne die Nährwerte EXAKT basierend auf echten Zutatenmengen.
Referenzwerte pro 100g: Hühnerbrust=165kcal/31gP, Parmesan=431kcal/38gP,
Ricotta=174kcal/11gP, Hackfleisch=250kcal/17gP, Pasta=350kcal/13gP,
Reis=130kcal/3gP, Ei=155kcal/13gP, Butter=717kcal/1gP.
Ballaststoffreiche Referenzwerte pro 100g: Vollkornprodukte=8-10g, Hülsenfrüchte=6-8g, Gemüse=2-4g, Obst=2-3g.

Antworte NUR mit folgendem JSON (kein Markdown, keine Erklärungen):
{
  "meals": [
    {
      "mealType": "BREAKFAST",
      "title": "Name der Mahlzeit",
      "description": "Kurze Beschreibung mit Hauptzutaten/Mengen",
      "timing": "",
      "calories": 450,
      "protein": 30.0,
      "carbs": 40.0,
      "fat": 15.0,
      "fiber": 8.0
    }
  ],
  "totalCalories": 2000,
  "totalProtein": 150.0,
  "totalCarbs": 200.0,
  "totalFat": 60.0,
  "totalFiber": 32.0,
  "note": "Kurzer Hinweis oder Tipp zum Plan (1 Satz)"
}

Das "timing"-Feld nur bei Pre-/Post-Workout-Mahlzeiten befüllen ("Pre-Workout" / "Post-Workout"), sonst leeren String lassen.
""".trimIndent()
    }

    private fun buildAdaptPrompt(recipe: GeneratedRecipe, targetMethod: CookingMethod, applianceModel: String): String {
        val ingredientsList = recipe.effectiveIngredients().joinToString("\n") { "- ${it.amount} ${it.name}" }
        val stepsList = recipe.steps.joinToString("\n") { "- $it" }
        val modelPart = if (applianceModel.isNotBlank()) " (konkretes Modell: $applianceModel)" else ""
        val methodInstruction = when (targetMethod) {
            CookingMethod.STOVETOP -> "auf dem Herd/in der Pfanne"
            CookingMethod.OVEN -> "im Backofen$modelPart, mit konkreter Temperatur (°C) und Backzeit"
            CookingMethod.STEAM_OVEN -> "im Kombi-Dampfgarer$modelPart, mit passendem Programm (z.B. Dampfgaren, Heissluft mit Beschwaden, Zartgaren mit Dampf, Profi-Backen), Temperatur (°C) und Garzeit"
            CookingMethod.SMART -> "mit dem für JEDE Komponente einzeln am besten geeigneten Gerät " +
                "(Pfanne/Herd, Backofen oder Kombi-Dampfgarer$modelPart) - nutze wo sinnvoll mehrere Geräte " +
                "PARALLEL statt alles nacheinander in einem Gerät. Beginne jeden Schritt mit dem gewählten " +
                "Gerät in Klammern plus 3-6 Wörter Begründung, z.B. \"(Pfanne – bräunt schön knusprig) ...\""
        }
        return """
Du bist ein erfahrener Koch. Hier ist ein bestehendes Rezept:

Titel: ${recipe.title}
Zutaten:
$ingredientsList

Bisherige Zubereitung:
$stepsList

Schreibe NUR die Zubereitungsschritte so um, dass das Gericht $methodInstruction zubereitet wird.
Zutaten und Mengen bleiben unverändert. Ändere NICHTS an den Nährwerten. Titel und Beschreibung
darfst du leicht anpassen (z.B. "... aus dem Dampfgarer"), falls sinnvoll.

$JSON_SCHEMA_HINT
""".trimIndent()
    }

    private fun Float.roundToIntSafe(): Int = this.coerceAtLeast(0f).let { Math.round(it) }

    /** Führt den eigentlichen Groq-Call aus und liefert den bereinigten JSON-String zurück. */
    private fun callGroq(prompt: String, maxTokens: Int = 3000): Result<String> {
        return try {
            val apiKey = BuildConfig.GROQ_API_KEY
            if (apiKey.isBlank()) return Result.failure(Exception(
                "Kein GROQ_API_KEY in local.properties konfiguriert"
            ))

            val requestJson = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.7)
                put("max_tokens", maxTokens)
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

            Result.success(cleaned)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun tryProvider(prompt: String): Result<GeneratedRecipe> =
        callGroq(prompt, maxTokens = 2000).mapCatching { json.decodeFromString<GeneratedRecipe>(it) }

    private fun tryDayPlanProvider(prompt: String): Result<DayPlan> =
        callGroq(prompt, maxTokens = 3000).mapCatching { json.decodeFromString<DayPlan>(it) }
}
