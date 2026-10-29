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
        WebResult(r, i, "${r.size} results · %.1f s".format(ms / 1000.0))
    }
}
