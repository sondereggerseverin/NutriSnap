package ch.nutrisnap.app.ui.screens.recipes

import ch.nutrisnap.app.data.model.FoodItem
import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.MatchSource
import ch.nutrisnap.app.data.model.RecipeComponent
import ch.nutrisnap.app.domain.RecipeNutritionAnalyzer

internal fun fmtMacro(v: Float): String =
    if (v.isFinite()) "%.1f".format(v) else "0.0"

internal fun safeInt(v: Float): Int =
    if (v.isFinite()) v.toInt().coerceAtLeast(0) else 0

// ── State for a single ingredient during verification ─────────────────────────

/**
 * Persistente, leichte Repräsentation einer manuellen Zutaten-Anpassung.
 * Wird (anders als IngredientVerifyState) außerhalb des Sheets im ViewModel
 * gehalten, damit sie weder beim Schließen des Sheets noch bei "Neu berechnen"
 * (frische AnalysisResult-Instanz) verloren geht. Schlüssel ist die Zutaten-
 * Zeile (result.line) — identisch zum Key, den die LazyColumn ohnehin nutzt.
 */
data class IngredientOverride(
    val override: FoodItem? = null,
    val manualFiber: Float? = null,
    val amountOverride: Float? = null,
    /** True = Zutat wurde vom User entfernt; beim Merge übersprungen. */
    val deleted: Boolean = false,
    /** "side" | "sauce" | null = Heuristik beim Öffnen. */
    val componentGroup: String? = null
)

data class IngredientVerifyState(
    val result: RecipeNutritionAnalyzer.IngredientResult,
    // Override set by user scanning/searching/manual
    val override: FoodItem? = null,
    /** Manuell nachgetragene Ballaststoffe für die tatsächlich verwendete Menge (nicht pro 100g).
     *  Hat Vorrang vor jedem aus override/result stammenden Fiber-Wert. */
    val manualFiber: Float? = null,
    /** Manuell korrigierte Menge in Gramm (überschreibt die aus dem Rezepttext geparste Menge). */
    val amountOverride: Float? = null
) {
    val isVerified: Boolean get() = override != null || result.matched
    val effectiveFood: FoodItem? get() = override ?: result.foodItem
    val originalAmountG: Float get() = result.parsed?.amountG ?: 100f
    val effectiveAmountG: Float get() = amountOverride ?: originalAmountG
    /** Verhältnis effektive/ursprüngliche Menge — Fallback-Skalierung, wenn kein FoodItem vorliegt. */
    private val amountRatio: Float get() = effectiveAmountG / originalAmountG.coerceAtLeast(0.1f)

    val effectiveCalories: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.calories ?: 0f) }
            ?: (result.calories * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    val effectiveProtein: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.protein ?: 0f) }
            ?: (result.protein * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    val effectiveCarbs: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.carbs ?: 0f) }
            ?: (result.carbs * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    val effectiveFat: Float get() = (
        effectiveFood?.let { effectiveAmountG / 100f * (it.fat ?: 0f) }
            ?: (result.fat * amountRatio)
    ).let { if (it.isFinite()) it else 0f }
    /** Mikronaehrstoffe (Ballaststoffe etc.) für die tatsächlich verwendete Menge —
     *  bei bekanntem FoodItem (override oder Match) anhand der editierbaren Menge skaliert,
     *  sonst anhand des Mengenverhältnisses aus der ursprünglichen Analyse.
     *  manualFiber überschreibt einen ggf. vorhandenen Fiber-Wert immer. */
    val effectiveMicros: Map<String, Float> get() {
        val base = effectiveFood?.let { food ->
            val factor = effectiveAmountG / 100f
            buildMap {
                food.fiber?.let { put("fiber", it * factor) }
                food.sugar?.let { put("sugar", it * factor) }
                food.saturatedFat?.let { put("saturatedFat", it * factor) }
                food.salt?.let { put("salt", it * factor) }
                food.sodium?.let { put("sodium", it * factor) }
            }
        } ?: result.micros.mapValues { it.value * amountRatio }
        return manualFiber?.let { base + ("fiber" to it) } ?: base
    }

    fun toOverride(componentGroup: String? = null): IngredientOverride =
        IngredientOverride(
            override = override,
            manualFiber = manualFiber,
            amountOverride = amountOverride,
            componentGroup = componentGroup
        )
}

