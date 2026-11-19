package ch.nutrisnap.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCompletenessTest {

    @Test
    fun truncatedIntroWithoutIngredientsIsIncomplete() {
        val intro = "Cheesecake zum Abnehmen? Ja, bitte! Stell dir vor, Zimtschnecke und " +
            "Cheesecake bekommen ein Baby. Das Ergebnis? Dieser unglaublich cremige " +
            "High Protein Cinnamon Swirl Cheesecake!"
        assertFalse(CaptionCompleteness.hasIngredientContent(intro))
    }

    @Test
    fun fullCaptionWithQuantitiesIsComplete() {
        val full = """
            Cheesecake zum Abnehmen? Ja, bitte!

            ZUTATEN:
            Für die Cheesecake-Masse:
            200g Frischkäse 0,2% Fett
            200g Magertopfen
            1 Ei (M)
            37g Vanillepuddingpulver
            40g Erythrit
            2 Scoops Vanille Flavour

            ZUBEREITUNG:
            Alle Zutaten in einer Schüssel gut vermischen.
        """.trimIndent()
        assertTrue(CaptionCompleteness.hasIngredientContent(full))
    }

    @Test
    fun headerWithListWithoutUnitsIsComplete() {
        val caption = """
            Ingredients:
            3 eggs
            1 banana
            2 apples
            cinnamon
        """.trimIndent()
        assertTrue(CaptionCompleteness.hasIngredientContent(caption))
    }

    @Test
    fun headerAloneWithoutListIsIncomplete() {
        assertFalse(CaptionCompleteness.hasIngredientContent("Zutaten findest du im Kommentar 👇"))
    }

    @Test
    fun nullAndBlankAreIncomplete() {
        assertFalse(CaptionCompleteness.hasIngredientContent(null))
        assertFalse(CaptionCompleteness.hasIngredientContent("   "))
    }
}
