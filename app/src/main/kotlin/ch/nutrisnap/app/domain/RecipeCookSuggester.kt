package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.DietTag
import ch.nutrisnap.app.data.model.Recipe
import ch.nutrisnap.app.data.model.RecipeCategory
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Smart-Vorschläge „Was soll ich kochen?“ bei grossen Rezeptbibliotheken.
 *
 * Scoring-Faktoren:
 *  - Noch nie / lange nicht gekocht
 *  - Favorit & Bewertung
 *  - Makro-Fit (optional, offene Tagesmakros)
 *  - Tageszeit → passende Kategorie
 *  - Schnelle Zubereitung / High-Protein-Tag
 *  - Leichte Zufallskomponente (Reshuffle)
 */
enum class CookSuggestMode {
    /** Gesamtscore aus allen Faktoren. */
    SMART,
    /** timesCooked == 0. */
    NEVER_COOKED,
    /** prep ≤ 30 min oder DietTag.QUICK. */
    QUICK,
    /** High-Protein-Tag oder Protein/kcal-Verhältnis. */
    HIGH_PROTEIN,
    /** isFavorite. */
    FAVORITES,
    /** Nie gekocht oder lastCookedAt älter als 21 Tage. */
    LONG_AGO
}

data class CookSuggestion(
    val recipe: Recipe,
    val score: Float,
    /** Kurze Gründe für die UI, z.B. „Noch nie gekocht“, „Passt zu Restprotein“. */
    val reasons: List<String>
)

object RecipeCookSuggester {

    private const val LONG_AGO_DAYS = 21L
    private const val RECENT_DAYS = 3L

    /**
     * Liefert bis [maxResults] gerankte Vorschläge.
     *
     * @param remaining offene Makros (heute); null = Makro-Fit wird übersprungen
     * @param preferredCategory erzwingt Kategorie (z.B. aus UI-Chip)
     * @param hourOfDay 0–23 für Tageszeit-Heuristik; null = Systemzeit
     * @param seed steuert die leichte Zufallskomponente (Reshuffle)
     */
    fun suggest(
        recipes: List<Recipe>,
        remaining: MacroRemaining? = null,
        mode: CookSuggestMode = CookSuggestMode.SMART,
        preferredCategory: RecipeCategory? = null,
        maxResults: Int = 5,
        hourOfDay: Int? = null,
        seed: Long = System.currentTimeMillis()
    ): List<CookSuggestion> {
        if (recipes.isEmpty()) return emptyList()
        val hour = hourOfDay ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val now = System.currentTimeMillis()
        val rng = Random(seed)

        val filtered = recipes.asSequence()
            .filter { it.title.isNotBlank() }
            .filter { preferredCategory == null || it.category() == preferredCategory }
            .filter { matchesMode(it, mode, now) }
            .toList()

        if (filtered.isEmpty()) return emptyList()

        val scored = filtered.map { recipe ->
            val (score, reasons) = scoreRecipe(
                recipe = recipe,
                remaining = remaining,
                mode = mode,
                hour = hour,
                now = now,
                rng = rng
            )
            CookSuggestion(recipe = recipe, score = score, reasons = reasons)
        }

        // Bei SMART: Top-K nach Score, leichte Durchmischung der Top-Kandidaten
        // damit „Noch eins“ nicht immer identisch ist.
        return if (mode == CookSuggestMode.SMART) {
            scored.sortedByDescending { it.score }
                .take(max(maxResults * 3, 12))
                .shuffled(rng)
                .sortedByDescending { it.score }
                .take(maxResults)
        } else {
            scored.sortedByDescending { it.score }.take(maxResults)
        }
    }

