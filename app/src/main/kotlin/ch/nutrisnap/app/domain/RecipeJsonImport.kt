package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Recipe
import org.json.JSONObject

/**
 * Erkennt und parst ein Rezept-JSON, das Claude (Chat) exportiert hat, damit es per
 * Android-Share ("Text markieren -> Teilen -> NutriSnap") oder aus einer heruntergeladenen
 * .json-Datei direkt als [Recipe] gespeichert werden kann - ohne die fehleranfaellige
 * Freitext-Erkennung (parseCaption) durchlaufen zu muessen.
 *
 * Erkennungsmerkmal: Top-Level-Key "nutrisnap_recipe" (Versionsnummer). Fehlt er, wird null
 * zurueckgegeben, damit geteilte URLs (Instagram/TikTok/Web) weiterhin ganz normal ueber
 * [RecipeScraper] laufen.
 */
object RecipeJsonImport {

    fun tryParse(raw: String): Recipe? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        if (!json.has("nutrisnap_recipe")) return null

        fun floatOrNull(key: String): Float? =
            if (json.has(key) && !json.isNull(key)) json.optDouble(key).toFloat() else null

        return Recipe(
            title             = json.optString("title").ifBlank { "Rezept von Claude" },
            description       = json.optString("description", ""),
            platform          = "ki",
            ingredients       = json.optString("ingredients", ""),
            instructions      = json.optString("instructions", ""),
            servings          = json.optInt("servings", 1).coerceAtLeast(1),
            totalCalories     = floatOrNull("totalCalories"),
            proteinPerServing = floatOrNull("proteinPerServing"),
            carbsPerServing   = floatOrNull("carbsPerServing"),
            fatPerServing     = floatOrNull("fatPerServing"),
            fiberPerServing   = floatOrNull("fiberPerServing"),
            tags              = "Claude"
        )
    }
}
