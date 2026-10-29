package fi.lepune.haku.search

import android.os.SystemClock
import fi.lepune.haku.tools.Hit
import fi.lepune.haku.tools.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Plain search-engine results plus a Wikipedia card when it clearly matches. No model involved. */
class QuickWeb(private val web: WebTools) {

    private companion object {
        val QUESTION_WORDS = setOf(
            "mikä", "mitä", "miksi", "miten", "kuka", "missä", "milloin", "paljonko", "onko",
            "what", "why", "how", "who", "where", "when", "is", "are", "does",
        )
    }

    suspend fun run(question: String): WebResult = coroutineScope {
        val t0 = SystemClock.elapsedRealtime()
        val lang = if (Intent.isFinnish(question)) "fi" else "en"
        val nav = isNavigational(question)
        val compact = question.lowercase().filter { it.isLetterOrDigit() }

        val results = async(Dispatchers.IO) { runCatching { web.webSearch(question, limit = 10) }.getOrDefault(emptyList()) }
        // Wikipedia card + (for navigational queries) the article's official website from Wikidata.
        val wiki = async(Dispatchers.IO) {
            runCatching {
                val top = web.wikiSearch(Intent.productTerms(question), lang).firstOrNull() ?: return@runCatching null
                val q = Intent.stems(question)
                val t = Intent.stems(top.title)
                if (q.none { s -> t.any { it.startsWith(s.take(4)) || s.startsWith(it.take(4)) } }) return@runCatching null
                val key = top.url.substringAfterLast("/wiki/")
                val summary = web.wikiSummary(lang, key)
                val official = if (nav) runCatching { web.officialSite(lang, key) }.getOrNull() else null
                summary to official
            }.getOrNull()
        }
        // Direct guess: "runescape" -> runescape.com, works even when every search engine throttles us.
        val guess = async(Dispatchers.IO) {
            if (nav && compact.length in 3..30 && question.trim().split(Regex("\\s+")).size <= 2)
                runCatching { web.guessDomain(compact) }.getOrNull() else null
        }

        val r = results.await()
        val (instant, officialUrl) = wiki.await() ?: (null to null)
        val site = if (!nav) null else
            navigationalTarget(question, r)
                ?: officialUrl?.let { u -> Hit(java.net.URI(u).host?.removePrefix("www.") ?: u, u, instant?.title ?: "") }
                ?: guess.await()
        if (site == null) guess.cancel()
        val ms = SystemClock.elapsedRealtime() - t0
        WebResult(r, instant, "${r.size} results · %.1f s".format(ms / 1000.0), site = site)
    }

    /** Short, question-less, non-shopping queries are usually "take me to X". */
    private fun isNavigational(q: String): Boolean {
        val words = q.trim().split(Regex("\\s+"))
        return words.size <= 4 && !q.contains('?') && !Intent.isShopping(q) &&
            q.lowercase().filter { it.isLetterOrDigit() }.length >= 3 &&
            words.none { it.lowercase() in QUESTION_WORDS }
    }

    /**
     * Pick the first top-3 result whose domain spells the query ("oldschool runescape" ->
     * oldschool.runescape.com).
     */
    private fun navigationalTarget(q: String, results: List<Hit>): Hit? {
        val words = q.trim().split(Regex("\\s+"))
        val compact = q.lowercase().filter { it.isLetterOrDigit() }
        return results.take(3).firstOrNull { h ->
            val host = runCatching { java.net.URI(h.url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return@firstOrNull false
            val label = host.substringBeforeLast('.').filter { it.isLetterOrDigit() }
            val main = host.substringBeforeLast('.').substringAfterLast('.')
            label.contains(compact) || compact.contains(main) && main.length >= 3 ||
                words.any { w -> w.length >= 4 && main == w.lowercase().filter { it.isLetterOrDigit() } }
        }
    }

    /** What people say: forum and community threads (Reddit, io-tech, Suomi24, Vauva.fi). */
    suspend fun discussions(question: String): List<Hit> = coroutineScope {
        val terms = Intent.productTerms(question)
        val sites = if (Intent.isFinnish(question))
            listOf("io-tech.fi", "suomi24.fi", "reddit.com", "vauva.fi")
        else listOf("reddit.com", "io-tech.fi", "stackexchange.com", "news.ycombinator.com")
        val lists = sites.map { site ->
            async(Dispatchers.IO) { runCatching { web.webSearch("site:$site $terms", limit = 4) }.getOrDefault(emptyList()) }
        }.map { it.await() }
        // Interleave so every forum gets a voice near the top.
        val out = mutableListOf<Hit>()
        for (i in 0 until 4) for (l in lists) l.getOrNull(i)?.let(out::add)
        out.distinctBy { it.url }.take(10)
    }
}
