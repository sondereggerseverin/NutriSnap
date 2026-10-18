package ch.nutrisnap.app.domain

/**
 * Prüft gescannte Lebensmittel-Barcodes (EAN-13, EAN-8, UPC-A, UPC-E) auf Länge und Prüfziffer.
 *
 * Hintergrund: ML Kit liefert bei gewölbten Verpackungen (z. B. Flaschen) gelegentlich Fehllesungen
 * wie 6-stellige Zahlen aus anderen Codetypen. Eine gültige Prüfziffer filtert solche Treffer aus.
 */
object BarcodeValidator {

    /** true, wenn [code] nur aus Ziffern besteht, eine gültige Länge hat und die Prüfziffer stimmt. */
    fun isValidFoodBarcode(code: String): Boolean {
        if (code.isEmpty() || !code.all { it in '0'..'9' }) return false
        return when (code.length) {
            13, 12, 8 -> hasValidCheckDigit(code)   // EAN-13, UPC-A, EAN-8 (GS1-Prüfziffer)
            else -> false
        }
    }

    /**
     * GS1-Prüfziffer: Ziffern von rechts (ohne Prüfziffer) abwechselnd mit 3 und 1 gewichten.
     * Gilt für EAN-13, UPC-A (12) und EAN-8 identisch.
     */
    internal fun hasValidCheckDigit(code: String): Boolean {
        val digits = code.map { it - '0' }
        val check = digits.last()
        var sum = 0
        digits.dropLast(1).asReversed().forEachIndexed { i, d ->
            sum += if (i % 2 == 0) d * 3 else d
        }
        return (10 - sum % 10) % 10 == check
    }
}

/**
 * Lässt einen Barcode erst durch, wenn er in [required] aufeinanderfolgenden Frames
 * identisch erkannt wurde. Einzelne Fehllesungen (Unschärfe, Wölbung) fallen so durch.
 */
class BarcodeStabilityGate(private val required: Int = 2) {
    private var last: String? = null
    private var count = 0

    /** Gibt [code] zurück, sobald er stabil erkannt wurde, sonst null. */
    fun offer(code: String): String? {
        if (code == last) count++ else { last = code; count = 1 }
        return if (count >= required) code else null
    }

    fun reset() { last = null; count = 0 }
}
