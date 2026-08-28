package ch.nutrisnap.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// ─── Diary ───────────────────────────────────────────────────────────────────
@Entity(
    tableName = "diary_entries",
    indices = [
        Index(value = ["dateStr"]),
        Index(value = ["foodItemId"]),
        Index(value = ["dateStr", "mealType"])
    ]
)
data class DiaryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodItemId: Int,           // Int to match FoodItem.id
    val foodName: String,
    val amountGrams: Float,
    val mealType: MealType,
    val dateStr: String,           // "yyyy-MM-dd"
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    @ColumnInfo(defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(defaultValue = "0") val fiber: Float = 0f,
    @ColumnInfo(defaultValue = "0") val sugar: Float = 0f,
    @ColumnInfo(defaultValue = "0") val saturatedFat: Float = 0f,
    @ColumnInfo(defaultValue = "0") val salt: Float = 0f,
    @ColumnInfo(defaultValue = "0") val sodium: Float = 0f,
    /** Nur bei Rezept-Einträgen gesetzt, wenn der Nutzer die Menge in Gramm statt
     *  in Portionen eingegeben hat. amountGrams speichert weiterhin den daraus
     *  abgeleiteten Portionsfaktor (für die Nährwert-Skalierung); recipeGrams ist
     *  ausschliesslich für die Anzeige ("180 g" statt "0.8 Portionen"). */
    val recipeGrams: Float? = null,
    /** Globale Makro-Korrektur (siehe MacroField): true, wenn der Nutzer Kalorien/
     *  Protein/Kohlenhydrate/Fett/Ballaststoffe direkt überschrieben hat, statt über
     *  die Zutatenebene zu korrigieren. Die Zutaten (falls vorhanden) bleiben dabei
     *  unangetastet - nur die Endsumme dieses Eintrags wird ersetzt. */
    @ColumnInfo(defaultValue = "0") val isGloballyOverridden: Boolean = false,
    /** Snapshot der automatisch berechneten Werte vor dem ersten Override, damit
     *  "Override entfernen" die ursprünglichen Werte wiederherstellen kann. Wird bei
     *  Mengenänderungen proportional mitskaliert (siehe updateEntryAmount). */
    val originalCalories: Float? = null,
    val originalProtein: Float? = null,
    val originalCarbs: Float? = null,
    val originalFat: Float? = null,
    val originalFiber: Float? = null,
    /** Verknüpfung zu custom_foods.id, falls dieser Eintrag (z.B. beim Yazio-Import)
     *  einem eigenen Lebensmittel zugeordnet werden konnte. */
    val matchedCustomFoodId: Int? = null,
    /** Verknüpfung zu recipes.id, falls dieser Eintrag einem importierten Rezept
     *  zugeordnet werden konnte. */
    val matchedRecipeId: Long? = null,
    // ── Snapshot zum Log-Zeitpunkt (OpenNutriTracker-Ansatz): bleibt stabil,
    // auch wenn FoodItem später geändert/gelöscht wird. Nullable = Legacy-Zeilen.
    @ColumnInfo(defaultValue = "NULL") val snapshotBrand: String? = null,
    @ColumnInfo(defaultValue = "NULL") val snapshotBarcode: String? = null,
    @ColumnInfo(defaultValue = "NULL") val snapshotCaloriesPer100g: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val snapshotProteinPer100g: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val snapshotCarbsPer100g: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val snapshotFatPer100g: Float? = null,
    @ColumnInfo(defaultValue = "NULL") val snapshotSource: String? = null
)

enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }

/**
 * Art eines Tagebuch-Eintrags. Ersetzt Heuristiken wie
 * `foodItemId < 0` / `amountGrams == 0`.
 *
 * - FOOD: normales Lebensmittel (amountGrams = Gramm)
 * - RECIPE: Rezept (amountGrams = Portionsfaktor; optional recipeGrams = Anzeige in g)
 * - MANUAL: manuell erfasste kcal/Makros (foodItemId = -999)
 */
enum class DiaryEntryKind { FOOD, RECIPE, MANUAL }

/** foodItemId-Marker für manuell erfasste Einträge (kein FoodItem in der DB). */
const val MANUAL_FOOD_ITEM_ID: Int = -999

