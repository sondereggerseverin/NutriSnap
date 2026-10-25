package ch.nutrisnap.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IngredientKeyTest {
    @Test fun klammernUndSatzzeichen() {
        assertEquals(IngredientKey.of("Flohsamenschalen"), IngredientKey.of("Flohsamenschalen (Psyllium)"))
        assertEquals(IngredientKey.of("Grana Padano"), IngredientKey.of("Grana Padano (Cucina (Aldi))"))
        assertEquals(IngredientKey.of("Lotus Biscoff Keks zerbröselt"), IngredientKey.of("Lotus Biscoff Keks, zerbröselt"))
    }

    @Test fun reihenfolgeUndFuellwoerter() {
        assertEquals(IngredientKey.of("Geriebener Käse"), IngredientKey.of("Käse gerieben"))
        assertEquals(IngredientKey.of("Geriebener Käse"), IngredientKey.of("Geraspelter Käse"))
        assertEquals(IngredientKey.of("Blumenkohl"), IngredientKey.of("Blumenkohl roh"))
        assertEquals(IngredientKey.of("Mozzarella"), IngredientKey.of("frischer Mozzarella"))
    }

    @Test fun umlauteUndGrossschreibung() {
        assertEquals(IngredientKey.of("Rüebli"), IngredientKey.of("RUEBLI"))
        assertEquals(IngredientKey.of("Crème fraîche"), IngredientKey.of("creme fraiche"))
    }

    @Test fun bedeutungstragendeWoerterBleiben() {
        assertNotEquals(IngredientKey.of("Käse"), IngredientKey.of("Light Käse"))
        assertNotEquals(IngredientKey.of("Rama Cremefine"), IngredientKey.of("Rama Cremefine 7 %"))
        assertNotEquals(IngredientKey.of("Taco-Gewürz"), IngredientKey.of("Gewürz Ketchup"))
    }
}
