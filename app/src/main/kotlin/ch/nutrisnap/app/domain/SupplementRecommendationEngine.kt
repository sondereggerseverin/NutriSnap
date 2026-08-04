package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.Supplement
import ch.nutrisnap.app.data.model.SupplementConflictGroup
import ch.nutrisnap.app.data.model.SupplementStatus
import ch.nutrisnap.app.data.model.SupplementTiming

data class SupplementRecommendation(val supplement: Supplement, val reason: String)

data class DailySupplementPlan(
    val morning: List<SupplementRecommendation>,
    val evening: List<SupplementRecommendation>,
    val onDemandHints: List<Supplement>
)

/**
 * Morgens/Abends-Plan aus aktivem Bestand.
 * Pro Konfliktgruppe nur ein Produkt pro Tag.
 */
object SupplementRecommendationEngine {

    fun plan(all: List<Supplement>): DailySupplementPlan {
        val active = all.filter { it.status == SupplementStatus.AKTIV }
        val autoEligible = active.filter { !it.requiresMedicalConfirmation }
        val onDemand = active.filter {
            it.requiresMedicalConfirmation || it.preferredTiming == SupplementTiming.NACH_BEDARF
        }

        val chosen = mutableListOf<Supplement>()
        val usedGroups = mutableSetOf<SupplementConflictGroup>()
        for (s in autoEligible.sortedBy { it.name }) {
            if (s.conflictGroup == SupplementConflictGroup.NONE) {
                chosen += s
            } else if (s.conflictGroup !in usedGroups) {
                chosen += s
                usedGroups += s.conflictGroup
            }
        }

        val morning = chosen.filter {
            it.preferredTiming == SupplementTiming.MORGENS ||
                it.preferredTiming == SupplementTiming.ZUR_MAHLZEIT ||
                it.preferredTiming == SupplementTiming.WOECHENTLICH ||
                it.preferredTiming == SupplementTiming.VOR_TRAINING
        }.map { SupplementRecommendation(it, reasonFor(it)) }

        val evening = chosen.filter {
            it.preferredTiming == SupplementTiming.ABENDS
        }.map { SupplementRecommendation(it, reasonFor(it)) }

        return DailySupplementPlan(morning = morning, evening = evening, onDemandHints = onDemand)
    }

    private fun reasonFor(s: Supplement): String = when (s.conflictGroup) {
        SupplementConflictGroup.MAGNESIUM_QUELLE -> "Magnesium-Quelle für heute"
        SupplementConflictGroup.MULTIVITAMIN_QUELLE -> "Multivitamin für heute"
        SupplementConflictGroup.VITAMIN_D_QUELLE -> "Vitamin-D-Quelle für heute"
        SupplementConflictGroup.ZINK_SELEN_EISEN_QUELLE -> "Zink/Selen/Eisen für heute"
        SupplementConflictGroup.KOFFEIN_QUELLE -> "Koffeinhaltig — nicht zusätzlich kombinieren"
        SupplementConflictGroup.OMEGA_3_QUELLE -> "Omega-3-Quelle für heute"
        SupplementConflictGroup.NONE -> "Laut Verzehrempfehlung"
    }
}
