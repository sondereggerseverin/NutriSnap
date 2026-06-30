package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.DiaryDao
import ch.nutrisnap.app.data.db.NutriDatabase
import java.time.LocalDate

class StatsRepository(private val diaryDao: DiaryDao) {

    constructor(db: NutriDatabase) : this(db.diaryDao())

    /**
     * Number of consecutive days (ending today) with at least one diary entry.
     * If today has no entries yet, the streak still counts back from yesterday
     * (so logging tomorrow keeps the streak alive until the day is over).
     */
    suspend fun calculateStreak(): Int {
        var streak = 0
        var date   = LocalDate.now()
        val today  = LocalDate.now()

        while (true) {
            val hasEntries = diaryDao.hasEntriesForDate(date.toString())
            if (hasEntries) {
                streak++
            } else if (date != today) {
                break
            }
            date = date.minusDays(1)
            if (streak > 3650) break // safety net (~10 years)
        }
        return streak
    }
}