val DiaryEntry.kind: DiaryEntryKind
    get() = when {
        foodItemId == MANUAL_FOOD_ITEM_ID -> DiaryEntryKind.MANUAL
        foodItemId < 0 -> DiaryEntryKind.RECIPE
        else -> DiaryEntryKind.FOOD
    }

val DiaryEntry.isManualEntry: Boolean get() = kind == DiaryEntryKind.MANUAL
val DiaryEntry.isRecipeEntry: Boolean get() = kind == DiaryEntryKind.RECIPE
val DiaryEntry.isFoodEntry: Boolean get() = kind == DiaryEntryKind.FOOD

/**
 * true, wenn die Menge als Portion (nicht als Gramm) zu interpretieren ist.
 * Deckt auch Legacy-Fälle (amountGrams ≈ 1 bei hoher kcal).
 */
val DiaryEntry.isPortionTracked: Boolean
    get() = when {
        isManualEntry -> true
        isRecipeEntry -> recipeGrams == null || recipeGrams < 10f
        recipeGrams != null -> true
        amountGrams <= 0f -> true
        // Legacy: „1 g“ bei ~vollen Portions-kcal kann keine echte Gramm-Angabe sein
        amountGrams < 10f && calories >= 40f -> true
        else -> false
    }

/** true, wenn Rezept-Menge in Gramm erfasst wurde (recipeGrams ≥ 10). */
val DiaryEntry.isGramTrackedRecipe: Boolean
    get() = isRecipeEntry && recipeGrams != null && recipeGrams >= 10f

/**
 * Skaliert alle Nährwerte (und optionale Original-Snapshots) um [factor].
 * amountGrams / recipeGrams werden bewusst nicht angefasst — der Aufrufer setzt sie.
 */
fun DiaryEntry.scaledBy(factor: Float): DiaryEntry {
    if (factor == 1f) return this
    return copy(
        calories = calories * factor,
        protein = protein * factor,
        carbs = carbs * factor,
        fat = fat * factor,
        fiber = fiber * factor,
        sugar = sugar * factor,
        saturatedFat = saturatedFat * factor,
        salt = salt * factor,
        sodium = sodium * factor,
        originalCalories = originalCalories?.let { it * factor },
        originalProtein = originalProtein?.let { it * factor },
        originalCarbs = originalCarbs?.let { it * factor },
        originalFat = originalFat?.let { it * factor },
        originalFiber = originalFiber?.let { it * factor }
    )
}

/** Die fünf Makro-/Nährwertfelder, die per direkter Korrektur (ohne Zutaten-Umweg)
 *  überschrieben werden können. */
enum class MacroField(val label: String, val unit: String) {
    CALORIES("Kalorien", "kcal"),
    PROTEIN("Protein", "g"),
    CARBS("Kohlenhydrate", "g"),
    FAT("Fett", "g"),
    FIBER("Ballaststoffe", "g")
}

fun DiaryEntry.valueOf(field: MacroField): Float = when (field) {
    MacroField.CALORIES -> calories
    MacroField.PROTEIN  -> protein
    MacroField.CARBS    -> carbs
    MacroField.FAT      -> fat
    MacroField.FIBER    -> fiber
}

/** Wandelt den gespeicherten "meal_order"-Preference-String in eine vollständige MealType-Reihenfolge um. */
fun parseMealOrder(stored: String?): List<MealType> {
    val defaults = MealType.entries
    if (stored.isNullOrBlank()) return defaults
    val parsed = stored.split(",").mapNotNull { runCatching { MealType.valueOf(it) }.getOrNull() }
    return parsed + defaults.filter { it !in parsed }
}

/** Standard-Mahlzeiten-Split (Anteil am Tagesziel). Summe ≈ 1.0 */
val DEFAULT_MEAL_SPLIT: Map<MealType, Float> = mapOf(
    MealType.BREAKFAST to 0.25f,
    MealType.LUNCH to 0.35f,
    MealType.DINNER to 0.30f,
    MealType.SNACK to 0.10f
)

