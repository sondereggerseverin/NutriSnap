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
                .setConfidenceThreshold(0.45f)
                .build()
        )
    }

    /**
     * Englische ML-Kit-Labels (und Varianten) → deutsche Anzeigenamen + Default-Portion in g.
     * Nur Einträge, die für Ernährung sinnvoll sind (kein "Table", "Person", …).
     */
    private val foodMap: Map<String, Pair<String, Float>> = mapOf(
        "apple" to ("Apfel" to 150f),
        "banana" to ("Banane" to 120f),
        "orange" to ("Orange" to 150f),
        "lemon" to ("Zitrone" to 60f),
        "strawberry" to ("Erdbeere" to 80f),
        "grape" to ("Trauben" to 100f),
        "watermelon" to ("Wassermelone" to 200f),
        "pineapple" to ("Ananas" to 150f),
        "pear" to ("Birne" to 150f),
        "peach" to ("Pfirsich" to 120f),
        "mango" to ("Mango" to 150f),
        "tomato" to ("Tomate" to 100f),
        "cucumber" to ("Gurke" to 100f),
        "carrot" to ("Rüebli" to 80f),
        "broccoli" to ("Brokkoli" to 100f),
        "lettuce" to ("Salat" to 80f),
        "potato" to ("Kartoffel" to 150f),
        "onion" to ("Zwiebel" to 80f),
        "garlic" to ("Knoblauch" to 10f),
        "corn" to ("Mais" to 100f),
        "mushroom" to ("Pilze" to 80f),
        "egg" to ("Ei" to 60f),
        "bread" to ("Brot" to 60f),
        "bagel" to ("Bagel" to 100f),
        "pretzel" to ("Brezel" to 80f),
        "croissant" to ("Gipfeli" to 60f),
        "pizza" to ("Pizza" to 250f),
        "hamburger" to ("Hamburger" to 220f),
        "hot dog" to ("Hot Dog" to 150f),
        "sandwich" to ("Sandwich" to 180f),
        "burrito" to ("Burrito" to 250f),
        "taco" to ("Taco" to 120f),
        "sushi" to ("Sushi" to 150f),
        "noodles" to ("Nudeln" to 250f),
        "pasta" to ("Pasta" to 250f),
        "rice" to ("Reis" to 180f),
        "soup" to ("Suppe" to 300f),
        "salad" to ("Salat" to 200f),
        "cake" to ("Kuchen" to 100f),
        "cookie" to ("Keks" to 30f),
        "doughnut" to ("Donut" to 70f),
        "ice cream" to ("Glace" to 100f),
        "chocolate" to ("Schokolade" to 40f),
        "candy" to ("Süssigkeit" to 30f),
        "cheese" to ("Käse" to 40f),
        "yogurt" to ("Joghurt" to 150f),
        "milk" to ("Milch" to 200f),
        "coffee" to ("Kaffee" to 200f),
        "tea" to ("Tee" to 200f),
        "wine" to ("Wein" to 150f),
        "beer" to ("Bier" to 300f),
        "juice" to ("Saft" to 200f),
        "smoothie" to ("Smoothie" to 250f),
        "steak" to ("Steak" to 180f),
        "chicken" to ("Hähnchen" to 150f),
        "fish" to ("Fisch" to 150f),
        "shrimp" to ("Garnelen" to 100f),
        "sausage" to ("Wurst" to 80f),
        "bacon" to ("Speck" to 30f),
        "french fries" to ("Pommes" to 150f),
        "popcorn" to ("Popcorn" to 50f),
        "pancake" to ("Pancake" to 120f),
        "waffle" to ("Waffel" to 100f),
        "food" to ("Gericht" to 300f),
        "fruit" to ("Obst" to 150f),
        "vegetable" to ("Gemüse" to 120f),
        "seafood" to ("Meeresfrüchte" to 150f),
        "dessert" to ("Dessert" to 120f),
        "breakfast" to ("Frühstück" to 300f)
    )

    /**
     * Analysiert ein Bitmap on-device. Leere Liste / Failure wenn nichts Lebensmittel-ähnliches
     * erkannt wird (dann soll der Aufrufer eine klare Offline-Fehlermeldung zeigen).
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

        val ingredients = labels.mapNotNull { label ->
            val key = label.text.lowercase().trim()
            val mapped = foodMap[key] ?: foodMap.entries.firstOrNull { key.contains(it.key) }?.value
            mapped?.let { (deName, grams) ->
                val conf = when {
                    label.confidence >= 0.75f -> "mittel"
                    label.confidence >= 0.55f -> "niedrig"
                    else -> "niedrig"
                }
                // On-Device nie "hoch" – Cloud-Vision bleibt die präzisere Quelle
                DishIngredientCandidate(
                    name = deName,
                    estimatedGrams = grams,
                    confidence = conf
                )
            }
        }
            .distinctBy { it.name.lowercase() }
            .take(8)

        if (ingredients.isEmpty()) {
            error("On-Device: keine Lebensmittel erkannt")
        }

        val dishName = when {
            ingredients.size == 1 -> ingredients.first().name
            else -> ingredients.take(3).joinToString(" + ") { it.name }
        }

        DishScanResult(dishName = dishName, ingredients = ingredients)
    }
}
