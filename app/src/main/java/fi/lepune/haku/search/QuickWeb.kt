package fi.lepune.haku.search

import android.os.SystemClock
import fi.lepune.haku.tools.Hit
import fi.lepune.haku.tools.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Plain search-engine results plus a Wikipedia card when it clearly matches. No model involved. */
class QuickWeb(private val web: WebTools) {

    suspend fun run(question: String): WebResult = coroutineScope {
        val t0 = SystemClock.elapsedRealtime()
        val lang = if (Intent.isFinnish(question)) "fi" else "en"
        val results = async(Dispatchers.IO) { runCatching { web.webSearch(question, limit = 10) }.getOrDefault(emptyList()) }
        val instant = async(Dispatchers.IO) {
            runCatching {
                val top = web.wikiSearch(Intent.productTerms(question), lang).firstOrNull() ?: return@runCatching null
                // Only show it when the article title shares a word with the question.
                val q = Intent.stems(question)
                val t = Intent.stems(top.title)
                if (q.none { s -> t.any { it.startsWith(s.take(4)) || s.startsWith(it.take(4)) } }) return@runCatching null
                web.wikiSummary(lang, top.url.substringAfterLast("/wiki/"))
            }.getOrNull()
        }
        val r = results.await()
        val i = instant.await()
        val ms = SystemClock.elapsedRealtime() - t0
        WebResult(r, i, "${r.size} results · %.1f s".format(ms / 1000.0), site = navigationalTarget(question, r))
    }

    /**
     * Short queries that name a site ("runescape", "yle areena", "posti seuranta") are navigation, not
     * questions: pick the first top-3 result whose domain spells the query.
     */
    private fun navigationalTarget(q: String, results: List<Hit>): Hit? {
        val words = q.trim().split(Regex("\\s+"))
        if (words.size > 4 || q.contains('?') || Intent.isShopping(q)) return null
        val compact = q.lowercase().filter { it.isLetterOrDigit() }
        if (compact.length < 3) return null
        return results.take(3).firstOrNull { h ->
            val host = runCatching { java.net.URI(h.url).host }.getOrNull()?.lowercase()?.removePrefix("www.") ?: return@firstOrNull false
            val label = host.substringBeforeLast('.').filter { it.isLetterOrDigit() } // "oldschool.runescape" -> "oldschoolrunescape"
            val main = host.substringBeforeLast('.').substringAfterLast('.')            // "runescape"
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
