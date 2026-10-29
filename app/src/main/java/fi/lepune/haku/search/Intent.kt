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
        // condition words: they describe the listing, not the product, so they must not decide matching
        "used", "working", "but", "second", "hand", "secondhand", "refurbished", "condition", "new", "and", "with",
        "käytetty", "käytettynä", "kaytetty", "toimiva", "toimivana", "kunnossa", "uusi", "uutena", "mutta",
    )

    private val USED = Regex(
        "\\b(used|second[- ]?hand|pre-?owned|refurb\\w*|käytet\\w*|kaytet\\w*|tori|huuto)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val YEAR = Regex("(19|20)\\d\\d")

    /** "used MI100", "käytetty näytönohjain": look at second-hand markets too. */
    fun isUsed(q: String) = USED.containsMatchIn(q)

    /**
     * Model codes like "MI100", "RTX 5070", "9800X3D": letters+digits, or a 3–5 digit number.
     * Returned lowercase without separators; a listing must contain every one of them.
     */
    fun modelCodes(q: String): List<String> =
        q.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { t ->
                t.length >= 3 && t.any(Char::isDigit) && dims(t).isEmpty() &&
                    (t.any(Char::isLetter) || t.length in 3..5) &&
                    !YEAR.matches(t) // "best phone 2026" is not a model number
            }
            .distinct()

    private val COMPUTER = Regex(
        "\\b(pelikone|pelitietokone|tietokone|kone|pc|desktop|kannettava|läppäri|laptop|kokoonpano)\\b|" +
            "\\bi[3579][- ]?\\d{4,5}|\\bryzen\\s?[3579]\\b|\\br[3579][- ]\\d{4}",
        RegexOption.IGNORE_CASE,
    )

    /** "Pelikone RTX 3060 | i5-9600KF" is a whole computer, not the card the user asked for. */
    fun isComputer(text: String) = COMPUTER.containsMatchIn(text)

    private val VARIANTS = listOf("ti", "super", "xtx", "xt", "max", "pro", "plus", "mini")

    /**
     * True when [title] names every model code of the query as the same model: "RTX 3060 12GB"
     * matches "3060", "RTX 3060 Ti" does not (unless the query said Ti too).
     */
    fun sameModel(title: String, query: String): Boolean {
        val codes = modelCodes(query)
        if (codes.isEmpty()) return true
        val t = compact(title)
        val q = compact(query)
        return codes.all { code ->
            var i = t.indexOf(code)
            var ok = false
            while (i >= 0 && !ok) {
                val after = t.substring(i + code.length)
                val variant = VARIANTS.firstOrNull { after.startsWith(it) }
                ok = variant == null || q.contains(code + variant)
                i = t.indexOf(code, i + 1)
            }
            ok
        }
    }

    /** Lowercase, letters and digits only: "MI-100" and "MI 100" both become "mi100". */
    fun compact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

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
