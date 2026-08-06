package ch.nutrisnap.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// ─── Diary ───────────────────────────────────────────────────────────────────
@Entity(tableName = "diary_entries")
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
    val matchedRecipeId: Long? = null
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

    companion object {
        fun fromStored(value: String?): RecipeCategory {
            if (value.isNullOrBlank()) return OTHER
            return entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: OTHER
        }

        /** Heuristik aus Titel/Zutaten – für Import und leere Kategorien. */
        fun guess(title: String, ingredients: String = "", description: String = ""): RecipeCategory {
            val t = "$title\n$description\n$ingredients".lowercase()
            val dessert = listOf(
                "dessert", "nachtisch", "kuchen", "brownie", "cookie", "keks", "pudding",
                "eis ", "ice cream", "mousse", "cheesecake", "tiramisu", "süß", "süss",
                "schoko", "chocolate", "muffin", "cupcake"
            )
            val breakfast = listOf(
                "frühstück", "fruehstueck", "breakfast", "overnight", "chia", "porridge",
                "oats", "hafer", "müsli", "muesli", "granola", "pancake", "pfannkuchen",
                "french toast", "smoothie bowl", "joghurt bowl", "yogurt bowl"
            )
            val drink = listOf("smoothie", "shake", "saft", "juice", "latte", "matcha drink", "protein shake")
            val sauce = listOf("sauce", "soße", "sosse", "dressing", "dip ", "mayo", "pesto")
            val snack = listOf("snack", "beilage", "side ", "wrap (klein)", "energy ball", "riegel")
            when {
                dessert.any { it in t } -> return DESSERT
                breakfast.any { it in t } -> return BREAKFAST
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
    val cookedWeightG: Float? = null
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
        if (t.isNotEmpty() && !t.equals("null", true) && !t.equals("undefined", true)) return t
        val fromIngredients = ingredients.lineSequence()
            .map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }
            .firstOrNull { it.length in 3..60 && !it.equals("null", true) }
        return fromIngredients ?: "Rezept"
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
