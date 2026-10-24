package ch.nutrisnap.app.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoodEmojiTest {

    @Test
    fun `typische Tagebuch-Eintraege bekommen passendes Emoji`() {
        assertEquals("🍝", FoodEmoji.forName("Nudeln, gekocht (aus Hartweizengriess)"))
        assertEquals("🍗", FoodEmoji.forName("Putenschnitzel (Truthahnschnitzel)"))
        assertEquals("🍰", FoodEmoji.forName("Karottenkuchen"))
        assertEquals("🥜", FoodEmoji.forName("Kerne, Dattel & Mandel (Farmer)"))
        assertEquals("🍛", FoodEmoji.forName("One-Pot-Kürbis-Curry mit Quinoa"))
        assertEquals("🥣", FoodEmoji.forName("Griechischer Joghurt mit Beeren"))
    }

    @Test
    fun `Reihenfolge - spezifisch vor allgemein`() {
        assertEquals("🥩", FoodEmoji.forName("Fleischkäse"))
        assertEquals("🍞", FoodEmoji.forName("Nussbrot"))
    }

    @Test
    fun `Wortanfang - Reis und Ei ja, Eiweissmehl nein`() {
        assertEquals("🍚", FoodEmoji.forName("Reis, gekocht"))
        assertEquals("🍚", FoodEmoji.forName("Vollkorn Reis"))
        assertEquals("🍓", FoodEmoji.forName("Preiselbeeren"))
        assertNull(FoodEmoji.forName("Eiweissmehl"))
        assertEquals("🥚", FoodEmoji.forName("Ei, gekocht"))
    }

    @Test
    fun `unbekannt oder leer ergibt null`() {
        assertNull(FoodEmoji.forName(""))
        assertNull(FoodEmoji.forName("   "))
        assertNull(FoodEmoji.forName("Xylit"))
    }
}
