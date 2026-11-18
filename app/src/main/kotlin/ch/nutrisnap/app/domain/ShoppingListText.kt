package ch.nutrisnap.app.domain

/** Baut den Teilen-/Kopier-Text der Einkaufsliste, sortiert nach Supermarkt-Gang. */
object ShoppingListText {

    data class Line(val name: String, val amountText: String? = null)

    fun build(lines: List<Line>, title: String = "Einkaufsliste"): String {
        if (lines.isEmpty()) return ""
        val grouped = lines
            .groupBy { ShoppingAisle.classify(it.name) }
            .toSortedMap(compareBy { it.ordinal })
        return buildString {
            append(title)
            grouped.forEach { (aisle, items) ->
                append("\n\n").append(aisle.label)
                items.sortedBy { it.name.lowercase() }.forEach { l ->
                    append("\n• ").append(l.name)
                    if (!l.amountText.isNullOrBlank()) append(" – ").append(l.amountText)
                }
            }
        }
    }
}
