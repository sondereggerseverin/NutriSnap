package ch.nutrisnap.app.domain

import ch.nutrisnap.app.data.model.IngredientMatch
import ch.nutrisnap.app.data.model.RecipeComponent

/**
 * Einzige Quelle der Wahrheit für Rezept-Abschnitte (Sauce / Beilage / Teig …).
 *
 * Prinzip:
 * - Der Zutaten-Text eines Rezepts ist der Master: Kopfzeile = Abschnitt, Zeilen darunter gehören dazu.
 * - Jede Zutat gehört zu genau einem Abschnitt (nie leer). Ohne Kopfzeilen gibt es den einen Abschnitt [DEFAULT_SECTION].
 * - Gruppiert wird nach Abschnittsname, nicht nach Position → doppelte Kopfzeilen sind unmöglich.
 * - Die Zuordnung IngredientMatch ↔ Textzeile läuft über genau eine Funktion ([resolve]).
 *
 * Pure Kotlin – ohne Room/Compose testbar.
 */
object IngredientSections {

    const val DEFAULT_SECTION = "Gericht"

    /** Bearbeitbares Modell: Name + Zeilen ohne Aufzählungszeichen. */
    data class EditSection(val name: String, val lines: List<String>)

    /** Eine Zutatenzeile im aufgelösten Modell; [matchIndex] zeigt in die übergebene Match-Liste. */
    data class ResolvedLine(val text: String, val matchIndex: Int?)

    data class ResolvedSection(
        val name: String,
        val lines: List<ResolvedLine>,
        val kcal: Float,
        val rawG: Float,
        val unmatchedCount: Int
    )

    // ── Kopfzeilen-Regel (identisch zur Detailansicht) ───────────────────────

