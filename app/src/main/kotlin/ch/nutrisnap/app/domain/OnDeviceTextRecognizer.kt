package ch.nutrisnap.app.domain

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-Device OCR via ML Kit Text Recognition (Latin).
 *
 * Inspiriert von „All My Meals / Was kann ich essen“: saubere Text-Extraktion
 * aus Screenshots, Rezeptkarten und Social-Media-Bildern – offline, schnell,
 * ohne Cloud-Kosten. Das Ergebnis kann als Caption an [RecipeAiParser] oder
 * als Vorstufe zu Groq-Vision gehen.
 *
 * Kein Ersatz für multimodale Vision (Layout, Bilder-ohne-Text), sondern
 * optimal für textlastige Screenshots.
 */
object OnDeviceTextRecognizer {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Extrahiert den gesamten erkennbaren Text aus einem Bitmap.
     * Zeilen bleiben in Lesereihenfolge (oben → unten, links → rechts).
     *
     * @return Rohtext oder leerer String bei Fehler / keinem Text
     */
    suspend fun recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    if (cont.isActive) {
                        cont.resume(visionText.text.orEmpty().trim())
                    }
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
            cont.invokeOnCancellation {
                // ML Kit bricht laufende Tasks nicht explizit ab; Ergebnis wird verworfen.
            }
        }

    /**
     * Wie [recognize], fängt Fehler ab und liefert null statt Exception.
     * Geeignet als best-effort Vorstufe im Import-Flow.
     */
    suspend fun recognizeOrNull(bitmap: Bitmap): String? =
        runCatching { recognize(bitmap) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}
