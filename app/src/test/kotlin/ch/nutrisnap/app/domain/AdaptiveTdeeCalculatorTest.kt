package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AdaptiveTdeeCalculatorTest {

    @Test
    fun `mergeWeightByDate prefers health connect on same day`() {
        val day = LocalDate.of(2026, 1, 10)
        val merged = AdaptiveTdeeCalculator.mergeWeightByDate(
            manualByDate = mapOf(day to 80f),
            healthConnectByDate = mapOf(day to 79.5f)
        )
        assertEquals(79.5f, merged[day])
    }

    @Test
    fun `ewmaSeries smooths step change`() {
        val series = AdaptiveTdeeCalculator.ewmaSeries(listOf(80.0, 82.0, 82.0), alpha = 0.5)
        assertEquals(3, series.size)
        assertEquals(80.0, series[0], 0.001)
        // prev + 0.5*(82-prev) = 81
        assertEquals(81.0, series[1], 0.001)
        assertTrue(series[2] > series[1])
    }

    @Test
    fun `computeTrendTdee returns null with too few days`() {
        val start = LocalDate.of(2026, 1, 1)
        val weights = (0..2).associate { start.plusDays(it.toLong()) to 80f }
        val intake = weights.mapValues { 2500f }
        assertNull(AdaptiveTdeeCalculator.computeTrendTdee(weights, intake))
    }

    @Test
    fun `computeTrendTdee estimates maintenance from weight and intake`() {
        val start = LocalDate.of(2026, 1, 1)
        // 10 days stable weight, steady intake 2500 → TDEE ≈ 2500
        val weights = (0..9).associate { start.plusDays(it.toLong()) to 80f }
        val intake = weights.mapValues { 2500f }
        val trend = AdaptiveTdeeCalculator.computeTrendTdee(weights, intake)
        assertNotNull(trend)
        assertEquals(10, trend!!.overlapDays)
        assertTrue(trend.tdee in 2400.0..2600.0)
    }

    @Test
    fun `computeDailyTarget applies safety floor`() {
        val target = AdaptiveTdeeCalculator.computeDailyTarget(
            formulaBmr = 1500.0,
            formulaTdee = 2000.0,
            trend = null,
            deficitKcal = 2000, // aggressive — would go below floor without clamp
            todayActiveKcal = 0.0,
            avgActiveKcal = 0.0
        )
        assertNotNull(target)
        assertTrue(target!!.targetKcal >= AdaptiveTdeeCalculator.SAFETY_FLOOR_KCAL.toInt())
    }
}
