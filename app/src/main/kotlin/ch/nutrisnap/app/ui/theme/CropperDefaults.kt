package ch.nutrisnap.app.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView

/**
 * Einheitliche, immer gut sichtbare Cropper-Optionen.
 * Dunkle Theme-Primärfarben (z. B. Schwarz/Grau) würden die Toolbar
 * sonst unsichtbar machen – daher Luminanz-Check + Fallback.
 */
object CropperDefaults {

    private val FALLBACK_TOOLBAR = AndroidColor.parseColor("#2563EB") // kräftiges Blau
    private const val WHITE = AndroidColor.WHITE
    private const val BLACK = AndroidColor.BLACK

    fun toolbarColor(useTheme: Boolean, themePrimary: Color): Int {
        if (!useTheme) return AndroidColor.parseColor("#4CAF50")
        // Zu dunkle Primärfarbe → fester, heller Toolbar-Ton
        if (themePrimary.luminance() < 0.25f) return FALLBACK_TOOLBAR
        return AndroidColor.argb(
            (themePrimary.alpha * 255).toInt().coerceIn(0, 255),
            (themePrimary.red * 255).toInt().coerceIn(0, 255),
            (themePrimary.green * 255).toInt().coerceIn(0, 255),
            (themePrimary.blue * 255).toInt().coerceIn(0, 255)
        )
    }

    fun options(
        title: String,
        useTheme: Boolean,
        themePrimary: Color
    ): CropImageOptions {
        val bar = toolbarColor(useTheme, themePrimary)
        return CropImageOptions(
            guidelines = CropImageView.Guidelines.ON,
            outputCompressFormat = Bitmap.CompressFormat.JPEG,
            outputCompressQuality = 90,
            activityTitle = title,
            cropMenuCropButtonTitle = "Speichern",
            allowFlipping = true,
            allowRotation = true,
            fixAspectRatio = false,
            // Handles nicht am Bildrand → kein Konflikt mit Notification-Shade
            initialCropWindowPaddingRatio = 0.08f,
            multiTouchEnabled = true,
            toolbarColor = bar,
            activityBackgroundColor = BLACK,
            toolbarTitleColor = WHITE,
            toolbarBackButtonColor = WHITE,
            borderLineColor = WHITE,
            borderCornerColor = WHITE,
            guidelinesColor = AndroidColor.argb(180, 255, 255, 255)
        )
    }
}
