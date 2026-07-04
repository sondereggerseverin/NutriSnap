package ch.nutrisnap.app.data.repository

import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.db.toDomain
import ch.nutrisnap.app.data.db.toEntity
import ch.nutrisnap.app.data.supabase.SupabaseSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Biologisches Geschlecht für die BMR-Berechnung (Mifflin-St-Jeor braucht den Unterschied). */
enum class Sex { MALE, FEMALE, UNSPECIFIED }

data class UserProfile(
    val dailyCalorieGoal: Int   = 2000,
    val proteinGoalG:     Float = 120f,
    val carbsGoalG:       Float = 220f,
    val fatGoalG:         Float = 65f,
    val weightKg:         Float = 0f,
    val heightCm:         Int   = 0,
    val ageYears:         Int   = 0,
    val activityFactor:   Float = 1.55f,
    val sex:              Sex   = Sex.UNSPECIFIED
) {
    /**
     * Mifflin-St-Jeor. Der Geschlechts-Term macht bis zu ~166 kcal Unterschied
     * (+5 Männer, -161 Frauen) - ohne Angabe nehmen wir den Mittelwert (-78) als
     * beste neutrale Schätzung, statt ihn ganz wegzulassen.
     */
    fun computedBmr(): Double? {
        if (weightKg <= 0f || heightCm <= 0 || ageYears <= 0) return null
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        return when (sex) {
            Sex.MALE        -> base + 5.0
            Sex.FEMALE      -> base - 161.0
            Sex.UNSPECIFIED -> base - 78.0
        }
    }

    fun computedTdee(): Double? = computedBmr()?.let { it * activityFactor }
}

class UserProfileRepository(private val db: NutriDatabase) {
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun pushSafely(block: suspend () -> Unit) {
        syncScope.launch { runCatching { block() } }
    }

    /**
     * Live-reactive: emits again whenever the profile row changes (e.g. after Settings
     * saves a new calorie/macro goal), so every screen combining this Flow (Home, Diary,
     * Analysis, Stats, ...) updates without needing to be recreated. Previously this
     * read once via a suspend query wrapped in `flow {}`, so edits never propagated to
     * already-open screens until the app/ViewModel was restarted.
     */
    fun get(): Flow<UserProfile> =
        db.userProfileDao().observe().map { it?.toDomain() ?: UserProfile() }.flowOn(Dispatchers.IO)

    suspend fun update(profile: UserProfile) = withContext(Dispatchers.IO) {
        db.userProfileDao().upsert(profile.toEntity())
        pushSafely {
            SupabaseSync.upsertUserProfile(
                weightKg = profile.weightKg, heightCm = profile.heightCm, ageYears = profile.ageYears,
                dailyCalorieGoal = profile.dailyCalorieGoal, proteinGoalG = profile.proteinGoalG,
                carbsGoalG = profile.carbsGoalG, fatGoalG = profile.fatGoalG,
                activityFactor = profile.activityFactor, sex = profile.sex.name
            )
        }
    }
}