/** Heuristik: Beilage vs. Sauce anhand des Zutatennamens. */
fun defaultComponentGroup(text: String): String {
    val n = text.lowercase()
    val sideKeys = listOf(
        "reis", "basmati", "erbse", "erbsen", "peas", "kartoffel", "süsskartoffel",
        "susskartoffel", "sweet potato", "nudel", "pasta", "quinoa", "couscous",
        "bulgur", "beilage", "reisnudeln", "mais", "corn", "bohne", "beans",
        "linse", "kicher", "stampf", "püree", "puree", "salat", "gemüse", "gemuese"
    )
    val sauceKeys = listOf(
        "poulet", "huhn", "hähnchen", "haehnchen", "chicken", "fleisch", "filet",
        "tomate", "rahm", "sahne", "cream", "joghurt", "yogurt", "gewürz", "garam",
        "sauce", "butter", "masala", "chili", "ingwer", "knoblauch", "zwiebel",
        "öl", "oil", "speiseöl", "fromage", "rôti", "roti", "honig", "honey",
        "limette", "lime", "marinade"
    )
    val isSide = sideKeys.any { it in n }
    val isSauce = sauceKeys.any { it in n }
    return when {
        isSide && !isSauce -> "side"
        isSauce -> "sauce"
        isSide -> "side"
        else -> "sauce"
    }
}

/**
 * Ordnet eine Analyse-Zeile einem Abschnitts-Key zu.
 * Reihenfolge: Override → Abschnitts-Text → bestehende Gruppe → Heuristik.
 * Verhindert, dass nach dem Löschen einer Zutat alle Zeilen auf side/sauce
 * zurückfallen und die originalen Abschnittsnamen (z.B. „Charred Zuckermais“) verloren gehen.
 */
fun resolveComponentGroup(
    line: String,
    parsedName: String?,
    foodName: String?,
    sectionByLine: Map<String, String>,
    overrideGroup: String?,
    existingGroup: String?,
    allowComponentSplit: Boolean
): String? {
    if (!allowComponentSplit) return null
    overrideGroup?.takeIf { it.isNotBlank() }?.let { return it }
    existingGroup?.takeIf { it.isNotBlank() && it != "side" && it != "sauce" }?.let { return it }
    val lineLc = line.lowercase().trim()
    val nameLc = parsedName?.lowercase()?.trim().orEmpty()
    val fromSection = sectionByLine.entries.firstOrNull { (k, _) ->
        val key = k.lowercase().trim()
        if (key.length < 3) return@firstOrNull false
        lineLc.contains(key) || key.contains(lineLc.take(24)) ||
            (nameLc.length >= 3 && (key.contains(nameLc) || nameLc.contains(key.take(24))))
    }?.value
    if (fromSection != null) return fromSection
    existingGroup?.takeIf { it.isNotBlank() }?.let { return it }
    val key = "$line ${parsedName.orEmpty()} ${foodName.orEmpty()}"
    return defaultComponentGroup(key)
}

/**
 * Ob für diese Zutat Ballaststoffe erwartet werden.
 * Bei Milch, Fleisch, Öl, reinem Whey o.ä. ist fehlender Fiber-Wert normal — keine Warnung.
 */
