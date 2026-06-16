package ch.nutrisnap.app.domain

object SearchUtils {

    fun fuzzyMatch(query: String, candidate: String): Boolean {
        val q = normalize(query)
        val c = normalize(candidate)
        if (c.contains(q)) return true
        if (q.length >= 3) {
            val windowSize = q.length + 2
            for (i in 0..(c.length - q.length).coerceAtLeast(0)) {
                val window = c.substring(i, (i + windowSize).coerceAtMost(c.length))
                if (levenshteinDistance(q, window) <= if (q.length > 6) 2 else 1) return true
            }
        }
        val synonyms = GERMAN_SYNONYMS[q]
        if (synonyms != null) return synonyms.any { c.contains(it) }
        return false
    }

    fun normalize(text: String): String = text
        .lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .replace("ß", "ss")
        .trim()

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
            else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }

    private val GERMAN_SYNONYMS = mapOf(
        "haehnchen" to listOf("chicken", "huhn", "haehnchenbrustfilet"),
        "chicken" to listOf("haehnchen", "huhn", "geflügel"),
        "kartoffel" to listOf("potato", "erdapfel"),
        "tomate" to listOf("tomato", "paradeiser"),
        "apfel" to listOf("apple"),
        "banane" to listOf("banana"),
        "joghurt" to listOf("yogurt", "yoghurt"),
        "vollkornbrot" to listOf("wholegrain bread", "vollkorn"),
        "magerquark" to listOf("quark", "topfen", "cottage cheese"),
        "haferflocken" to listOf("oats", "oatmeal", "porridge"),
        "rinderhack" to listOf("ground beef", "hackfleisch", "faschiertes")
    )
}
