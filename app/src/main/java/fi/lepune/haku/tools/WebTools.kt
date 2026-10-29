package fi.lepune.haku.tools

import android.os.Looper
import fi.lepune.haku.data.Settings
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Hit(val title: String, val url: String, val snippet: String)

/** Similarity of each text to the query (higher = more relevant), or null if unavailable. Blocking. */
typealias Ranker = (query: String, texts: List<String>) -> FloatArray?

/**
 * Network side of the search tools. No server needed: DuckDuckGo + Wikipedia online APIs.
 * If a SearXNG URL is set in settings, web search goes there instead.
 */
class WebTools(private val settings: () -> Settings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pool = Executors.newCachedThreadPool()

    /** Tool callbacks may arrive on any thread; never do network on the main thread. */
    private fun <T> offMain(block: () -> T): T =
        if (Looper.myLooper() == Looper.getMainLooper()) pool.submit(java.util.concurrent.Callable { block() }).get() else block()

    // ---------------------------------------------------------------- web search

    fun webSearch(query: String, limit: Int = MAX_HITS): List<Hit> = offMain {
        val searx = normalizeSearxUrl(settings().searxngUrl).orEmpty()
        // Each engine can throttle or block us; fall through to the next one instead of failing.
        val engines = buildList<() -> List<Hit>> {
            if (searx.isNotEmpty()) add { searxng(searx, query, limit) }
            add { duckDuckGo(query, limit) }
            add { duckDuckGoLite(query, limit) }
            add { mojeek(query, limit) }
        }
        for (e in engines) {
            val r = runCatching { e() }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return@offMain r
        }
        emptyList()
    }

    /** Mojeek: independent index with a plain HTML results page; used when DuckDuckGo throttles. */
    private fun mojeek(query: String, limit: Int): List<Hit> {
        val url = "https://www.mojeek.com/search".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val doc = Jsoup.parse(get(url.toString(), BROWSER_UA), url.toString())
        return doc.select("ul.results-standard > li, ul.results > li").mapNotNull { li ->
            val a = li.selectFirst("a.title, h2 a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifBlank { return@mapNotNull null }
            Hit(a.text(), href, li.selectFirst("p.s, p")?.text().orEmpty().take(SNIPPET))
        }.take(limit)
    }

    /**
     * Settings "Test" button: one real query against the instance, with a plain-language
     * reason when it fails (the normal search silently falls back to DuckDuckGo).
     */
    fun testSearxng(rawUrl: String): String = offMain {
        val base = normalizeSearxUrl(rawUrl) ?: return@offMain "Enter a URL like http://server:8080"
        val start = System.currentTimeMillis()
        try {
            val n = searxng(base, "wikipedia", 10).size
            val ms = System.currentTimeMillis() - start
            if (n == 0) "Connected, but 0 results. Check the engines enabled in SearXNG settings."
            else "OK: $n results in $ms ms from $base"
        } catch (t: Throwable) {
            val m = t.message.orEmpty()
            when {
                "403" in m -> "HTTP 403: enable JSON output in SearXNG settings.yml (search: formats: [html, json])."
                "429" in m -> "HTTP 429: SearXNG's rate limiter blocks the app. Set server: limiter: false."
                "CLEARTEXT" in m.uppercase() -> "Plain http blocked by Android. Update Haku."
                t is javax.net.ssl.SSLException || "CertPath" in m || "trust anchor" in m ->
                    "Certificate not trusted. Install the server's root CA on the phone (only *.lan, " +
                        "*.home.arpa and *.internal names accept it), or use an http:// address."
                t is java.net.UnknownHostException -> "Host not found. Is the phone using your Pi-hole/Tailscale DNS? " +
                    "Android Private DNS bypasses it; otherwise use the server's IP."
                t is java.net.SocketTimeoutException || t is java.net.ConnectException ->
                    "No connection. Is the server on, the port right, and the phone on LAN/Tailscale?"
                t is org.json.JSONException -> "Got a page, not JSON. Wrong URL or JSON output disabled."
                else -> "Failed: ${m.ifBlank { t.javaClass.simpleName }}"
            }
        }
    }

    private fun searxng(base: String, query: String, limit: Int): List<Hit> {
        val url = "$base/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .build()
        val body = get(url.toString())
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until minOf(results.length(), limit)).map {
            val r = results.getJSONObject(it)
            Hit(r.optString("title"), r.optString("url"), r.optString("content").take(SNIPPET))
        }
    }

    private fun duckDuckGo(query: String, limit: Int): List<Hit> {
        val form = FormBody.Builder()
            .add("q", query)
            .add("kl", settings().searchRegion)
            .build()
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .post(form)
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "fi-FI,fi;q=0.9,en;q=0.8")
            .build()
        val html = client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val doc = Jsoup.parse(html)
        return doc.select("div.result").asSequence()
            .filterNot { it.hasClass("result--ad") }
            .mapNotNull { r ->
                val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
                val url = unwrapDdg(a.attr("href")) ?: return@mapNotNull null
                Hit(a.text(), url, r.selectFirst(".result__snippet")?.text().orEmpty().take(SNIPPET))
            }
            .filterNot { it.url.contains("duckduckgo.com/y.js") }
            .take(limit)
            .toList()
    }

    private fun duckDuckGoLite(query: String, limit: Int): List<Hit> {
        val url = "https://lite.duckduckgo.com/lite/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("kl", settings().searchRegion)
            .build()
        val doc = Jsoup.parse(get(url.toString(), BROWSER_UA))
        val links = doc.select("a.result-link")
        val snippets = doc.select("td.result-snippet")
        return links.take(limit).mapIndexedNotNull { i, a ->
            val u = unwrapDdg(a.attr("href")) ?: return@mapIndexedNotNull null
            Hit(a.text(), u, snippets.getOrNull(i)?.text().orEmpty().take(SNIPPET))
        }
    }

    private fun unwrapDdg(href: String): String? {
        val full = if (href.startsWith("//")) "https:$href" else href
        val parsed = full.toHttpUrlOrNull() ?: return null
        val target = parsed.queryParameter("uddg")
        return when {
            target != null -> URLDecoder.decode(target, "UTF-8")
            parsed.host.endsWith("duckduckgo.com") -> null
            else -> full
        }
    }

    // ---------------------------------------------------------------- wikipedia

    fun wikiSearch(query: String, lang: String): List<Hit> = offMain {
        val l = normalizeLang(lang)
        val url = "https://$l.wikipedia.org/w/rest.php/v1/search/page".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", MAX_HITS.toString())
            .build()
        val pages = JSONObject(get(url.toString(), APP_UA)).optJSONArray("pages")
            ?: return@offMain emptyList()
        (0 until pages.length()).map {
            val p = pages.getJSONObject(it)
            val key = p.optString("key")
            val excerpt = Jsoup.parse(p.optString("excerpt")).text()
            val desc = p.optString("description").takeIf { d -> d.isNotBlank() && d != "null" }
            Hit(
                title = p.optString("title"),
                url = "https://$l.wikipedia.org/wiki/$key",
                snippet = listOfNotNull(desc, excerpt).joinToString(" — ").take(SNIPPET),
            )
        }
    }

    /** Wikipedia lead paragraph for an instant-answer card. */
    fun wikiSummary(lang: String, title: String): Hit? = offMain {
        val l = normalizeLang(lang)
        val enc = java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val o = JSONObject(get("https://$l.wikipedia.org/api/rest_v1/page/summary/$enc", APP_UA))
        if (o.optString("type") == "disambiguation") return@offMain null
        val extract = o.optString("extract").trim()
        if (extract.isEmpty()) null
        else Hit(
            o.optString("title", title),
            o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
                ?: "https://$l.wikipedia.org/wiki/$enc",
            extract.take(700),
        )
    }

    /** Official website from Wikidata (P856) for a Wikipedia article title. */
    fun officialSite(lang: String, title: String): String? = offMain {
        val l = normalizeLang(lang)
        val enc = java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val qid = JSONObject(get("https://$l.wikipedia.org/api/rest_v1/page/summary/$enc", APP_UA))
            .optString("wikibase_item").ifBlank { return@offMain null }
        val claims = JSONObject(get(
            "https://www.wikidata.org/w/api.php?action=wbgetclaims&entity=$qid&property=P856&format=json", APP_UA,
        )).optJSONObject("claims")?.optJSONArray("P856") ?: return@offMain null
        // Prefer a claim marked preferred, otherwise the first one.
        val pick = (0 until claims.length()).map { claims.getJSONObject(it) }
            .sortedByDescending { it.optString("rank") == "preferred" }
            .firstOrNull() ?: return@offMain null
        pick.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")?.ifBlank { null }
    }

    /** "runescape" -> try runescape.com / .fi directly; returns the first that answers with a page. */
    fun guessDomain(compact: String): Hit? = offMain {
        val fast = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
        for (tld in listOf("com", "fi")) {
            val url = "https://$compact.$tld/"
            val ok = runCatching {
                fast.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val finalHost = r.request.url.host.removePrefix("www.")
                    // Accept if we stayed on a domain that still carries the name (not a parking page elsewhere).
                    if (!finalHost.replace(".", "").contains(compact)) return@use null
                    val title = Jsoup.parse(r.body?.string().orEmpty().take(200_000)).title()
                    Hit(title.ifBlank { finalHost }, r.request.url.toString(), "")
                }
            }.getOrNull()
            if (ok != null) return@offMain ok
        }
        null
    }

    // ---------------------------------------------------------------- read page

    fun readPage(url: String): ReadResult = offMain {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return@offMain ReadResult(url, url, "Error: not a valid URL. Pass a full https:// URL from a search result.")
        if (parsed.host.endsWith("wikipedia.org") && parsed.pathSegments.firstOrNull() == "wiki") {
            readWikipedia(parsed.host.substringBefore('.'), parsed.pathSegments.drop(1).joinToString("/"))
        } else {
            readGeneric(parsed.toString())
        }
    }

    data class ReadResult(val title: String, val url: String, val text: String)

    /**
     * The lead plus the paragraphs of a Wikipedia article that best match [query], in article
     * order, each under its section heading. Keyword stems, no model: costs one API call.
     */
    fun wikiPassages(
        articleUrl: String,
        query: String,
        maxChars: Int = 1800,
        ranker: Ranker? = null,
    ): String? = offMain {
        val parsed = articleUrl.toHttpUrlOrNull() ?: return@offMain null
        if (!parsed.host.endsWith("wikipedia.org") || parsed.pathSegments.firstOrNull() != "wiki") return@offMain null
        val lang = normalizeLang(parsed.host.substringBefore('.'))
        val title = parsed.pathSegments.drop(1).joinToString("/").replace('_', ' ')
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", title)
            .build()
        val extract = JSONObject(get(url.toString(), APP_UA))
            .optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0)
            ?.optString("extract").orEmpty().take(120_000)
        if (extract.isBlank()) return@offMain null
        selectPassages(extract, query, maxChars, ranker)
    }

    private fun readWikipedia(lang: String, title: String): ReadResult {
        val l = normalizeLang(lang)
        val url = "https://$l.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("titles", title.replace('_', ' '))
            .build()
        val page = JSONObject(get(url.toString(), APP_UA))
            .optJSONObject("query")?.optJSONArray("pages")?.optJSONObject(0)
        val realTitle = page?.optString("title") ?: title
        val text = page?.optString("extract").orEmpty()
            .replace(Regex("\n{3,}"), "\n\n")
            .ifBlank { "Error: article not found." }
        return ReadResult(realTitle, "https://$l.wikipedia.org/wiki/${realTitle.replace(' ', '_')}", text.take(MAX_PAGE_CHARS))
    }

    private fun readGeneric(url: String): ReadResult {
        val html = get(url, BROWSER_UA)
        val doc: Document = Jsoup.parse(html, url)
        val title = doc.title().ifBlank { url }
        doc.select("script, style, noscript, svg, nav, header, footer, aside, form, iframe, button, " +
            "[role=navigation], [aria-hidden=true], .cookie, .advert, .ads").remove()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body() ?: doc
        val sb = StringBuilder()
        for (el in root.select("h1, h2, h3, h4, p, li, pre, blockquote, td")) {
            // skip containers whose text is already captured by a child
            if (el.tagName() == "li" && el.selectFirst("p") != null) continue
            val t = el.text().trim()
            if (t.length < 2) continue
            when (el.tagName()) {
                "h1", "h2", "h3", "h4" -> sb.append("\n## ").append(t).append('\n')
                "li" -> sb.append("- ").append(t).append('\n')
                else -> sb.append(t).append('\n')
            }
            if (sb.length > MAX_PAGE_CHARS) break
        }
        val text = sb.toString().ifBlank { root.text() }.take(MAX_PAGE_CHARS)
        return ReadResult(title, url, text.ifBlank { "Error: no readable text on page." })
    }

    /** Raw page for deep-search extraction (structured data + text). */
    fun fetchDoc(url: String): Document = offMain {
        val req = Request.Builder().url(url).header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "fi-FI,fi;q=0.9,en;q=0.8").build()
        deepClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val type = resp.header("Content-Type").orEmpty()
            if (type.isNotEmpty() && !type.contains("html")) throw java.io.IOException("not a web page ($type)")
            // Cap at 3 MB; some shop pages are enormous.
            val bytes = resp.body?.source()?.let { src -> src.request(3L shl 20); src.buffer.readByteArray(minOf(src.buffer.size, 3L shl 20)) }
                ?: ByteArray(0)
            Jsoup.parse(java.io.ByteArrayInputStream(bytes), null, resp.request.url.toString()) // charset sniffed from <meta>
        }
    }

    private val deepClient by lazy {
        client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS).build()
    }

    // ---------------------------------------------------------------- currency

    @Volatile private var rates: Pair<Long, Map<String, Double>>? = null

    /**
     * ECB reference rates (1 EUR = x CUR), cached for 12 h. Lets eBay and other foreign
     * listings show up in euros instead of being dropped. Empty map if unreachable.
     */
    fun eurRates(): Map<String, Double> = offMain {
        val now = System.currentTimeMillis()
        rates?.takeIf { now - it.first < 12 * 3_600_000L }?.let { return@offMain it.second }
        val xml = runCatching { get("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml") }.getOrNull()
            ?: return@offMain rates?.second.orEmpty()
        val map = Regex("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]").findAll(xml)
            .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.let { m.groupValues[1] to it } }
            .toMap()
        if (map.isNotEmpty()) rates = now to map
        map
    }

    // ---------------------------------------------------------------- http

    private fun get(url: String, ua: String = APP_UA): String {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept-Language", "fi-FI,fi;q=0.9,en;q=0.8").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} from ${resp.request.url.host}")
            return resp.body?.string().orEmpty()
        }
    }

    /** "server:8080" -> "http://server:8080"; strips a trailing "/search" or slash. */
    private fun normalizeSearxUrl(raw: String): String? {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.removeSuffix("/search").trimEnd('/')
    }

    private fun normalizeLang(lang: String) =
        lang.trim().lowercase().take(3).filter { it.isLetter() }.ifEmpty { "en" }

    companion object {
        internal val STOP = setOf(
            "the", "and", "for", "with", "what", "when", "where", "which", "who", "how", "why", "did", "does",
            "was", "were", "are", "is", "get", "got", "from", "that", "this", "about", "into", "have", "has",
            "mikä", "mitä", "milloin", "missä", "miten", "miksi", "kuka", "onko", "oli", "ovat", "sekä", "kanssa",
        )
        private val YEAR = Regex("\\b(1[0-9]{3}|20[0-9]{2})\\b")
        private val HEADING = Regex("^=+\\s*(.+?)\\s*=+$")

        /**
         * Pure function so it can be unit tested without the network.
         * Keyword stems pick up to [RERANK] candidates; if a [ranker] is given (EmbeddingGemma),
         * those candidates are re-ordered by meaning, which finds "Mauritania abolished it in
         * 1981" even when a paragraph about modern slavery shares more words with the query.
         */
        fun selectPassages(extract: String, query: String, maxChars: Int, ranker: Ranker? = null): String? {
            val stems = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 && it !in STOP }
                .map { it.take(5) }
                .distinct()

            data class Para(val index: Int, val section: String, val text: String, val score: Double)

            var section = ""
            val paras = mutableListOf<Para>()
            for (line in extract.lines()) {
                val t = line.trim()
                if (t.isEmpty()) continue
                val h = HEADING.matchEntire(t)
                if (h != null) { section = h.groupValues[1]; continue }
                if (t.length < 40) continue
                val low = t.lowercase()
                val hits = stems.count { low.contains(it) }
                // Dates and numbers are what short answers usually lack.
                val years = YEAR.findAll(t).count().coerceAtMost(4)
                val score = hits * 2.0 + years * 0.5 + if (section.isEmpty()) 0.5 else 0.0
                paras += Para(paras.size, section, t, score)
            }
            if (paras.isEmpty()) return null

            val lead = paras.first()
            val chosen = mutableListOf(lead.copy(text = lead.text.take(450)))
            var budget = maxChars - chosen.first().text.length
            val byKeyword = paras.drop(1).filter { it.score >= 2.0 }.sortedByDescending { it.score }
            val candidates = byKeyword.take(RERANK)
            val sims = ranker?.let { r -> runCatching { r(query, candidates.map { it.text.take(600) }) }.getOrNull() }
            val ordered = if (sims != null && sims.size == candidates.size) {
                candidates.indices.sortedByDescending { i ->
                    sims[i] + 0.02f * YEAR.findAll(candidates[i].text).count().coerceAtMost(3)
                }.map { candidates[it] }
            } else byKeyword
            for (p in ordered) {
                val text = if (p.text.length > 700) p.text.take(700) + "…" else p.text
                if (text.length > budget) continue
                chosen += p.copy(text = text)
                budget -= text.length
                if (budget < 150) break
            }
            return chosen.sortedBy { it.index }.joinToString("\n") { p ->
                if (p.section.isEmpty()) p.text else "(${p.section}) ${p.text}"
            }
        }

        /** How many keyword candidates get embedded; ~1 s on the CPU for 270M. */
        const val RERANK = 16
        const val MAX_HITS = 5
        const val SNIPPET = 300
        const val MAX_PAGE_CHARS = 6000
        const val APP_UA = "Haku/0.1 (https://github.com/Lepune/haku; on-device search assistant)"
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
