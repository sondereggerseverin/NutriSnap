package ch.nutrisnap.app.domain

import java.time.LocalDate

/**
 * Gewichtstrend als Wochenrate (kg/Woche, negativ = Abnahme).
 *
 * Nutzt eine lineare Regression über alle Messungen im Fenster statt Differenz zweier Einzelwerte:
 * Tagesschwankungen (Wasser, Mahlzeiten) mitteln sich so heraus.
 */
object WeightTrend {

    const val DEFAULT_WINDOW_DAYS = 21
    private const val MIN_POINTS = 3
    private const val MIN_SPAN_DAYS = 6

    /**
     * @return kg pro Woche oder null, wenn zu wenig Daten vorliegen
     *         (mind. 3 Messungen, die mindestens 6 Tage auseinanderliegen).
     */
    fun weeklyRateKg(
        weightByDate: Map<LocalDate, Float>,
        today: LocalDate,
        windowDays: Int = DEFAULT_WINDOW_DAYS
    ): Float? {
        val from = today.minusDays(windowDays.toLong())
        val points = weightByDate
            .filter { (date, kg) -> !date.isBefore(from) && !date.isAfter(today) && kg > 0f }
            .map { (date, kg) -> date.toEpochDay().toDouble() to kg.toDouble() }
        if (points.size < MIN_POINTS) return null

        val span = points.maxOf { it.first } - points.minOf { it.first }
        if (span < MIN_SPAN_DAYS) return null

        val meanX = points.sumOf { it.first } / points.size
        val meanY = points.sumOf { it.second } / points.size
        val varX = points.sumOf { (it.first - meanX) * (it.first - meanX) }
        if (varX == 0.0) return null

        val slopePerDay = points.sumOf { (it.first - meanX) * (it.second - meanY) } / varX
        return (slopePerDay * 7.0).toFloat()
    }

    /** Kurztext für den Header: "−0.4/Wo", "+0.2/Wo" oder "±0/Wo" bei stabilem Gewicht. */
    fun formatWeeklyRate(rateKg: Float): String = when {
        kotlin.math.abs(rateKg) < 0.05f -> "±0/Wo"
        else -> "%+.1f/Wo".format(rateKg)
    }
}
