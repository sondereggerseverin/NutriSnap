package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.repository.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FudCalorieCalculatorTest {

    @Test
    fun `Mifflin Mann 80kg 180cm 30J`() {
        // 10*80 + 6.25*180 - 5*30 - 161 + 166 = 1780
        assertEquals(1780.0, FudCalorieCalculator.bmrMifflin(80f, 180, 30, Sex.MALE), 0.001)
    }

    @Test
    fun `Mifflin Frau 65kg 165cm 30J`() {
        assertEquals(1370.25, FudCalorieCalculator.bmrMifflin(65f, 165, 30, Sex.FEMALE), 0.001)
    }

    @Test
    fun `Mifflin unspezifiziert liegt genau zwischen Mann und Frau`() {
        val m = FudCalorieCalculator.bmrMifflin(70f, 170, 40, Sex.MALE)
        val f = FudCalorieCalculator.bmrMifflin(70f, 170, 40, Sex.FEMALE)
        val u = FudCalorieCalculator.bmrMifflin(70f, 170, 40, Sex.UNSPECIFIED)
        assertEquals((m + f) / 2.0, u, 0.001)
    }

    @Test
    fun `Katch-McArdle 80kg 20 Prozent Koerperfett`() {
        // fettfreie Masse 64 kg -> 370 + 21.6 * 64
        assertEquals(1752.4, FudCalorieCalculator.bmrKatch(80f, 20f), 0.001)
    }

    @Test
    fun `Katch-McArdle begrenzt Koerperfett auf 60 Prozent`() {
        assertEquals(
            FudCalorieCalculator.bmrKatch(80f, 60f),
            FudCalorieCalculator.bmrKatch(80f, 95f),
            0.001
        )
    }

    @Test
    fun `ungueltige Eingaben liefern Standardziele`() {
        val g = FudCalorieCalculator.compute(0f, 180, 30, Sex.MALE, 1.55f)
        assertEquals(2000, g.calories)
        assertEquals(1500, g.bmr)
        assertFalse(g.usedKatchMcArdle)
    }

    @Test
    fun `Koerperfett setzt Katch-McArdle, null oder 0 nutzt Mifflin`() {
        val withBf = FudCalorieCalculator.compute(80f, 180, 30, Sex.MALE, 1.55f, bodyFatPercent = 20f)
        val zeroBf = FudCalorieCalculator.compute(80f, 180, 30, Sex.MALE, 1.55f, bodyFatPercent = 0f)
        val noBf = FudCalorieCalculator.compute(80f, 180, 30, Sex.MALE, 1.55f, bodyFatPercent = null)
        assertTrue(withBf.usedKatchMcArdle)
        assertEquals(1752, withBf.bmr)
        assertFalse(zeroBf.usedKatchMcArdle)
        assertFalse(noBf.usedKatchMcArdle)
        assertEquals(1780, noBf.bmr)
    }

    @Test
    fun `Erhalt entspricht TDEE und Makros folgen Aktivitaetsstufe`() {
        val g = FudCalorieCalculator.compute(80f, 180, 30, Sex.MALE, 1.55f)
        assertEquals(2759, g.tdee)
        assertEquals(2759, g.calories)
        assertEquals(144f, g.proteinG, 0.01f) // 1.8 g/kg
        assertEquals(48f, g.fatG, 0.01f)      // 0.6 g/kg
    }

    @Test
    fun `Defizit senkt Kalorien um 550 pro Tag bei minus 0,5 kg Woche und erhoeht Protein`() {
        val g = FudCalorieCalculator.compute(80f, 180, 30, Sex.MALE, 1.55f, weeklyChangeKg = -0.5f)
        assertEquals(2209, g.calories)
        assertEquals(160f, g.proteinG, 0.05f) // 2.0 g/kg (1.8 + 0.2)
    }

    @Test
    fun `Kalorien werden nach unten auf 800 begrenzt`() {
        val g = FudCalorieCalculator.compute(80f, 180, 30, Sex.MALE, 1.55f, weeklyChangeKg = -3f)
        assertEquals(800, g.calories)
    }

    @Test
    fun `naechste Aktivitaetsstufe wird gewaehlt`() {
        assertEquals("Mässig aktiv", FudCalorieCalculator.nearestActivity(1.5f).labelDe)
        assertEquals("Sitzend", FudCalorieCalculator.nearestActivity(1.0f).labelDe)
        assertEquals("Extrem aktiv", FudCalorieCalculator.nearestActivity(3.0f).labelDe)
    }
}