/** Presets analog OpenNutriTracker */
enum class MealSplitPreset(val label: String, val splits: Map<MealType, Float>) {
    STANDARD("Standard (3+Snack)", DEFAULT_MEAL_SPLIT),
    OMAD("OMAD (1 Mahlzeit)", mapOf(
        MealType.BREAKFAST to 0f, MealType.LUNCH to 0f, MealType.DINNER to 1f, MealType.SNACK to 0f
    )),
    TWO_MEAL("2 Mahlzeiten", mapOf(
        MealType.BREAKFAST to 0f, MealType.LUNCH to 0.45f, MealType.DINNER to 0.45f, MealType.SNACK to 0.10f
    )),
    FIVE_SMALL("5 kleine", mapOf(
        MealType.BREAKFAST to 0.20f, MealType.LUNCH to 0.20f, MealType.DINNER to 0.20f, MealType.SNACK to 0.40f
    ))
}

fun parseMealSplit(stored: String?): Map<MealType, Float> {
    if (stored.isNullOrBlank()) return DEFAULT_MEAL_SPLIT
    return runCatching {
        val cleaned = stored.trim().removePrefix("{").removeSuffix("}")
        val map = mutableMapOf<MealType, Float>()
        cleaned.split(",").forEach { part ->
            val kv = part.split(":")
            if (kv.size == 2) {
                val key = kv[0].trim().removeSurrounding("\"")
                val value = kv[1].trim().toFloatOrNull() ?: return@forEach
                runCatching { MealType.valueOf(key) }.getOrNull()?.let { map[it] = value }
            }
        }
        if (map.isEmpty()) DEFAULT_MEAL_SPLIT else DEFAULT_MEAL_SPLIT + map
    }.getOrDefault(DEFAULT_MEAL_SPLIT)
}

fun mealSplitToJson(split: Map<MealType, Float>): String =
    MealType.entries.joinToString(",", "{", "}") { "\"${it.name}\":${split[it] ?: 0f}" }

fun mealKcalTarget(dailyGoal: Int, meal: MealType, split: Map<MealType, Float>): Int =
    ((split[meal] ?: 0f) * dailyGoal).toInt().coerceAtLeast(0)

// ─── Rezept-Tags / Diät-Filter ───────────────────────────────────────────────
enum class DietTag(val label: String, val emoji: String) {
    VEGAN("Vegan", "🌱"),
    VEGETARIAN("Vegetarisch", "🥗"),
    LOW_CARB("Low Carb", "🥩"),
    HIGH_PROTEIN("Proteinreich", "💪"),
    LOW_CALORIE("Kalorienarm", "⚡"),
    GLUTEN_FREE("Glutenfrei", "🌾"),
    DAIRY_FREE("Laktosefrei", "🥛"),
    QUICK("Schnell (<30 Min)", "⏱️")
}

/** Mahlzeit-Kategorie für Sortierung/Filter (Frühstück, Dessert, …). */
enum class RecipeCategory(val label: String, val emoji: String) {
    BREAKFAST("Frühstück", "🌅"),
    MAIN("Hauptgericht", "🍽️"),
    SIDE_SNACK("Beilage / Snack", "🥗"),
    DESSERT("Dessert", "🍰"),
    DRINK("Getränk", "🥤"),
    SAUCE("Sauce / Dip", "🫙"),
    OTHER("Sonstiges", "📋");

    /**
     * Ob dieses Gericht sinnvoll in Beilage / Sauce-Fleisch aufgeteilt werden kann.
     * Frühstück, Dessert und Getränke sind ein Gericht – nie splitten.
     */
    val allowsComponentSplit: Boolean
        get() = this == MAIN || this == SIDE_SNACK || this == SAUCE || this == OTHER

