package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Leichter On-Device-Fallback für Food-Scan (Phase C):
 * ML Kit Image Labeling läuft ohne Cloud und ohne grosses LLM-Modell.
 * Liefert grobe Lebensmittel-Klassen → [DishScanResult] mit niedriger/mittlerer Confidence.
 *
 * Kein Ersatz für Cloud-Vision (Zutaten-Zerlegung), sondern Offline-/Fehler-Fallback.
 */
object OnDeviceFoodLabeler {

    private val labeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(0.42f)
                .build()
        )
    }

    /** Generische Labels – nur behalten, wenn keine spezifischere Zutat gefunden wurde. */
    private val genericKeys = setOf(
        "food", "fruit", "vegetable", "seafood", "dessert", "breakfast", "meal", "dish"
    )

    /**
     * Englische ML-Kit-Labels (und Varianten) → deutsche Anzeigenamen + Default-Portion in g.
     * Nur Einträge, die für Ernährung sinnvoll sind (kein "Table", "Person", …).
     */
    internal val foodMap: Map<String, Pair<String, Float>> = mapOf(
        // Obst
        "apple" to ("Apfel" to 150f),
        "banana" to ("Banane" to 120f),
        "orange" to ("Orange" to 150f),
        "lemon" to ("Zitrone" to 60f),
        "lime" to ("Limette" to 50f),
        "strawberry" to ("Erdbeere" to 80f),
        "blueberry" to ("Heidelbeere" to 80f),
        "raspberry" to ("Himbeere" to 80f),
        "grape" to ("Trauben" to 100f),
        "watermelon" to ("Wassermelone" to 200f),
        "melon" to ("Melone" to 180f),
        "pineapple" to ("Ananas" to 150f),
        "pear" to ("Birne" to 150f),
        "peach" to ("Pfirsich" to 120f),
        "mango" to ("Mango" to 150f),
        "kiwi" to ("Kiwi" to 80f),
        "avocado" to ("Avocado" to 100f),
        "cherry" to ("Kirsche" to 80f),
        "plum" to ("Zwetschge" to 80f),
        // Gemüse
        "tomato" to ("Tomate" to 100f),
        "cucumber" to ("Gurke" to 100f),
        "carrot" to ("Rüebli" to 80f),
        "broccoli" to ("Brokkoli" to 100f),
        "cauliflower" to ("Blumenkohl" to 120f),
        "lettuce" to ("Salat" to 80f),
        "spinach" to ("Spinat" to 80f),
        "potato" to ("Kartoffel" to 150f),
        "sweet potato" to ("Süsskartoffel" to 150f),
        "onion" to ("Zwiebel" to 80f),
        "garlic" to ("Knoblauch" to 10f),
        "corn" to ("Mais" to 100f),
        "mushroom" to ("Pilze" to 80f),
        "pepper" to ("Peperoni" to 80f),
        "bell pepper" to ("Peperoni" to 80f),
        "cabbage" to ("Kohl" to 100f),
        "zucchini" to ("Zucchini" to 120f),
        "eggplant" to ("Aubergine" to 150f),
        "asparagus" to ("Spargel" to 100f),
        "bean" to ("Bohnen" to 100f),
        "peas" to ("Erbsen" to 80f),
        // Ei / Milch
        "egg" to ("Ei" to 53f),
        "cheese" to ("Käse" to 40f),
        "yogurt" to ("Joghurt" to 150f),
        "yoghurt" to ("Joghurt" to 150f),
        "milk" to ("Milch" to 200f),
        "butter" to ("Butter" to 10f),
        // Brot / Gebäck
        "bread" to ("Brot" to 40f),
        "bagel" to ("Bagel" to 100f),
        "pretzel" to ("Brezel" to 80f),
        "croissant" to ("Gipfeli" to 60f),
        "baguette" to ("Baguette" to 60f),
        "toast" to ("Toast" to 30f),
        // Fertiggerichte
        "pizza" to ("Pizza" to 250f),
        "hamburger" to ("Hamburger" to 220f),
        "burger" to ("Burger" to 220f),
        "hot dog" to ("Hot Dog" to 150f),
        "sandwich" to ("Sandwich" to 180f),
        "burrito" to ("Burrito" to 250f),
        "taco" to ("Taco" to 90f),
        "sushi" to ("Sushi" to 150f),
        "noodles" to ("Nudeln" to 250f),
        "pasta" to ("Pasta" to 250f),
        "spaghetti" to ("Spaghetti" to 250f),
        "rice" to ("Reis" to 180f),
        "soup" to ("Suppe" to 300f),
        "salad" to ("Salat" to 200f),
        "french fries" to ("Pommes" to 150f),
        "fries" to ("Pommes" to 150f),
        // Süsses
        "cake" to ("Kuchen" to 100f),
        "cookie" to ("Keks" to 15f),
        "doughnut" to ("Donut" to 55f),
        "donut" to ("Donut" to 55f),
        "ice cream" to ("Glace" to 60f),
        "chocolate" to ("Schokolade" to 40f),
        "candy" to ("Süssigkeit" to 30f),
        "muffin" to ("Muffin" to 60f),
        "pancake" to ("Pancake" to 60f),
        "waffle" to ("Waffel" to 75f),
        "popcorn" to ("Popcorn" to 30f),
        // Protein
        "steak" to ("Steak" to 150f),
        "chicken" to ("Hähnchen" to 150f),
        "poultry" to ("Geflügel" to 150f),
        "fish" to ("Fisch" to 130f),
        "shrimp" to ("Garnelen" to 100f),
        "sausage" to ("Wurst" to 80f),
        "bacon" to ("Speck" to 15f),
        "meat" to ("Fleisch" to 150f),
        "pork" to ("Schweinefleisch" to 150f),
        "beef" to ("Rindfleisch" to 150f),
        // Getränke
        "coffee" to ("Kaffee" to 200f),
        "tea" to ("Tee" to 200f),
        "wine" to ("Wein" to 100f),
        "beer" to ("Bier" to 300f),
        "juice" to ("Saft" to 200f),
        "smoothie" to ("Smoothie" to 250f),
        "cocktail" to ("Cocktail" to 200f),
        // Generisch (niedrige Priorität)
        "food" to ("Gericht" to 300f),
        "fruit" to ("Obst" to 150f),
        "vegetable" to ("Gemüse" to 120f),
        "seafood" to ("Meeresfrüchte" to 150f),
        "dessert" to ("Dessert" to 120f),
        "breakfast" to ("Frühstück" to 300f),
        "meal" to ("Mahlzeit" to 350f),
        "dish" to ("Gericht" to 300f)
    )

    /**
     * Mappt ein einzelnes ML-Kit-Label (Englisch) auf DE-Name + Gramm + Confidence-Stufe.
     * Sichtbar für Unit-Tests.
     */
    internal fun resolveLabel(rawText: String, score: Float): DishIngredientCandidate? {
        val key = rawText.lowercase().trim()
        if (key.isBlank()) return null
        // 1) Exakt  2) Label enthält Map-Key (mind. 4 Zeichen) – kein umgekehrtes
        //    contains, sonst matchen kurze Keys falsch (z.B. "tea" in "table").
        val mapped = foodMap[key]
            ?: foodMap.entries
                .filter { (mapKey, _) ->
                    mapKey.length >= 4 && (key == mapKey || key.contains(mapKey))
                }
                .maxByOrNull { it.key.length }
                ?.value
            ?: return null
        val (deName, grams) = mapped
        val conf = when {
            score >= 0.75f && key !in genericKeys -> "mittel"
            else -> "niedrig"
        }
        return DishIngredientCandidate(
            name = deName,
            estimatedGrams = grams,
            confidence = conf
        )
    }

    /**
     * Baut aus rohen Label-Treffern (text + score) ein [DishScanResult].
     * Generische Labels werden verworfen, sobald mindestens eine spezifische Zutat da ist.
     */
    internal fun buildDishFromLabels(labels: List<Pair<String, Float>>): DishScanResult {
        val resolved = labels.mapNotNull { (text, score) ->
            resolveLabel(text, score)?.let { cand -> text.lowercase().trim() to cand }
        }

        val specific = resolved.filter { (key, _) -> key !in genericKeys }
        val chosen = (if (specific.isNotEmpty()) specific else resolved)
            .sortedByDescending { it.second.estimatedGrams } // stabile Reihenfolge, grössere Portionen zuerst
            .distinctBy { it.second.name.lowercase() }
            .map { it.second }
            .take(8)

        require(chosen.isNotEmpty()) { "On-Device: keine Lebensmittel erkannt" }

        val dishName = when {
            chosen.size == 1 -> chosen.first().name
            else -> chosen.take(3).joinToString(" + ") { it.name }
        }
        return DishScanResult(dishName = dishName, ingredients = chosen)
    }

    /**
     * Analysiert ein Bitmap on-device. Failure wenn nichts Lebensmittel-ähnliches erkannt wird.
     */
    suspend fun analyze(bitmap: Bitmap): Result<DishScanResult> = runCatching {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labels = suspendCancellableCoroutine { cont ->
            labeler.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }
        buildDishFromLabels(labels.map { it.text to it.confidence })
    }
}
