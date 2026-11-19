package ch.nutrisnap.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ActiveCalorieEstimatorTest {

    @Test
    fun `10000 Schritte bei 179 cm und 87 kg ergeben rund 323 kcal netto`() {
        // 10'000 × 0,415 × 1,79 m = 7,43 km; × 0,5 kcal/kg/km × 87 kg
        assertEquals(323.1, ActiveCalorieEstimator.walkingKcal(10_000, 179, 87f), 0.5)
    }

    @Test
    fun `keine Schritte ergeben 0 kcal`() {
        assertEquals(0.0, ActiveCalorieEstimator.walkingKcal(0, 179, 87f), 0.0)
    }

    @Test
    fun `Laufen 30 min bei 8,3 MET und 87 kg ergibt netto rund 318 kcal`() {
        assertEquals(317.6, ActiveCalorieEstimator.sessionKcal(8.3, 87f, 30.0), 0.5)
    }

    @Test
    fun `MET unter 1 ergibt nie negative kcal`() {
        assertEquals(0.0, ActiveCalorieEstimator.sessionKcal(0.9, 87f, 60.0), 0.0)
    }

    @Test
    fun `ungueltige Koerpergroesse wirft`() {
        assertThrows(IllegalArgumentException::class.java) {
            ActiveCalorieEstimator.walkingKcal(1000, 0, 87f)
        }
    }
}