    companion object {
        fun fromStored(value: String?): RecipeCategory {
            if (value.isNullOrBlank()) return OTHER
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: OTHER
        }

        /** Heuristik aus Titel/Zutaten – für Import und leere Kategorien. */
        fun guess(title: String, ingredients: String = "", description: String = ""): RecipeCategory {
            // „süß“ allein trifft fälschlich „Süßkartoffel“ — deshalb keine nackten süß/süss.
            val t = "$title\n$description\n$ingredients".lowercase()
                .replace("süßkartoffel", "suesskartoffel")
                .replace("süsskartoffel", "suesskartoffel")
                .replace("sweet potato", "suesskartoffel")

            val savoryMain = listOf(
                "hackfleisch", "hähnchen", "haehnchen", "hühner", "huehner", "chicken",
                "rind", "schwein", "lachs", "fisch", "garnele", "tofu", "curry",
                "pfanne", "pfannen", "bowl", "pasta", "nudeln", "risotto", "eintopf",
                "suppe", "gulasch", "braten", "steak", "masala", "chili", "taco", "wrap",
                "piadina", "piadine", "fladen", "fladenbrot", "quesadilla"
            )
            val dessert = listOf(
                "dessert", "nachtisch", "kuchen", "brownie", "cookie", "keks", "pudding",
                "eiscreme", "ice cream", "mousse", "cheesecake", "tiramisu",
                "schokolade", "chocolate", "muffin", "cupcake", "süßspeise", "suessspeise",
                "oreo", "cremefüllung", "cremefuellung", "quarkdessert", "protein pudding",
                "proteinpudding"
            )
            // Eindeutige Frühstücks-Gerichtsnamen — schlagen Dessert-Zutaten im Topping/Rezept
            // (z.B. "Bueno Overnight Oats" mit Schokolade-Topping darf nicht als Dessert landen).
            val breakfastStrong = listOf(
                "frühstück", "fruehstueck", "breakfast", "overnight", "overnight oats", "porridge",
                "oats", "haferflocken", "müsli", "muesli", "granola", "pancake", "pfannkuchen",
                "french toast", "smoothie bowl", "joghurt bowl", "yogurt bowl",
                "magerquark", "skyr", "haferbrei"
            )
            // Schwaches Signal (nur Zutat, kein Gerichtsname) — Dessert-Treffer geht vor
            // (z.B. "Schoko-Chia-Pudding" bleibt Dessert).
            val breakfastWeak = listOf("chia")
            val drink = listOf("smoothie", "shake", "saft", "juice", "latte", "matcha drink", "protein shake")
            val sauce = listOf("sauce", "soße", "sosse", "dressing", "dip ", "mayo", "pesto")
            val snack = listOf("snack", "beilage", "side dish", "energy ball", "proteinriegel")

            // Herzhaftes Gericht schlägt Dessert/Frühstück (z.B. Süßkartoffel-Hack-Pfanne)
            if (savoryMain.any { it in t }) return MAIN
            when {
                breakfastStrong.any { it in t } -> return BREAKFAST
                dessert.any { it in t } -> return DESSERT
                breakfastWeak.any { it in t } -> return BREAKFAST
                drink.any { it in t } && "bowl" !in t -> return DRINK
                sauce.any { it in t } && "pasta" !in t && "nudeln" !in t -> return SAUCE
                snack.any { it in t } -> return SIDE_SNACK
                else -> return MAIN
            }
        }
    }
}

