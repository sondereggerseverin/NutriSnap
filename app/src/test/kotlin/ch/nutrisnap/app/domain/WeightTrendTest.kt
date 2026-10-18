package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class WeightTrendTest {

    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun `gleichmaessige Abnahme ergibt Wochenrate`() {
        // 0.1 kg pro Tag über 14 Tage -> -0.7 kg/Woche
        val data = (0..14).associate { i -> today.minusDays((14 - i).toLong()) to (90f - 0.1f * i) }

        assertEquals(-0.7f, WeightTrend.weeklyRateKg(data, today)!!, 0.01f)
    }

    @Test
    fun `stabiles Gewicht ergibt rund null`() {
        val data = (0..14 step 2).associate { i -> today.minusDays(i.toLong()) to 87.5f }

        assertEquals(0f, WeightTrend.weeklyRateKg(data, today)!!, 0.001f)
    }

    @Test
    fun `zu wenige Messungen ergeben keinen Trend`() {
        val data = mapOf(today to 87.5f, today.minusDays(10) to 88.0f)

        assertNull(WeightTrend.weeklyRateKg(data, today))
    }

    @Test
    fun `zu kurzer Zeitraum ergibt keinen Trend`() {
        val data = mapOf(today to 87.5f, today.minusDays(1) to 87.6f, today.minusDays(2) to 87.4f)

        assertNull(WeightTrend.weeklyRateKg(data, today))
    }

    @Test
    fun `messungen ausserhalb des Fensters werden ignoriert`() {
        val data = mapOf(
            today.minusDays(60) to 100f,
            today.minusDays(14) to 88f,
            today.minusDays(7) to 87.5f,
            today to 87f
        )

        // Nur die letzten 3 Messungen zählen: -1 kg in 14 Tagen = -0.5 kg/Woche
        assertEquals(-0.5f, WeightTrend.weeklyRateKg(data, today)!!, 0.01f)
        assertNotNull(WeightTrend.weeklyRateKg(data, today, windowDays = 21))
    }

    @Test
    fun `formatierung`() {
        assertEquals("±0/Wo", WeightTrend.formatWeeklyRate(0.02f))
        assertEquals("-0.4/Wo", WeightTrend.formatWeeklyRate(-0.4f).replace(',', '.'))
        assertEquals("+0.3/Wo", WeightTrend.formatWeeklyRate(0.3f).replace(',', '.'))
    }
}
