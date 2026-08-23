package ch.nutrisnap.app.utils

/**
 * Normalisiert Barcodes für Speichern und Lookup.
 * ML Kit liefert manchmal Leerzeichen; EAN-8/13/UPC sollen nur Ziffern enthalten.
 */
object BarcodeUtils {
    /** Nur Ziffern behalten (EAN/UPC). Leerer String wenn nichts Brauchbares. */
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val digits = raw.filter { it.isDigit() }
        return digits
    }

    fun isValid(raw: String?): Boolean {
        val n = normalize(raw)
        return n.length in 8..14
    }
}