fun expectsDietaryFiber(text: String): Boolean {
    val n = text.lowercase()
        .replace(Regex("""added_\d+_"""), "") // Scan-Prefix entfernen
    // Explizit ohne Ballaststoffe (oder vernachlässigbar)
    val noFiber = listOf(
        "milch", "milk", "sahne", "cream", "quark", "joghurt", "yogurt", "skyr",
        "käse", "kaese", "cheese", "butter", "ei ", "eier", "egg", "eiweiss",
        "whey", "isolat", "isolate", "casein", "protein pulver", "proteinpulver",
        "öl", "oil", "wasser", "water", "salz", "salt", "pfeffer", "pepper",
        "hähnchen", "haehnchen", "poulet", "chicken", "rind", "schwein", "fleisch",
        "lachs", "fisch", "fish", "thunfisch", "garnelen", "schinken", "speck",
        "wein", "weinbrand", "brühe", "bruehe", "fond", "bouillon"
    )
    if (noFiber.any { it in n }) return false
    // Typische Ballaststoff-Träger
    val hasFiber = listOf(
        "hafer", "oat", "flocken", "chia", "leinsamen", "psyllium", "vollkorn",
        "mehl", "flour", "brot", "bread", "nudel", "pasta", "reis", "rice",
        "quinoa", "bulgur", "couscous", "gemüse", "gemuese", "vegetable",
        "spinat", "brokkoli", "bohne", "linse", "erbse", "kicher", "apfel",
        "banane", "beere", "obst", "fruit", "nuss", "mandel", "walnuss",
        "keks", "cookie", "oreo", "kakao", "cacao", "cocoa", "schokolade",
        "mais", "kartoffel", "potato", "süßkartoffel", "avocado", "tomate"
    )
    return hasFiber.any { it in n }
}

data class VerifiedTotals(
    val kcal: Float, val protein: Float, val carbs: Float, val fat: Float,
    val fiber: Float?, val sugar: Float?, val saturatedFat: Float?, val salt: Float?, val sodium: Float?
)

/** Baut die aktuelle Zutatenliste aus einem frischen AnalysisResult + gespeicherten
 *  manuellen Anpassungen zusammen. Als Löschung markierte Zutaten werden ausgelassen. */
fun mergeIngredientOverrides(
    ingredients: List<RecipeNutritionAnalyzer.IngredientResult>,
    overrides: Map<String, IngredientOverride>
): List<IngredientVerifyState> = ingredients.mapNotNull { result ->
    val ov = overrides[result.line]
        ?: overrides.entries.firstOrNull { (k, _) ->
            k.trim().equals(result.line.trim(), ignoreCase = true)
        }?.value
    when {
        ov?.deleted == true -> null
        ov != null -> IngredientVerifyState(result, ov.override, ov.manualFiber, ov.amountOverride)
        else -> IngredientVerifyState(result)
    }
}

/**
 * Rekonstruiert Session-Overrides aus persistenten [IngredientMatch]-Zeilen.
 * Key = ingredientRaw (Zeilentext), damit mergeIngredientOverrides greift.
 */
fun matchesToOverrides(matches: List<IngredientMatch>): Map<String, IngredientOverride> {
    if (matches.isEmpty()) return emptyMap()
    return matches.associate { m ->
        val amount = m.manualAmountG ?: m.amountGrams.takeIf { it > 0f }
        val per100 = amount?.takeIf { it > 0f } ?: 100f
        val food = if (m.matchedFoodName != null || m.matchedCalories != null) {
            FoodItem(
                id = m.matchedFoodItemId?.toInt() ?: 0,
                name = m.matchedFoodName ?: m.ingredientName,
                calories = m.matchedCalories?.let { it / per100 * 100f },
                protein = m.matchedProtein?.let { it / per100 * 100f },
                carbs = m.matchedCarbs?.let { it / per100 * 100f },
                fat = m.matchedFat?.let { it / per100 * 100f },
                fiber = m.manualFiberG?.let { it / per100 * 100f }
            )
        } else null
        m.ingredientRaw to IngredientOverride(
            override = food,
            manualFiber = m.manualFiberG,
            amountOverride = m.manualAmountG,
            deleted = m.isDeleted,
            componentGroup = m.componentGroup
        )
    }
}

/** True, wenn Matches manuelle Anpassungen oder Komponenten-Zuordnung tragen. */
fun matchesHaveOverrides(matches: List<IngredientMatch>): Boolean =
    matches.any {
        it.manualAmountG != null || it.manualFiberG != null || it.isDeleted ||
            !it.componentGroup.isNullOrBlank() || it.matchedFoodItemId != null
    }

