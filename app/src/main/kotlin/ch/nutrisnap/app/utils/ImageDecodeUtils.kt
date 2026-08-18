package ch.nutrisnap.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sicheres Laden von Galerie-Fotos: volle Kamera-Auflösung (12+ MP) würde sonst
 * oft OOM auslösen → App hängt und stürzt ab.
 */
object ImageDecodeUtils {

    private const val DEFAULT_MAX_EDGE = 2048

    fun decodeUri(
        context: Context,
        uri: Uri,
        maxEdgePx: Int = DEFAULT_MAX_EDGE,
        /** RGB_565 halbiert Speicher/Bandbreite – gut für Crop-Preview. */
        preferRgb565: Boolean = false
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        val longest = max(w, h)
        while (longest / sample > maxEdgePx) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (preferRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val edge = max(bitmap.width, bitmap.height)
        if (edge <= maxEdgePx) return bitmap
        val scale = maxEdgePx.toFloat() / edge
        val tw = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val th = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        if (scaled !== bitmap) bitmap.recycle()
        scaled
    }.getOrNull()
}
