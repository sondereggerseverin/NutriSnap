package ch.nutrisnap.app.domain

import ch.nutrisnap.app.domain.ShoppingAisle.Aisle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingAisleTest {
    private fun a(name: String) = ShoppingAisle.classify(name)

    @Test fun gemueseUndObst() {
        assertEquals(Aisle.PRODUCE, a("Rote Zwiebeln"))
        assertEquals(Aisle.PRODUCE, a("Tomaten"))
        assertEquals(Aisle.PRODUCE, a("Wassermelone"))
        assertEquals(Aisle.PRODUCE, a("Knoblauchzehe"))
    }

    @Test fun spezifischVorAllgemein() {
        assertEquals(Aisle.SPICES, a("Paprikapulver"))
        assertEquals(Aisle.PRODUCE, a("Paprika"))
        assertEquals(Aisle.DRY, a("Kokosmilch"))
        assertEquals(Aisle.DAIRY, a("Vollmilch"))
        assertEquals(Aisle.DRY, a("Erdnussbutter"))
        assertEquals(Aisle.DRY, a("Tomatenmark"))
    }

    @Test fun weitereGaenge() {
        assertEquals(Aisle.DAIRY, a("Eier"))
        assertEquals(Aisle.DAIRY, a("Frischkäse"))
        assertEquals(Aisle.MEAT_FISH, a("Hackfleisch"))
        assertEquals(Aisle.MEAT_FISH, a("Lachsfilet"))
        assertEquals(Aisle.BREAD, a("Toastbrot"))
        assertEquals(Aisle.DRY, a("Spaghetti"))
        assertEquals(Aisle.BAKING, a("Olivenöl"))
        assertEquals(Aisle.BAKING, a("Zucker (braun)"))
        assertEquals(Aisle.SPICES, a("Gemüsebrühe"))
        assertEquals(Aisle.BEVERAGES, a("Mineralwasser"))
        assertEquals(Aisle.FROZEN, a("Tiefkühlerbsen"))
    }

    @Test fun unbekanntUndLeer() {
        assertEquals(Aisle.OTHER, a(""))
        assertEquals(Aisle.OTHER, a("Xyz"))
    }

    @Test fun teilenTextNachGangSortiert() {
        val text = ShoppingListText.build(
            listOf(
                ShoppingListText.Line("Mehl", "500 g"),
                ShoppingListText.Line("Zwiebel", "2 Stk"),
                ShoppingListText.Line("Milch")
            )
        )
        assertTrue(text.startsWith("Einkaufsliste"))
        assertTrue(text.indexOf("Obst & Gemüse") < text.indexOf("Milch, Käse & Eier"))
        assertTrue(text.indexOf("Milch, Käse & Eier") < text.indexOf("Backen & Öle"))
        assertTrue(text.contains("• Zwiebel – 2 Stk"))
        assertTrue(text.contains("• Milch\n") || text.contains("• Milch"))
        assertEquals("", ShoppingListText.build(emptyList()))
    }
}
