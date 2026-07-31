package ch.nutrisnap.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** Zeichnet den Kalorien-Fortschrittsring als Bitmap. Glance kennt keine Canvas-Arcs,
 *  daher wird der Ring hier einmalig serverseitig gerendert und als
 *  [androidx.glance.ImageProvider] eingebunden. */
object RingRenderer {

    private const val STROKE_FRACTION = 0.12f // Ringdicke relativ zur Bitmap-Größe

    fun draw(progress: Float, sizePx: Int, trackColor: Int, progressColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = sizePx * STROKE_FRACTION
        val inset = stroke / 2f
        val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = trackColor
        }
        canvas.drawArc(rect, 0f, 360f, false, trackPaint)

        val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = progressColor
        }
        val sweep = progress.coerceIn(0f, 1f) * 360f
        canvas.drawArc(rect, -90f, sweep, false, progressPaint)

        return bitmap
    }
}
