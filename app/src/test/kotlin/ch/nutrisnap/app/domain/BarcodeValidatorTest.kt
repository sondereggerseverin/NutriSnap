package ch.nutrisnap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeValidatorTest {

    @Test
    fun `gueltige EAN-13 wird akzeptiert`() {
        assertTrue(BarcodeValidator.isValidFoodBarcode("7610900217816")) // Emmi High Protein
        assertTrue(BarcodeValidator.isValidFoodBarcode("4006381333931")) // Stabilo
    }

    @Test
    fun `gueltige EAN-8 und UPC-A werden akzeptiert`() {
        assertTrue(BarcodeValidator.isValidFoodBarcode("96385074"))
        assertTrue(BarcodeValidator.isValidFoodBarcode("036000291452"))
    }

    @Test
    fun `falsche Pruefziffer wird abgelehnt`() {
        assertFalse(BarcodeValidator.isValidFoodBarcode("7610900217815"))
        assertFalse(BarcodeValidator.isValidFoodBarcode("96385075"))
    }

    @Test
    fun `fehllesung mit sechs Ziffern wird abgelehnt`() {
        assertFalse(BarcodeValidator.isValidFoodBarcode("104812"))
    }

    @Test
    fun `nicht-numerische und leere Werte werden abgelehnt`() {
        assertFalse(BarcodeValidator.isValidFoodBarcode(""))
        assertFalse(BarcodeValidator.isValidFoodBarcode("76109002178A6"))
    }

    @Test
    fun `stabilitaetsfilter braucht zwei identische frames`() {
        val gate = BarcodeStabilityGate(required = 2)
        assertTrue(gate.offer("7610900217816") == null)
        assertTrue(gate.offer("7610900217816") == "7610900217816")
    }

    @Test
    fun `stabilitaetsfilter verwirft abweichende frames`() {
        val gate = BarcodeStabilityGate(required = 2)
        assertTrue(gate.offer("7610900217816") == null)
        assertTrue(gate.offer("4006381333931") == null)   // anderer Wert -> Zähler startet neu
        assertTrue(gate.offer("7610900217816") == null)
    }
}
