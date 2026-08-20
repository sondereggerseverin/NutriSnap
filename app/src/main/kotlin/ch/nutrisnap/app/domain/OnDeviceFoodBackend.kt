package ch.nutrisnap.app.domain

import android.graphics.Bitmap

/**
 * Phase C6 – austauschbares On-Device-Backend (Skelett).
 *
 * Heute: [MlKitFoodBackend] (leicht, in der APK).
 * Später optional: Gemma/TFLite per Download, ohne die Cloud-Pipeline zu ändern.
 */
interface OnDeviceFoodBackend {
    /** Kurzer Anzeigename für Logs/UI (z.B. "ML Kit"). */
    val displayName: String

    /**
     * true, wenn dieses Backend auf dem Gerät sofort nutzbar ist
     * (Modell vorhanden / keine Extra-Downloads nötig).
     */
    fun isReady(): Boolean

    suspend fun analyze(bitmap: Bitmap): Result<DishScanResult>
}

/**
 * Standard-Backend: ML Kit Image Labeling ([OnDeviceFoodLabeler]).
 */
object MlKitFoodBackend : OnDeviceFoodBackend {
    override val displayName: String = "ML Kit"

    override fun isReady(): Boolean = true

    override suspend fun analyze(bitmap: Bitmap): Result<DishScanResult> =
        OnDeviceFoodLabeler.analyze(bitmap)
}

/**
 * Platzhalter für künftiges Download-on-Demand-Modell (Gemma/TFLite).
 * [isReady] bleibt false, bis ein Modell installiert und verdrahtet ist.
 */
object FutureLlmFoodBackend : OnDeviceFoodBackend {
    override val displayName: String = "On-Device LLM (noch nicht aktiv)"

    override fun isReady(): Boolean = false

    override suspend fun analyze(bitmap: Bitmap): Result<DishScanResult> =
        Result.failure(IllegalStateException("On-Device-LLM ist noch nicht installiert"))
}

/**
 * Wählt das beste bereite Backend (aktuell immer ML Kit).
 */
object OnDeviceFoodBackendRegistry {
    private val backends: List<OnDeviceFoodBackend> = listOf(
        // FutureLlmFoodBackend zuerst, sobald isReady() true wird
        FutureLlmFoodBackend,
        MlKitFoodBackend
    )

    fun active(): OnDeviceFoodBackend =
        backends.firstOrNull { it.isReady() } ?: MlKitFoodBackend
}