/** Reine Summierungslogik, wiederverwendbar sowohl im Sheet (Live-Anzeige) als
 *  auch im ViewModel (Button "Auswahl übernehmen", ohne Sheet zu öffnen). */
fun computeVerifiedTotals(states: List<IngredientVerifyState>): VerifiedTotals {
    fun microTotal(key: String): Float? =
        states.mapNotNull { it.effectiveMicros[key] }.takeIf { it.isNotEmpty() }?.sum()
    return VerifiedTotals(
        kcal = states.sumOf { it.effectiveCalories.toDouble() }.toFloat(),
        protein = states.sumOf { it.effectiveProtein.toDouble() }.toFloat(),
        carbs = states.sumOf { it.effectiveCarbs.toDouble() }.toFloat(),
        fat = states.sumOf { it.effectiveFat.toDouble() }.toFloat(),
        fiber = microTotal("fiber"),
        sugar = microTotal("sugar"),
        saturatedFat = microTotal("saturatedFat"),
        salt = microTotal("salt"),
        sodium = microTotal("sodium")
    )
}

/** Anzeigetext: aktuelle Menge + Rezeptname (DE), nicht OFF/Scan-Produktname (oft FR/EN). */
/**
 * Menge + Name getrennt – für tabellarische Verify-Zeilen.
 * Entfernt Abschnittsköpfe ("Für die Sauce:", "For the chicken:") und Roh-Mengenpräfixe.
 */
data class VerifyLineParts(val amountLabel: String, val name: String)

fun formatVerifyLineParts(state: IngredientVerifyState): VerifyLineParts {
    val g = state.effectiveAmountG
    val amountStr = when {
        !g.isFinite() || g <= 0f -> "–"
        g >= 10f -> "${g.toInt()} g"
        else -> "${"%.1f".format(g)} g"
    }
    fun clean(raw: String): String = raw
        .trimStart('•', '-', '*', '·', ' ', '➕')
        .replace(Regex("""^added_\d+_"""), "")
        .replace(Regex("""\s*\(\d{10,}\)"""), "")
        // Abschnittsköpfe, die fälschlich in den Zutatennamen gerutscht sind
        .replace(
            Regex(
                """(?i)^(für\s+(die\s+)?|for\s+(the\s+)?)""" +
                    """(hähnchen|haehnchen|chicken|sauce|soße|sosse|marinade|dressing|""" +
                    """topping|teig|base|füllung|fuellung|beilage|gemüse|gemuese|reis|nudeln)""" +
                    """\s*[:：\-]\s*"""
            ),
            ""
        )
        .replace(Regex("""(?i)^ingredients?\s*(\([^)]*\))?\s*:?\s*"""), "")
        .replace(Regex("""(?i)^zutaten\s*(\([^)]*\))?\s*:?\s*"""), "")
        .replace(Regex("""(?i)^\d+([.,]\d+)?\s*(g|ml|kg|el|tl|cup|tbsp|tsp|oz)\s+"""), "")
        .trim()
        .trimStart(':', '–', '-', ' ')
        .trim()

    val fromParsed = state.result.parsed?.name?.takeIf { it.isNotBlank() }?.let { clean(it) }
    val fromLine = clean(state.result.line).takeIf { it.isNotBlank() }
    val fromFood = state.effectiveFood?.name?.takeIf { it.isNotBlank() }?.let { clean(it) }

    // Original-Rezepttext hat Vorrang vor kommerziellem OFF-Namen ("Cacao en poudre" etc.)
    val name = when {
        !fromParsed.isNullOrBlank() && fromParsed.length >= 2 -> fromParsed
        !fromLine.isNullOrBlank() && fromLine.length >= 2 -> fromLine
        !fromFood.isNullOrBlank() -> fromFood
        else -> clean(state.result.line).ifBlank { state.result.line.trim() }
    }
    return VerifyLineParts(amountStr, name)
}

fun formatVerifyLineTitle(state: IngredientVerifyState): String {
    val p = formatVerifyLineParts(state)
    return "${p.amountLabel} ${p.name}".trim()
}
