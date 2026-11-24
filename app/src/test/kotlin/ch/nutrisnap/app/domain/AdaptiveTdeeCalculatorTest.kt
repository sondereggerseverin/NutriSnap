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
            deficitKcal = 2000.0, // aggressive — would go below floor without clamp
            todayActiveKcal = 0.0,
            avgActiveKcal = 0.0
        )
        assertNotNull(target)
        assertTrue(target!!.targetKcal >= AdaptiveTdeeCalculator.SAFETY_FLOOR_KCAL.toInt())
    }

    @Test
    fun `hard day reduces deficit and applies higher activity factor`() {
        // Normaler Tag: Ø 400 aktiv, heute 400 → kein Bonus, volles Defizit
        val normal = AdaptiveTdeeCalculator.computeDailyTarget(
            formulaBmr = 1800.0,
            formulaTdee = 2800.0,
            trend = null,
            deficitKcal = 500.0,
            todayActiveKcal = 400.0,
            avgActiveKcal = 400.0
        )
        assertNotNull(normal)
        assertEquals(500, normal!!.deficitKcal)
        assertEquals(0, normal.activityBonusKcal)
        // 2800 - 500 = 2300
        assertEquals(2300, normal.targetKcal)

        // Harter Tag: heute 2400, Ø 400 → Excess 2000 ≥ 800
        // Defizit 500 × 0.25 = 125, Bonus 2000 × 0.75 = 1500
        // Ziel ≈ 2800 - 125 + 1500 = 4175
        val hard = AdaptiveTdeeCalculator.computeDailyTarget(
            formulaBmr = 1800.0,
            formulaTdee = 2800.0,
            trend = null,
            deficitKcal = 500.0,
            todayActiveKcal = 2400.0,
            avgActiveKcal = 400.0
        )
        assertNotNull(hard)
        assertEquals(125, hard!!.deficitKcal)
        assertEquals(1500, hard.activityBonusKcal)
        assertEquals(4175, hard.targetKcal)
        assertTrue(hard.targetKcal > normal.targetKcal + 1500)
    }

    @Test
    fun `floor raises target and exposes raw target`() {
        val t = AdaptiveTdeeCalculator.computeDailyTarget(
            formulaBmr = 1500.0, formulaTdee = 2000.0, trend = null,
            todayActiveKcal = 0.0, avgActiveKcal = 0.0
        )!!
        assertEquals(2000, t.targetKcal)
        assertTrue(t.rawTargetKcal < t.targetKcal)
    }

    @Test
    fun `trend below BMR x1_2 is ignored in favour of formula`() {
        val low = TrendTdeeResult(tdee = 2100.0, overlapDays = 10, spanDays = 10)
        val t = AdaptiveTdeeCalculator.computeDailyTarget(
            formulaBmr = 1915.0, formulaTdee = 2968.0, trend = low,
            todayActiveKcal = null, avgActiveKcal = null
        )!!
        assertTrue(!t.isTrendBased)
        assertEquals(2968, t.maintenanceKcal)
    }

    @Test
    fun `days below minimum intake are treated as not logged`() {
        val start = LocalDate.of(2026, 1, 1)
        val weights = (0..9).associate { start.plusDays(it.toLong()) to 80f }
        // jeder zweite Tag vergessen (300 kcal), echte Zufuhr 2800
        val intake = (0..9).associate { start.plusDays(it.toLong()) to if (it % 2 == 0) 2800f else 300f }
        val trend = AdaptiveTdeeCalculator.computeTrendTdee(weights, intake)
        assertNotNull(trend)
        assertTrue(trend!!.tdee in 2700.0..2900.0)
    }
}
