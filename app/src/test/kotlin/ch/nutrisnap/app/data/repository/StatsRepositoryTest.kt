package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.DailySummary
import ch.nutrisnap.app.data.db.DiaryDao
import ch.nutrisnap.app.data.model.DiaryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Minimal in-memory fake of DiaryDao so StatsRepository can be unit tested
 * without spinning up Room / an instrumented test.
 */
private class FakeDiaryDao(private val datesWithEntries: Set<String>) : DiaryDao {
    override fun getEntriesForDate(dateStr: String): Flow<List<DiaryEntry>> = flowOf(emptyList())
    override fun getWeeklySummary(fromDate: String): Flow<List<DailySummary>> = flowOf(emptyList())
    override suspend fun insert(entry: DiaryEntry): Long = 0L
    override suspend fun update(entry: DiaryEntry) {}
    override suspend fun delete(entry: DiaryEntry) {}
    override suspend fun totalCaloriesForDate(dateStr: String): Float? = null
    override suspend fun hasEntriesForDate(dateStr: String): Boolean = dateStr in datesWithEntries
    override suspend fun getAllOnce(): List<DiaryEntry> = emptyList()
    override suspend fun deleteAll() {}
}

class StatsRepositoryTest {

    @Test
    fun `streak is zero when no entries exist at all`() = runTest {
        val repo = StatsRepository(FakeDiaryDao(emptySet()))
        assertEquals(0, repo.calculateStreak())
    }

    @Test
    fun `streak counts consecutive days ending today`() = runTest {
        val today = LocalDate.now()
        val dates = setOf(
            today.toString(),
            today.minusDays(1).toString(),
            today.minusDays(2).toString()
        )
        val repo = StatsRepository(FakeDiaryDao(dates))
        assertEquals(3, repo.calculateStreak())
    }

    @Test
    fun `streak still counts back from yesterday when today has no entry yet`() = runTest {
        val today = LocalDate.now()
        val dates = setOf(
            today.minusDays(1).toString(),
            today.minusDays(2).toString()
        )
        val repo = StatsRepository(FakeDiaryDao(dates))
        assertEquals(2, repo.calculateStreak())
    }

    @Test
    fun `streak breaks on first gap`() = runTest {
        val today = LocalDate.now()
        val dates = setOf(
            today.toString(),
            // gap at today-1
            today.minusDays(2).toString()
        )
        val repo = StatsRepository(FakeDiaryDao(dates))
        assertEquals(1, repo.calculateStreak())
    }
}
