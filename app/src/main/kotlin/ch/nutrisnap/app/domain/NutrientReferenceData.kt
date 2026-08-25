package ch.nutrisnap.app.domain

/**
 * Zentrale Referenztabelle für Vitamine/Mineralstoffe/sonstige Mikronährstoffe.
 *
 * War bisher als private/val direkt in ui/components/Components.kt definiert und
 * daher nur von MicronutrientTable() nutzbar. Für den NutrientDeficiencyEngine
 * (domain/NutrientDeficiencyEngine.kt) hierher verschoben, damit es EINE
 * Quelle der Wahrheit für Labels/Einheiten/NRV-Referenzwerte gibt statt zwei
 * potenziell auseinanderlaufende Kopien.
 *
 * Alle Roh-Werte (in FoodItem, Recipe.microNutrientsJson, NRV_REFERENCE) sind
 * einheitlich in GRAMM. MICRO_META.factor rechnet für die Anzeige in die
 * jeweils übliche Einheit um (µg/mg/g).
 */

/** label, Anzeige-Einheit, Umrechnungsfaktor von Gramm in die Anzeige-Einheit. */
val MICRO_META: Map<String, Triple<String, String, Float>> = mapOf(
    "fiber" to Triple("Ballaststoffe", "g", 1f),
    "sugar" to Triple("Zucker", "g", 1f),
    "saturatedFat" to Triple("Gesättigte Fettsäuren", "g", 1f),
    "monoFat" to Triple("Einfach ungesättigt", "g", 1f),
    "polyFat" to Triple("Mehrfach ungesättigt", "g", 1f),
    "transFat" to Triple("Trans-Fette", "g", 1f),
    "alcohol" to Triple("Alkohol", "g", 1f),
    "cholesterol" to Triple("Cholesterin", "mg", 1000f),
    "salt" to Triple("Salz", "g", 1f),
    "sodium" to Triple("Natrium", "mg", 1000f),
    "water" to Triple("Wasser", "g", 1f),
    "vitaminA" to Triple("Vitamin A", "µg", 1_000_000f),
    "vitaminB1" to Triple("Vitamin B1 (Thiamin)", "mg", 1000f),
    "vitaminB2" to Triple("Vitamin B2 (Riboflavin)", "mg", 1000f),
    "vitaminB3" to Triple("Vitamin B3 (Niacin)", "mg", 1000f),
    "vitaminB5" to Triple("Vitamin B5 (Pantothensäure)", "mg", 1000f),
    "vitaminB6" to Triple("Vitamin B6", "mg", 1000f),
    "vitaminB7" to Triple("Vitamin B7 (Biotin)", "µg", 1_000_000f),
    "vitaminB11" to Triple("Vitamin B11 (Folsäure)", "µg", 1_000_000f),
    "vitaminB12" to Triple("Vitamin B12", "µg", 1_000_000f),
    "vitaminC" to Triple("Vitamin C", "mg", 1000f),
    "vitaminD" to Triple("Vitamin D", "µg", 1_000_000f),
    "vitaminE" to Triple("Vitamin E", "mg", 1000f),
    "vitaminK" to Triple("Vitamin K", "µg", 1_000_000f),
    "potassium" to Triple("Kalium", "mg", 1000f),
    "calcium" to Triple("Calcium", "mg", 1000f),
    "iron" to Triple("Eisen", "mg", 1000f),
    "magnesium" to Triple("Magnesium", "mg", 1000f),
    "zinc" to Triple("Zink", "mg", 1000f),
    "phosphorus" to Triple("Phosphor", "mg", 1000f),
    "copper" to Triple("Kupfer", "mg", 1000f),
    "manganese" to Triple("Mangan", "mg", 1000f),
    "fluoride" to Triple("Fluorid", "mg", 1000f),
    "iodine" to Triple("Jod", "µg", 1_000_000f),
    "selenium" to Triple("Selen", "µg", 1_000_000f),
    "chromium" to Triple("Chrom", "µg", 1_000_000f),
    "molybdenum" to Triple("Molybdän", "µg", 1_000_000f),
    "chloride" to Triple("Chlorid", "mg", 1000f),
    "choline" to Triple("Cholin", "mg", 1000f),
    "arsenic" to Triple("Arsen", "µg", 1_000_000f),
    "boron" to Triple("Bor", "mg", 1000f),
    "cobalt" to Triple("Kobalt", "µg", 1_000_000f),
    "rubidium" to Triple("Rubidium", "mg", 1000f),
    "silicon" to Triple("Silizium", "mg", 1000f),
    "sulfur" to Triple("Schwefel", "mg", 1000f),
    "tin" to Triple("Zinn", "mg", 1000f),
    "vanadium" to Triple("Vanadium", "µg", 1_000_000f)
)

/**
 * EU-Referenzmenge (NRV, "Nutrient Reference Value") je Nährstoff, in Gramm.
 * Bewusst NUR Nährstoffe mit offiziell definiertem EU-NRV (Richtlinie 1169/2011,
 * Anhang XIII) — für Spurenelemente ohne etablierten Referenzwert (z.B. Vanadium,
 * Zinn, Arsen) gibt es hier absichtlich KEINEN Eintrag, damit der
 * NutrientDeficiencyEngine keine erfundenen Zielwerte verwendet.
 */
val NRV_REFERENCE: Map<String, Float> = mapOf(
    "vitaminA" to 0.0008f, "vitaminB1" to 0.0011f, "vitaminB2" to 0.0014f, "vitaminB3" to 0.016f,
    "vitaminB5" to 0.006f, "vitaminB6" to 0.0014f, "vitaminB7" to 0.00005f, "vitaminB11" to 0.0002f,
    "vitaminB12" to 0.0000025f, "vitaminC" to 0.08f, "vitaminD" to 0.000005f, "vitaminE" to 0.012f,
    "vitaminK" to 0.000075f,
    "potassium" to 2f, "calcium" to 0.8f, "iron" to 0.014f, "magnesium" to 0.375f, "zinc" to 0.01f,
    "phosphorus" to 0.7f, "copper" to 0.001f, "manganese" to 0.002f, "iodine" to 0.00015f,
    "selenium" to 0.000055f, "chloride" to 0.8f
)

val MICRO_OTHER = listOf("fiber","sugar","saturatedFat","monoFat","polyFat","transFat","alcohol","cholesterol","salt","sodium","water")
val MICRO_VITAMINS = listOf("vitaminA","vitaminB1","vitaminB2","vitaminB3","vitaminB5","vitaminB6","vitaminB7","vitaminB11","vitaminB12","vitaminC","vitaminD","vitaminE","vitaminK")
val MICRO_MINERALS = listOf("potassium","calcium","iron","magnesium","zinc","phosphorus","copper","manganese","fluoride","iodine","selenium","chromium","molybdenum","chloride","choline","arsenic","boron","cobalt","rubidium","silicon","sulfur","tin","vanadium")