    /**
     * Score für Sortierung „Empfohlen“ (ohne Limit / ohne Zufall).
     * Höher = besser.
     */
    fun recommendScore(
        recipe: Recipe,
        remaining: MacroRemaining? = null,
        hourOfDay: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Float {
        val hour = hourOfDay ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return scoreRecipe(
            recipe = recipe,
            remaining = remaining,
            mode = CookSuggestMode.SMART,
            hour = hour,
            now = nowMs,
            rng = Random(0) // deterministisch
        ).first
    }

    private fun matchesMode(recipe: Recipe, mode: CookSuggestMode, now: Long): Boolean =
        when (mode) {
            CookSuggestMode.SMART -> true
            CookSuggestMode.NEVER_COOKED -> recipe.timesCooked <= 0
            CookSuggestMode.QUICK -> isQuick(recipe)
            CookSuggestMode.HIGH_PROTEIN -> isHighProtein(recipe)
            CookSuggestMode.FAVORITES -> recipe.isFavorite
            CookSuggestMode.LONG_AGO -> {
                recipe.timesCooked <= 0 || daysSince(recipe.lastCookedAt, now) >= LONG_AGO_DAYS
            }
        }

    private fun scoreRecipe(
        recipe: Recipe,
        remaining: MacroRemaining?,
        mode: CookSuggestMode,
        hour: Int,
        now: Long,
        rng: Random
    ): Pair<Float, List<String>> {
        val reasons = mutableListOf<String>()
        var score = 0f

        // ── Noch nie / lange nicht ──────────────────────────────────────────
        if (recipe.timesCooked <= 0) {
            score += 28f
            reasons += "Noch nie gekocht"
        } else {
            val days = daysSince(recipe.lastCookedAt, now)
            when {
                days >= LONG_AGO_DAYS -> {
                    score += 16f
                    reasons += "Lange nicht gekocht"
                }
                days >= 7 -> score += 8f
                days <= RECENT_DAYS -> score -= 10f // kürzlich schon → abwerten
            }
            // Häufig gekocht leicht belohnen (bewährt), aber nicht dominieren
            if (recipe.timesCooked in 2..8) score += 4f
        }

        // ── Favorit & Rating ────────────────────────────────────────────────
        if (recipe.isFavorite) {
            score += 18f
            reasons += "Favorit"
        }
        when (recipe.cookRating) {
            5 -> {
                score += 12f
                reasons += "Top-Bewertung"
            }
            4 -> score += 8f
            3 -> score += 2f
            1, 2 -> score -= 8f
        }

        // ── Schnell ─────────────────────────────────────────────────────────
        if (isQuick(recipe)) {
            score += 10f
            if (mode == CookSuggestMode.QUICK || mode == CookSuggestMode.SMART) {
                reasons += "Schnell"
            }
        }

        // ── High Protein ────────────────────────────────────────────────────
        if (isHighProtein(recipe)) {
            score += 9f
            if (mode == CookSuggestMode.HIGH_PROTEIN ||
                (remaining != null && remaining.protein >= 15f)
            ) {
                reasons += "Proteinreich"
            }
        }

        // ── Tageszeit → Kategorie ───────────────────────────────────────────
        val preferredCats = preferredCategoriesForHour(hour)
        val cat = recipe.category()
        if (cat in preferredCats) {
            score += 11f
            if (preferredCats.firstOrNull() == cat) {
                reasons += cat.label
            }
        }

        // ── Makro-Fit ───────────────────────────────────────────────────────
        if (remaining != null && remaining.hasMeaningfulGap) {
            val macro = macroFitScore(recipe, remaining)
            score += macro.first
            macro.second?.let { reasons += it }
        }

        // ── Nährwerte vorhanden (brauchbarer als „leere“ Karten) ────────────
        if (recipe.totalCalories != null && recipe.totalCalories!! > 0f) {
            score += 3f
        }

        // ── Leichte Zufallskomponente (nur SMART, Reshuffle) ────────────────
        if (mode == CookSuggestMode.SMART) {
            score += rng.nextFloat() * 6f
        }

        // Mode-spezifische Boosts
        when (mode) {
            CookSuggestMode.NEVER_COOKED -> if (recipe.timesCooked <= 0) score += 5f
            CookSuggestMode.FAVORITES -> if (recipe.isFavorite) score += 5f
            CookSuggestMode.LONG_AGO -> {
                if (recipe.timesCooked <= 0) score += 5f
                else if (daysSince(recipe.lastCookedAt, now) >= LONG_AGO_DAYS) score += 5f
            }
            else -> Unit
        }

        // Gründe begrenzen (UI)
        val uniqueReasons = reasons.distinct().take(3)
        return score to uniqueReasons
    }

    /**
     * @return Pair(score-anteil, optionaler Grund-Text)
     */
    private fun macroFitScore(recipe: Recipe, remaining: MacroRemaining): Pair<Float, String?> {
        val serv = recipe.servings.coerceAtLeast(1)
        val kcal = (recipe.totalCalories ?: return 0f to null) / serv
        if (kcal < 40f || kcal > 950f) return 0f to null

        val protein = recipe.proteinPerServing ?: 0f
        val kcalGap = max(remaining.kcal, 1f)
        val proteinGap = max(remaining.protein, 1f)
        val kcalRatio = kcal / kcalGap
        val proteinFill = min(protein / proteinGap, 1.25f)

        var s = 0f
        var reason: String? = null

        // Protein füllt Lücke
        s += proteinFill * 22f
        if (proteinFill >= 0.45f && remaining.protein >= 12f) {
            reason = "Passt zu Restprotein"
        }

        // kcal-Nähe
        s += when {
            kcalRatio in 0.5f..1.1f -> 18f
            kcalRatio in 0.3f..1.3f -> 10f
            kcalRatio > 1.3f -> max(0f, 8f - (kcalRatio - 1.3f) * 15f)
            else -> 4f
        }
        if (reason == null && kcalRatio in 0.55f..1.05f && remaining.kcal >= 150f) {
            reason = "Passt zu Restkalorien"
        }

        // Starke Überschreitung hart abwerten
        if (kcal > remaining.kcal * 1.4f && remaining.kcal >= 100f) {
            s -= 15f
        }

        return s to reason
    }

    private fun isQuick(recipe: Recipe): Boolean {
        val prep = recipe.prepTimeMinutes
        if (prep != null && prep in 1..30) return true
        return DietTag.QUICK in recipe.getDietTags()
    }

    private fun isHighProtein(recipe: Recipe): Boolean {
        if (DietTag.HIGH_PROTEIN in recipe.getDietTags()) return true
        val p = recipe.proteinPerServing ?: return false
        val kcal = recipe.totalCalories?.let { it / recipe.servings.coerceAtLeast(1) } ?: return false
        if (kcal <= 0f) return false
        // ≥ 25 g Protein/Portion oder ≥ 30 % der kcal aus Protein
        if (p >= 25f) return true
        val proteinKcalShare = (p * 4f) / kcal
        return proteinKcalShare >= 0.30f && p >= 15f
    }

    private fun daysSince(lastCookedAt: Long?, now: Long): Long {
        if (lastCookedAt == null || lastCookedAt <= 0L) return 999L
        return TimeUnit.MILLISECONDS.toDays(max(0L, now - lastCookedAt))
    }

    /** Grobe Tageszeit → bevorzugte Kategorien (Reihenfolge = Priorität). */
    fun preferredCategoriesForHour(hour: Int): List<RecipeCategory> = when (hour) {
        in 5..10 -> listOf(RecipeCategory.BREAKFAST, RecipeCategory.SIDE_SNACK, RecipeCategory.DRINK)
        in 11..14 -> listOf(RecipeCategory.MAIN, RecipeCategory.SIDE_SNACK, RecipeCategory.SAUCE)
        in 15..17 -> listOf(RecipeCategory.SIDE_SNACK, RecipeCategory.DESSERT, RecipeCategory.DRINK)
        in 18..21 -> listOf(RecipeCategory.MAIN, RecipeCategory.SIDE_SNACK, RecipeCategory.DESSERT)
        else -> listOf(RecipeCategory.SIDE_SNACK, RecipeCategory.MAIN, RecipeCategory.DESSERT)
    }
}
