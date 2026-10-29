package fi.lepune.haku.search

/** Cheap rule-based understanding of a query — no model call needed. */
object Intent {

    private val SHOPPING = Regex(
        "\\b(halvi\\w*|halpa\\w*|halvem\\w*|hinta\\w*|hinno\\w*|hintoj\\w*|maksa\\w*|osta\\w*|mist[äa] saa\\w*|" +
            "tarjou\\w*|alennu\\w*|ale\\b|kauppa\\w*|verkkokaup\\w*|tilaa\\w*|" +
            "cheap\\w*|price\\w*|pricing|buy|cost\\w*|deal\\w*|shop\\w*|store)\\b|€|\\beur\\b|\\beuro\\w*",
        RegexOption.IGNORE_CASE,
    )

    private val STOP = setOf(
        // Finnish question / shopping filler
        "mistä", "mista", "mitä", "mita", "mikä", "mika", "missä", "missa", "kuka", "miten", "onko", "voiko",
        "saan", "saa", "saisi", "saisin", "saada", "löytyy", "loytyy", "löydän", "loydan", "on", "ovat", "olisi",
        "halvimmalla", "halvin", "halvimman", "halvimmat", "halvimpia", "halvemmalla", "halpa", "halvalla", "edullisin", "edullisimmin",
        "hinta", "hintaa", "hinnat", "hinnalla", "hintoja", "paljonko", "maksaa", "ostaa", "osta", "ostan", "tilata",
        "suomesta", "suomessa", "suomi", "netistä", "netista", "verkosta", "verkkokaupasta", "kaupasta", "kaupoista",
        "paras", "parhaan", "kannattaa", "hyvä", "hyvän", "jokin", "joku", "jostain", "nyt", "tänään", "ja", "tai",
        "se", "sen", "tämä", "tuo", "minä", "mä", "ma", "voin", "pitäisi",
        // English
        "where", "can", "i", "buy", "get", "the", "a", "an", "in", "of", "for", "to", "from", "is", "what", "whats",
        "cheapest", "cheap", "price", "prices", "best", "finland", "online", "shop", "store", "how", "much", "does",
    )

    private val DIM = Regex("""(\d+(?:[.,]\d+)?)\s*[x×*]\s*(\d+(?:[.,]\d+)?)(?:\s*[x×*]\s*(\d+(?:[.,]\d+)?))?""")

    fun isShopping(q: String) = SHOPPING.containsMatchIn(q)

    /** Finnish if it has å/ä/ö or common Finnish function words. */
    fun isFinnish(q: String): Boolean {
        val low = q.lowercase()
        if (low.any { it == 'ä' || it == 'ö' }) return true
        return Regex("\\b(mistä|mikä|kuka|miten|onko|saan|kanssa|tai|ja|on|se|hinta|paljonko)\\b").containsMatchIn(low)
    }

    /** Product terms with filler words removed, e.g. "kasvatusteltan 120x120x200". */
    fun productTerms(q: String): String {
        val words = q.replace(Regex("[?!.,;:\"()]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        val kept = words.filter { w -> w.lowercase() !in STOP }
        return kept.joinToString(" ").ifBlank { q.trim() }.take(100)
    }

    /** Normalised dimension strings in the text, e.g. ["120x120x200"]. */
    fun dims(text: String): List<String> = DIM.findAll(text).map { m ->
        m.groupValues.drop(1).filter { it.isNotEmpty() }.joinToString("x") { it.replace(',', '.').removeSuffix(".0") }
    }.toList()

    /** Lowercase word stems (first 5 letters) for loose matching across Finnish inflections. */
    fun stems(text: String): Set<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 3 && it !in STOP && !it.all(Char::isDigit) }
        .map { it.take(5) }
        .toSet()
}