// ─── Recipes ─────────────────────────────────────────────────────────────────
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val platform: String? = null,
    val ingredients: String = "",
    val instructions: String = "",
    val totalCalories: Float? = null,
    val proteinPerServing: Float? = null,
    val carbsPerServing: Float? = null,
    val fatPerServing: Float? = null,
    val fiberPerServing: Float? = null,
    val sugarPerServing: Float? = null,
    val saturatedFatPerServing: Float? = null,
    val saltPerServing: Float? = null,
    val sodiumPerServing: Float? = null,
    /** Vitamine/Mineralstoffe pro Portion als JSON-Map (z.B. {"vitaminC":0.012,"iron":0.003}).
     *  Einzelne Spalten wie bei FoodItem wären hier Overkill — die Werte kommen ohnehin
     *  nur aus der Analyse und werden nie einzeln abgefragt/gefiltert. */
    val microNutrientsJson: String? = null,
    val servings: Int = 1,
    val prepTimeMinutes: Int? = null,
    val tags: String = "",          // Komma-separierte DietTag-Namen
    /** RecipeCategory.name – leer = noch nicht gesetzt. */
    val mealCategory: String = "",
    val collectionId: Long? = null,
    val isFavorite: Boolean = false,
    val showNutrition: Boolean = true,
    val savedAt: Long = System.currentTimeMillis(),
    /** Summe der Zutatenmengen in g (Rohgewicht vor dem Kochen). */
    val totalIngredientWeightG: Float? = null,
    /** Gewicht nach dem Kochen (optional) – z.B. Nudeln mit Wasseraufnahme. */
    val cookedWeightG: Float? = null,
    /** Wie oft ins Tagebuch übernommen / gekocht. */
    val timesCooked: Int = 0,
    /** Unix-ms des letzten Trackings. */
    val lastCookedAt: Long? = null,
    /** Letzte Bewertung 1–5 (0 = noch keine). */
    val cookRating: Int = 0,
    /** Notiz fürs nächste Mal, z.B. „Himbeeren mit Erythrit süssen“. */
    val nextTimeNote: String = ""
) {
    /** Gewicht für Gramm-Tracking: gekocht falls gesetzt, sonst Roh-Zutatensumme. */
    fun yieldWeightG(): Float? =
        cookedWeightG?.takeIf { it > 0f } ?: totalIngredientWeightG?.takeIf { it > 0f }

    fun getDietTags(): List<DietTag> =
        tags.split(",").mapNotNull { tag ->
            DietTag.entries.firstOrNull { it.name == tag.trim() }
        }

    fun category(): RecipeCategory = RecipeCategory.fromStored(mealCategory)

    fun withGuessedCategoryIfEmpty(): Recipe =
        if (mealCategory.isNotBlank()) this
        else copy(mealCategory = RecipeCategory.guess(title, ingredients, description).name)

    /**
     * Anzeigetitel ohne JSON-Null-Artefakte ("null"/"undefined" von optString).
     * Fallback: erste sinnvolle Zutatenzeile oder „Rezept“.
     */
    fun displayTitle(): String {
        val t = title.trim()
        if (t.isNotEmpty() && !t.equals("null", true) && !t.equals("undefined", true)) {
            return cleanSocialCaption(t)
        }
        val fromIngredients = ingredients.lineSequence()
            .map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.length in 3..60 && !it.equals("null", true) }
        return fromIngredients?.let { cleanSocialCaption(it) } ?: "Rezept"
    }

    fun displayDescription(): String {
        val d = description.trim()
        return if (d.isEmpty() || d.equals("null", true) || d.equals("undefined", true)) "" else d
    }

    /** Bereinigte Kopie für Speichern/Sync nach kaputtem Import. */
    fun withoutNullArtifacts(): Recipe = copy(
        title = displayTitle(),
        description = displayDescription()
    )
}

/**
 * Entfernt typische Social-Media-Caption-Floskeln aus importierten Titeln
 * ("POV: ...", "Recipe? It's at the end", "Follow for more", "Link in Bio" etc.),
 * ohne den eigentlichen Rezeptnamen zu beschädigen. Greift nur bei eindeutigen
 * Mustern und lässt den Titel unverändert, wenn danach zu wenig übrig bliebe.
 */
private val socialLeadingJunk = listOf(
    Regex("""^pov\s*[:\-]\s*""", RegexOption.IGNORE_CASE),
    Regex("""^(recipe|rezept)\s*(alert|idea)?\s*[:\-!]\s*""", RegexOption.IGNORE_CASE)
)
private val socialTrailingJunk = listOf(
    Regex("""[!.,\s]*recipe\??\s*(is\s*)?(in|at)\s*(the\s*)?(comments?|end|bio|caption)\.?$""", RegexOption.IGNORE_CASE),
    Regex("""[!.,\s]*(full\s*)?recipe\s*(below|down below)\.?$""", RegexOption.IGNORE_CASE),
    Regex("""[!.,\s]*swipe\s*(up|for\s*recipe)?\.?$""", RegexOption.IGNORE_CASE),
    Regex("""[!.,\s]*(follow|save this)\s*(me|for more)?\.?$""", RegexOption.IGNORE_CASE),
    Regex("""[!.,\s]*link\s*in\s*bio\.?$""", RegexOption.IGNORE_CASE)
)
private val leadingEmojiOrPunct = Regex("""^[\p{So}\p{Cn}\s!?.\-–—]+""")
private val trailingEmojiOrPunct = Regex("""[\p{So}\p{Cn}\s!?.\-–—]+$""")

private fun cleanSocialCaption(raw: String): String {
    var s = raw.trim()
    for (re in socialLeadingJunk) s = re.replace(s, "")
    for (re in socialTrailingJunk) s = re.replace(s, "")
    s = s.replace(leadingEmojiOrPunct, "").replace(trailingEmojiOrPunct, "")
    s = s.replace(Regex("""\s{2,}"""), " ").trim()
    // Sicherheitsnetz: nichts kaputt bereinigen – bei zu kurzem Rest Original behalten.
    return if (s.length >= 3) s else raw.trim()
}

