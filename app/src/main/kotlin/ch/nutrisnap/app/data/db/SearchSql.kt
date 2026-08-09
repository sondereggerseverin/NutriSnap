package ch.nutrisnap.app.data.db

/**
 * SQL-Fragmente für umlaut-tolerante LIKE-Suche direkt in Room-Queries.
 *
 * Bildet ä/ö/ü/ß (beide Schreibweisen) auf ae/oe/ue/ss ab, damit z.B. "ruebli"
 * "Rüebli" findet und umgekehrt. Kein Schema-Change/Migration nötig, da die
 * Normalisierung zur Query-Zeit auf Spalte UND Parameter angewendet wird statt
 * in einer zusätzlichen gespeicherten Spalte. LIKE ist bereits ASCII-case-
 * insensitiv, ein zusätzliches LOWER() ist daher nicht nötig.
 *
 * Tippfehler-Toleranz (Levenshtein) und Synonyme bleiben Aufgabe von
 * [ch.nutrisnap.app.domain.SearchUtils] auf den bereits geladenen Kandidaten -
 * das hier deckt nur ab, dass der Kandidat überhaupt aus der DB geladen wird.
 */
internal object SearchSql {
    const val NORM_NAME =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(name,'ä','ae'),'Ä','ae'),'ö','oe'),'Ö','oe'),'ü','ue'),'Ü','ue'),'ß','ss')"
    const val NORM_BRAND =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(brand,'ä','ae'),'Ä','ae'),'ö','oe'),'Ö','oe'),'ü','ue'),'Ü','ue'),'ß','ss')"
    const val NORM_QUERY =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(:query,'ä','ae'),'Ä','ae'),'ö','oe'),'Ö','oe'),'ü','ue'),'Ü','ue'),'ß','ss')"
}
