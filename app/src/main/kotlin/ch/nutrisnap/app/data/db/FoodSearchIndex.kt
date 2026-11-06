package ch.nutrisnap.app.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.nutrisnap.app.domain.FoodSearchText
import ch.nutrisnap.app.data.model.FoodItem

/**
 * Normalisierter Volltext-Suchindex über food_items (FTS4, unabhängig von Rooms eigenem
 * food_items_fts-Index).
 *
 * - name_norm/brand_norm: ä/ö/ü/Ä/Ö/Ü → a/o/u, ß → ss (Diakritik-Entfernung). Weitere
 *   Akzente (é, è, …) und Gross-/Kleinschreibung erledigt der unicode61-Tokenizer.
 *   So finden "Rüebli", "ruebli" und "RÜEBLI" denselben Eintrag.
 * - prefix="2,3,4": schnelle Präfixsuche beim Tippen.
 * - Trigger halten den Index bei INSERT/UPDATE/DELETE von food_items synchron.
 *
 * Room kennt diese Tabelle nicht (kein @Entity) und validiert sie deshalb nicht; Zugriff nur
 * über @RawQuery. Alle Funktionen sind fehlertolerant: scheitert der Index, bleibt die App
 * mit der bisherigen Suche funktionsfähig.
 */
internal object FoodSearchIndex {
    const val TABLE = "food_search_fts"

    private fun norm(col: String) =
        "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE($col,''),'ä','a'),'Ä','a'),'ö','o'),'Ö','o'),'ü','u'),'Ü','u'),'ß','ss')"

    /** Legt Tabelle + Trigger an (idempotent). Füllt den Index NICHT. */
    fun createStructure(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts4(" +
                "name_norm, brand_norm, tokenize=unicode61, prefix=\"2,3,4\")"
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS food_search_ai AFTER INSERT ON food_items BEGIN
                INSERT OR REPLACE INTO $TABLE(docid, name_norm, brand_norm)
                VALUES (new.id, ${norm("new.name")}, ${norm("new.brand")});
            END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS food_search_ad AFTER DELETE ON food_items BEGIN
                DELETE FROM $TABLE WHERE docid = old.id;
            END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS food_search_au AFTER UPDATE OF name, brand ON food_items BEGIN
                DELETE FROM $TABLE WHERE docid = old.id;
                INSERT OR REPLACE INTO $TABLE(docid, name_norm, brand_norm)
                VALUES (new.id, ${norm("new.name")}, ${norm("new.brand")});
            END""".trimIndent()
        )
    }

    /** Baut den Index komplett aus food_items neu auf. */
    fun rebuild(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM $TABLE")
        db.execSQL(
            "INSERT INTO $TABLE(docid, name_norm, brand_norm) " +
                "SELECT id, ${norm("name")}, ${norm("brand")} FROM food_items"
        )
    }

    /** Struktur anlegen und bei leerem Index (aber vorhandenen Produkten) neu aufbauen. */
    fun ensure(db: SupportSQLiteDatabase) {
        runCatching {
            createStructure(db)
            val indexEmpty = db.query("SELECT 1 FROM $TABLE LIMIT 1").use { !it.moveToFirst() }
            if (indexEmpty) {
                val hasItems = db.query("SELECT 1 FROM food_items LIMIT 1").use { it.moveToFirst() }
                if (hasItems) rebuild(db)
            }
        }
    }

    /**
     * Kandidaten für die Suche: erst Präfix-Treffer über den FTS-Index (schnell), bei zu wenig
     * Treffern zusätzlich Teilstring-Treffer (z. B. "brot" → "Vollkornbrot"). Fehlertolerant:
     * bei jedem Fehler leere Liste, der Aufrufer fällt dann auf die bisherige Suche zurück.
     */
    suspend fun candidates(dao: FoodItemDao, query: String, limit: Int = 150): List<FoodItem> {
        val found = LinkedHashMap<Int, FoodItem>()
        for (m in FoodSearchText.matchQueries(query)) {
            val sql = "SELECT food_items.* FROM $TABLE JOIN food_items ON food_items.id = $TABLE.docid " +
                "WHERE $TABLE MATCH ? LIMIT $limit"
            runCatching { dao.rawFoodQuery(SimpleSQLiteQuery(sql, arrayOf<Any>(m))) }
                .getOrDefault(emptyList()).forEach { found.putIfAbsent(it.id, it) }
        }
        val tokens = FoodSearchText.tokens(query)
        if (found.size < MIN_BEFORE_INFIX && tokens.isNotEmpty() && tokens.sumOf { it.length } >= 3) {
            val where = tokens.joinToString(" AND ") { "($TABLE.name_norm || ' ' || $TABLE.brand_norm) LIKE ?" }
            val sql = "SELECT food_items.* FROM $TABLE JOIN food_items ON food_items.id = $TABLE.docid " +
                "WHERE $where LIMIT $limit"
            runCatching { dao.rawFoodQuery(SimpleSQLiteQuery(sql, tokens.map { "%$it%" }.toTypedArray<Any>())) }
                .getOrDefault(emptyList()).forEach { found.putIfAbsent(it.id, it) }
        }
        return found.values.toList()
    }

    private const val MIN_BEFORE_INFIX = 15
}