/**
 * Teil eines Multi-Komponenten-Rezepts (z. B. „Reis & Erbsen“ und „Sauce“).
 * Nährwerte und cookedWeightG beziehen sich auf die **gesamte** Komponente
 * (Batch), nicht auf 100 g. Beim Tracken wird mit grams / cookedWeightG skaliert.
 *
 * Rezepte ohne Einträge in dieser Tabelle verhalten sich wie bisher (One-Pot /
 * einzelne Einheit).
 */
@Entity(
    tableName = "recipe_components",
    foreignKeys = [
        ForeignKey(
            entity = Recipe::class,
            parentColumns = ["id"],
            childColumns = ["recipeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["recipeId"])]
)
data class RecipeComponent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    /** Anzeigename, z. B. „Reis & Erbsen“ oder „Butter-Chicken-Sauce“. */
    val name: String,
    /** Gesamtgewicht dieser Komponente nach dem Kochen (g). */
    val cookedWeightG: Float,
    /** Gesamtkalorien der Komponente (Batch). */
    val totalCalories: Float,
    val proteinG: Float = 0f,
    val carbsG: Float = 0f,
    val fatG: Float = 0f,
    val fiberG: Float = 0f,
    val sortOrder: Int = 0
) {
    /** Nährwerte für [grams] dieser Komponente. */
    fun scaledTo(grams: Float): ScaledComponentNutrition {
        val factor = if (cookedWeightG > 0f) (grams / cookedWeightG).coerceAtLeast(0f) else 0f
        return ScaledComponentNutrition(
            grams = grams,
            calories = totalCalories * factor,
            protein = proteinG * factor,
            carbs = carbsG * factor,
            fat = fatG * factor,
            fiber = fiberG * factor
        )
    }
}

/** Ergebnis der Skalierung einer [RecipeComponent] auf eine abgewogene Menge. */
data class ScaledComponentNutrition(
    val grams: Float,
    val calories: Float,
    val protein: Float,
    val carbs: Float,
    val fat: Float,
    val fiber: Float
)

// ─── Rezept-Sammlungen ───────────────────────────────────────────────────────
@Entity(tableName = "recipe_collections")
data class RecipeCollection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "📁",
    val createdAt: Long = System.currentTimeMillis()
)

// ─── OpenFoodFacts API ───────────────────────────────────────────────────────
@Serializable
data class OFFSearchResponse(
    val count: Int = 0,
    val products: List<OFFProduct> = emptyList()
)

@Serializable
data class OFFProduct(
    @SerialName("product_name") val product_name: String? = null,
    val brands: String? = null,
    val nutriments: OFFNutriments? = null,
    @SerialName("image_front_small_url") val image_front_small_url: String? = null,
    @SerialName("image_url") val image_url: String? = null
)

@Serializable
data class OFFNutriments(
    @SerialName("energy-kcal_100g") val energyKcal100g: Float? = null,
    @SerialName("energy-kcal") val energyKcal: Float? = null,
    @SerialName("energy_kcal_100g") val energyKcalAlt: Float? = null,
    @SerialName("proteins_100g") val proteins100g: Float? = null,
    @SerialName("carbohydrates_100g") val carbs100g: Float? = null,
    @SerialName("fat_100g") val fat100g: Float? = null,
    @SerialName("fiber_100g") val fiber100g: Float? = null
) {
    val kcalPer100g: Float? get() = energyKcal100g ?: energyKcalAlt ?: energyKcal
}

@Serializable
data class SingleProductResponse(val status: Int = 0, val product: OFFProduct? = null)

// ─── UI helpers ──────────────────────────────────────────────────────────────
data class DailyNutrition(
    val date: LocalDate,
    val calories: Float, val protein: Float, val carbs: Float, val fat: Float,
    val goalCalories: Float = 2000f
) { val progress get() = (calories / goalCalories).coerceIn(0f, 1f) }

data class RecipeScrapeResult(
    val success: Boolean,
    val recipe: Recipe? = null,
    val error: String? = null,
    val instagramBlocked: Boolean = false
)

// ─── Weight Tracking ─────────────────────────────────────────────────────────
@Entity(tableName = "weight_entries")
data class WeightEntry(@PrimaryKey val dateStr: String, val weightKg: Float)

/** Manuell erfasste Aktivitätskalorien pro Tag (zusätzlich zu Health Connect). */
@Entity(tableName = "manual_activity")
data class ManualActivityEntry(
    @PrimaryKey val dateStr: String,  // yyyy-MM-dd
    val activeCaloriesKcal: Float
)

// ─── Favorites ───────────────────────────────────────────────────────────────
// Note: DB column names kept as-is for backward compatibility with Migration 2→3 SQL
@Entity(tableName = "favorite_foods")
data class FavoriteFoodEntity(
    @PrimaryKey val foodKey: String,
    val name: String,
    val brand: String? = null,
    val caloriesPer100g: Float?,   // column name kept for DB compat; nullable = Quelle kannte den Wert nicht
    val proteinPer100g: Float?,
    val carbsPer100g: Float?,
    val fatPer100g: Float?,
    val fiberPer100g: Float? = null,
    val addedAt: Long = System.currentTimeMillis()
)

// Extension functions updated to use new FoodItem schema (FoodItem.kt)
fun FoodItem.favoriteKey(): String = "${name.trim().lowercase()}|${brand?.trim()?.lowercase() ?: ""}"

fun FoodItem.toFavoriteEntity() = FavoriteFoodEntity(
    foodKey        = favoriteKey(),
    name           = name,
    brand          = brand,
    caloriesPer100g = calories,
    proteinPer100g  = protein,
    carbsPer100g    = carbs,
    fatPer100g      = fat,
    fiberPer100g    = fiber
)

fun FavoriteFoodEntity.toFoodItem() = FoodItem(
    id       = 0,
    name     = name,
    brand    = brand,
    calories = caloriesPer100g,
    protein  = proteinPer100g,
    carbs    = carbsPer100g,
    fat      = fatPer100g,
    fiber    = fiberPer100g,
    source   = FoodSource.MANUAL
)

// ─── Gefrierschrank ──────────────────────────────────────────────────────────

/** Eine Linie einer eingefrorenen Portion (Beilage, Sauce, …). */
data class FrozenPortionLine(
    val name: String,
    val grams: Float,
    val calories: Float,
    val protein: Float = 0f,
    val carbs: Float = 0f,
    val fat: Float = 0f,
    val fiber: Float = 0f
)

/**
 * Eingefrorenes Menü / Meal-Prep-Pack.
 * [portionJson] speichert die Linien **einer** Portion als JSON-Array.
 * [quantity] = wie viele identische Packungen noch im Gefrierer liegen.
 */
@Entity(tableName = "frozen_meals")
data class FrozenMeal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long? = null,
    val name: String,
    val quantity: Int = 1,
    val frozenAt: Long = System.currentTimeMillis(),
    val notes: String = "",
    /** JSON-Array von {name, grams, calories, protein, carbs, fat, fiber} für eine Portion. */
    val portionJson: String = "[]"
) {
    fun portionLines(): List<FrozenPortionLine> {
        if (portionJson.isBlank() || portionJson == "[]") return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(portionJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                FrozenPortionLine(
                    name = o.optString("name", "Teil"),
                    grams = o.optDouble("grams", 0.0).toFloat(),
                    calories = o.optDouble("calories", 0.0).toFloat(),
                    protein = o.optDouble("protein", 0.0).toFloat(),
                    carbs = o.optDouble("carbs", 0.0).toFloat(),
                    fat = o.optDouble("fat", 0.0).toFloat(),
                    fiber = o.optDouble("fiber", 0.0).toFloat()
                )
            }
        }.getOrDefault(emptyList())
    }

    fun totalCaloriesPerPortion(): Float = portionLines().sumOf { it.calories.toDouble() }.toFloat()
    fun totalProteinPerPortion(): Float = portionLines().sumOf { it.protein.toDouble() }.toFloat()
    fun totalGramsPerPortion(): Float = portionLines().sumOf { it.grams.toDouble() }.toFloat()

    companion object {
        fun encodePortionLines(lines: List<FrozenPortionLine>): String {
            val arr = org.json.JSONArray()
            for (l in lines) {
                arr.put(org.json.JSONObject().apply {
                    put("name", l.name)
                    put("grams", l.grams.toDouble())
                    put("calories", l.calories.toDouble())
                    put("protein", l.protein.toDouble())
                    put("carbs", l.carbs.toDouble())
                    put("fat", l.fat.toDouble())
                    put("fiber", l.fiber.toDouble())
                })
            }
            return arr.toString()
        }
    }
}
