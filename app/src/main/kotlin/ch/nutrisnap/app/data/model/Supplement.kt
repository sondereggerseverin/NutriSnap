package ch.nutrisnap.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SupplementCategory {
    OMEGA_3, VITAMIN_D, VITAMIN_E, VITAMIN_C, VITAMIN_B, MULTIVITAMIN,
    MAGNESIUM, EISEN, ZINK, DIAET, SPORT, GELENKE, LEBER, SONSTIGES
}

enum class SupplementTiming { MORGENS, ABENDS, VOR_TRAINING, ZUR_MAHLZEIT, WOECHENTLICH, NACH_BEDARF }

enum class SupplementStatus { AKTIV, LEER, ABGELAUFEN, NICHT_ZUTREFFEND }

/** Pro Gruppe nur eine Quelle pro Tag empfehlen. */
enum class SupplementConflictGroup {
    MAGNESIUM_QUELLE, MULTIVITAMIN_QUELLE, VITAMIN_D_QUELLE,
    ZINK_SELEN_EISEN_QUELLE, KOFFEIN_QUELLE, OMEGA_3_QUELLE, NONE
}

@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val brand: String = "",
    val category: SupplementCategory = SupplementCategory.SONSTIGES,
    val activeIngredients: String = "",
    val servingSize: String = "",
    val effects: String = "",
    val pros: String = "",
    val cons: String = "",
    val dosageRecommendation: String = "",
    val warnings: String = "",
    val expiryDateStr: String? = null,
    val status: SupplementStatus = SupplementStatus.AKTIV,
    val conflictGroup: SupplementConflictGroup = SupplementConflictGroup.NONE,
    val preferredTiming: SupplementTiming = SupplementTiming.MORGENS,
    val requiresMedicalConfirmation: Boolean = false,
    val isSeedData: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