    private val AMOUNT_UNIT = Regex(
        """\d+[.,]?\d*\s*(g|kg|ml|l|el|tl)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Eine Zeile ist Kopfzeile, wenn sie weder Aufzählung noch Menge noch Zahl am Anfang hat. */
    fun isHeaderLine(raw: String): Boolean {
        val d = raw.trim()
        if (d.length <= 2) return false
        if (d.startsWith("•") || d.startsWith("-") || d.startsWith("*")) return false
        if (d.first().isDigit()) return false
        if (raw.startsWith(" ")) return false
        if (AMOUNT_UNIT.containsMatchIn(d)) return false
        return true
    }

    fun stripBullet(line: String): String = line.trim().trimStart('•', '-', '*', ' ').trim()

    private fun sameName(a: String, b: String): Boolean =
        a.trim().trimEnd(':').equals(b.trim().trimEnd(':'), ignoreCase = true)

    // ── Parsing / Rendern ────────────────────────────────────────────────────

    /**
     * Liest den Zutaten-Text. Gleichnamige Kopfzeilen werden zusammengeführt (Reihenfolge = erstes Vorkommen).
     * Zeilen vor der ersten Kopfzeile landen in [DEFAULT_SECTION]. Leerer Text → eine leere Default-Sektion.
     */
    fun parse(text: String): List<EditSection> {
        val order = mutableListOf<String>()
        val map = linkedMapOf<String, MutableList<String>>()
        var current: String? = null

        fun ensure(name: String): MutableList<String> {
            val existing = order.firstOrNull { sameName(it, name) }
            if (existing != null) return map.getValue(existing)
            order += name
            return map.getOrPut(name) { mutableListOf() }
        }

        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            if (isHeaderLine(raw)) {
                val name = raw.trim().trimEnd(':').trim()
                ensure(name)
                current = order.first { sameName(it, name) }
            } else {
                val name = current ?: DEFAULT_SECTION.also { ensure(it); current = it }
                map.getValue(name) += stripBullet(raw)
            }
        }
        if (order.isEmpty()) return listOf(EditSection(DEFAULT_SECTION, emptyList()))
        return order.map { EditSection(it, map.getValue(it).toList()) }
    }

    /**
     * Schreibt den Text zurück. Eine einzige Default-Sektion ergibt flachen Text ohne Kopfzeile
     * (wie bisher); sonst „Name“ + „• Zeile“.
     */
    fun render(sections: List<EditSection>): String {
        if (sections.size == 1 && sameName(sections[0].name, DEFAULT_SECTION)) {
            return sections[0].lines.joinToString("\n") { "• $it" }
        }
        return sections.joinToString("\n") { s ->
            (listOf(s.name.trim()) + s.lines.map { "• $it" }).joinToString("\n")
        }
    }

    // ── Auflösen: Text + Matches → Abschnitte mit Kennzahlen ────────────────

    private fun norm(s: String): String = normalizeForCoverageMatch(stripBullet(s))

    private fun legacyLabel(group: String): String = when (group.trim().lowercase()) {
        "side" -> "Beilage"
        "sauce" -> "Sauce / Fleisch"
        "main" -> "Hauptteil"
        else -> group.trim()
    }

    /**
     * Ordnet jede aktive Zutat genau einem Abschnitt zu.
     *
     * Reihenfolge der Zuordnung pro Match:
     * 1. exakt gleiche (normalisierte) Textzeile, noch nicht vergeben
     * 2. Teil-Übereinstimmung (≥ 3 Zeichen), noch nicht vergeben
     * 3. Altdaten: bei flachem Text gewinnt [IngredientMatch.componentGroup]
     * 4. Rest: Abschnitt des gespeicherten componentGroup-Namens, sonst erster Abschnitt
     *
     * Matches ohne Textzeile erscheinen als zusätzliche Zeile → die Summe bleibt vollständig.
     */
    fun resolve(text: String, matches: List<IngredientMatch>): List<ResolvedSection> {
        var sections = parse(text)
        val active = matches.withIndex().filter { !it.value.isDeleted }

        // Altdaten: flacher Text, aber Matches tragen side/sauce/… → Abschnitte daraus bauen.
        val flat = sections.size == 1 && sameName(sections[0].name, DEFAULT_SECTION)
        val hasLegacy = active.any { !it.value.componentGroup.isNullOrBlank() }
        if (flat && hasLegacy) {
            val names = linkedMapOf<String, MutableList<String>>()
            for ((_, m) in active) {
                val g = m.componentGroup?.takeIf { it.isNotBlank() }?.let(::legacyLabel) ?: DEFAULT_SECTION
                val key = names.keys.firstOrNull { sameName(it, g) } ?: g
                names.getOrPut(key) { mutableListOf() } += stripBullet(m.ingredientRaw.ifBlank { m.ingredientName })
            }
            val covered = names.values.flatten().map(::norm).filter { it.isNotEmpty() }
            val rest = sections[0].lines.filter { l ->
                val n = norm(l)
                n.isNotEmpty() && covered.none { c -> c.length >= 3 && (n.contains(c) || c.contains(n)) }
            }
            if (rest.isNotEmpty()) {
                val first = names.keys.firstOrNull() ?: DEFAULT_SECTION
                names.getOrPut(first) { mutableListOf() }.addAll(rest)
            }
            if (names.isNotEmpty()) sections = names.map { (n, l) -> EditSection(n, l) }
        }

        // Linien-Index: (sectionIdx, lineIdx) in Textreihenfolge
        data class Slot(val s: Int, val l: Int, val key: String)
        val slots = sections.flatMapIndexed { si, sec ->
            sec.lines.mapIndexed { li, line -> Slot(si, li, norm(line)) }
        }
        val used = mutableSetOf<Pair<Int, Int>>()
        val assignment = mutableMapOf<Int, Pair<Int, Int>>() // matchIndex → (s, l)

        fun keysOf(m: IngredientMatch) =
            listOf(norm(m.ingredientRaw), norm(m.ingredientName)).filter { it.isNotEmpty() }

        // Pass 1: exakt
        for ((mi, m) in active) {
            val keys = keysOf(m)
            val slot = slots.firstOrNull { (it.s to it.l) !in used && it.key in keys }
            if (slot != null) { used += slot.s to slot.l; assignment[mi] = slot.s to slot.l }
        }
        // Pass 2: Teil-Übereinstimmung
        for ((mi, m) in active) {
            if (mi in assignment) continue
            val keys = keysOf(m).filter { it.length >= 3 }
            val slot = slots.firstOrNull { sl ->
                (sl.s to sl.l) !in used && sl.key.length >= 3 &&
                    keys.any { k -> sl.key.contains(k) || k.contains(sl.key) }
            }
            if (slot != null) { used += slot.s to slot.l; assignment[mi] = slot.s to slot.l }
        }

        // Aufbau
        val lines = sections.map { mutableListOf<ResolvedLine>() }
        sections.forEachIndexed { si, sec ->
            sec.lines.forEachIndexed { li, line ->
                val mi = assignment.entries.firstOrNull { it.value == (si to li) }?.key
                lines[si] += ResolvedLine(line, mi)
            }
        }
        // Waisen (kein Textbezug) → Abschnitt des gespeicherten Namens, sonst erster
        for ((mi, m) in active) {
            if (mi in assignment) continue
            val target = m.componentGroup?.takeIf { it.isNotBlank() }?.let { g ->
                val label = legacyLabel(g)
                sections.indexOfFirst { sameName(it.name, label) }.takeIf { it >= 0 }
            } ?: 0
            lines[target] += ResolvedLine(
                stripBullet(m.ingredientRaw.ifBlank { m.ingredientName }), mi
            )
        }

        return sections.mapIndexed { si, sec ->
            val ls = lines[si]
            val ms = ls.mapNotNull { it.matchIndex?.let { i -> matches[i] } }
            ResolvedSection(
                name = sec.name,
                lines = ls,
                kcal = ms.sumOf { (it.matchedCalories ?: 0f).toDouble() }.toFloat(),
                rawG = ms.sumOf { (it.manualAmountG ?: it.amountGrams).toDouble().coerceAtLeast(0.0) }.toFloat(),
                unmatchedCount = ls.count { it.matchIndex == null }
            )
        }
    }

    /**
     * Section-Name pro Match-Index (nur aktive). Für das Zurückschreiben nach [IngredientMatch.componentGroup].
     * Bei nur einem Abschnitt → null (kein Split).
     */
    fun groupsByMatchIndex(resolved: List<ResolvedSection>): Map<Int, String?> {
        val single = resolved.size <= 1
        val out = mutableMapOf<Int, String?>()
        for (sec in resolved) for (l in sec.lines) {
            l.matchIndex?.let { out[it] = if (single) null else sec.name }
        }
        return out
    }

    // ── Editieren (pure) ─────────────────────────────────────────────────────

    fun moveLine(
        sections: List<EditSection>,
        fromSection: Int,
        lineIdx: Int,
        toSection: Int
    ): List<EditSection> {
        if (fromSection !in sections.indices || toSection !in sections.indices) return sections
        if (lineIdx !in sections[fromSection].lines.indices || fromSection == toSection) return sections
        val line = sections[fromSection].lines[lineIdx]
        return sections.mapIndexed { i, s ->
            when (i) {
                fromSection -> s.copy(lines = s.lines.filterIndexed { li, _ -> li != lineIdx })
                toSection -> s.copy(lines = s.lines + line)
                else -> s
            }
        }
    }

    /** Neuer, leerer Abschnitt; gibt es den Namen schon, bleibt die Liste unverändert. */
    fun addSection(sections: List<EditSection>, name: String): List<EditSection> {
        val n = name.trim().trimEnd(':').trim()
        if (n.isEmpty() || sections.any { sameName(it.name, n) }) return sections
        return sections + EditSection(n, emptyList())
    }

    /** Benennt um; gleicht der neue Name einem anderen Abschnitt, werden beide zusammengeführt. */
    fun renameSection(sections: List<EditSection>, idx: Int, newName: String): List<EditSection> {
        val n = newName.trim().trimEnd(':').trim()
        if (idx !in sections.indices || n.isEmpty()) return sections
        val other = sections.indexOfFirst { sameName(it.name, n) }
        if (other < 0 || other == idx) {
            return sections.mapIndexed { i, s -> if (i == idx) s.copy(name = n) else s }
        }
        val merged = sections[other].copy(lines = sections[other].lines + sections[idx].lines)
        return sections.mapIndexedNotNull { i, s ->
            when (i) { idx -> null; other -> merged; else -> s }
        }
    }

    /** Löscht einen Abschnitt; seine Zeilen wandern in den vorherigen (bzw. nächsten). Mindestens einer bleibt. */
    fun deleteSection(sections: List<EditSection>, idx: Int): List<EditSection> {
        if (sections.size <= 1 || idx !in sections.indices) return sections
        val target = if (idx > 0) idx - 1 else 1
        return sections.mapIndexedNotNull { i, s ->
            when (i) {
                idx -> null
                target -> s.copy(lines = if (idx > target) s.lines + sections[idx].lines else sections[idx].lines + s.lines)
                else -> s
            }
        }
    }

    // ── Garfaktor (gekocht ÷ roh) ────────────────────────────────────────────

    const val MAX_FACTOR_HISTORY = 5

    fun netCookedG(grossG: Float, tareG: Float): Float = (grossG - tareG.coerceAtLeast(0f)).coerceAtLeast(0f)

    fun factorOf(rawG: Float, cookedG: Float): Float? =
        if (rawG > 0f && cookedG > 0f) cookedG / rawG else null

    fun parseFactorHistory(stored: String): List<Float> =
        stored.split(';').mapNotNull { it.trim().replace(',', '.').toFloatOrNull() }.filter { it > 0f }

    fun encodeFactorHistory(history: List<Float>): String =
        history.joinToString(";") { "%.4f".format(java.util.Locale.ROOT, it) }

    /** Hängt eine Messung an; behält die letzten [MAX_FACTOR_HISTORY]. */
    fun addFactor(history: List<Float>, factor: Float): List<Float> =
        (history + factor).takeLast(MAX_FACTOR_HISTORY)

    fun meanFactor(history: List<Float>): Float? =
        history.takeIf { it.isNotEmpty() }?.let { h -> (h.sumOf { it.toDouble() } / h.size).toFloat() }

    /** Vorschlag Kochgewicht in g aus Rohsumme und bisherigen Messungen (null ohne Messung). */
    fun suggestedCookedG(rawG: Float, history: List<Float>): Int? {
        val f = meanFactor(history) ?: return null
        if (rawG <= 0f) return null
        return Math.round(rawG * f)
    }

    // ── Abschnitt → Komponente (getrennt abwiegen) ──────────────────────────

    fun matchesOf(section: ResolvedSection, matches: List<IngredientMatch>): List<IngredientMatch> =
        section.lines.mapNotNull { it.matchIndex?.let(matches::getOrNull) }

    /** Komponente zum Abschnittsnamen (Name = Identität, keine Schlüsselwort-Heuristik). */
    fun findComponent(components: List<RecipeComponent>, sectionName: String): RecipeComponent? =
        components.firstOrNull { sameName(it.name, sectionName) }

    /** Nährwerte eines Abschnitts als Komponente; Kochgewicht und Garfaktor-Verlauf kommen von aussen. */
    fun toComponent(
        recipeId: Long,
        section: ResolvedSection,
        matches: List<IngredientMatch>,
        cookedG: Float,
        factorHistory: List<Float>,
        sortOrder: Int,
        id: Long = 0
    ): RecipeComponent {
        val ms = matchesOf(section, matches)
        fun sum(f: (IngredientMatch) -> Float?) = ms.sumOf { (f(it) ?: 0f).toDouble() }.toFloat()
        return RecipeComponent(
            id = id,
            recipeId = recipeId,
            name = section.name,
            cookedWeightG = cookedG,
            totalCalories = sum { it.matchedCalories },
            proteinG = sum { it.matchedProtein },
            carbsG = sum { it.matchedCarbs },
            fatG = sum { it.matchedFat },
            fiberG = sum { it.manualFiberG ?: it.matchedFiber },
            sortOrder = sortOrder,
            factorHistory = encodeFactorHistory(factorHistory)
        )
    }

    /**
     * Frische Nährwerte pro Komponente aus den Abschnitten des Rezepts (Zuordnung über den Namen).
     * Komponenten ohne passenden Abschnitt oder ohne kcal bleiben unverändert.
     */
    fun refreshComponents(
        text: String,
        components: List<RecipeComponent>,
        matches: List<IngredientMatch>
    ): List<RecipeComponent> {
        val resolved = resolve(text, matches)
        return components.map { c ->
            val sec = resolved.firstOrNull { sameName(it.name, c.name) } ?: return@map c
            val fresh = toComponent(
                c.recipeId, sec, matches, c.cookedWeightG,
                parseFactorHistory(c.factorHistory), c.sortOrder, c.id
            )
            if (fresh.totalCalories > 0f) fresh else c
        }
    }
}
