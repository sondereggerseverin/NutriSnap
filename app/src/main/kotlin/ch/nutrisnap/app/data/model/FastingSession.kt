package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "fasting_sessions")
data class FastingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime? = null,
    val goalHours: Int = 16,
    val isCompleted: Boolean = false
)

enum class FastingType(val hours: Int, val label: String) {
    TWELVE_TWELVE(12, "12:12"),
    SIXTEEN_EIGHT(16, "16:8"),
    EIGHTEEN_SIX(18, "18:6"),
    TWENTY_FOUR(24, "24h OMAD")
}
