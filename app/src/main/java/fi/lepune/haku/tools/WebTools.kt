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
        val searx = settings().searxngUrl.trim().trimEnd('/')
        if (searx.isNotEmpty()) searxng(searx, query, limit)
        else duckDuckGo(query, limit).ifEmpty { duckDuckGoLite(query, limit) }
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

    // ---------------------------------------------------------------- http

    private fun get(url: String, ua: String = APP_UA): String {
        val req = Request.Builder().url(url).header("User-Agent", ua)
            .header("Accept-Language", "fi-FI,fi;q=0.9,en;q=0.8").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} from ${resp.request.url.host}")
            return resp.body?.string().orEmpty()
        }
    }

    private fun normalizeLang(lang: String) =
        lang.trim().lowercase().take(3).filter { it.isLetter() }.ifEmpty { "en" }

    companion object {
        const val MAX_HITS = 5
        const val SNIPPET = 300
        const val MAX_PAGE_CHARS = 6000
        const val APP_UA = "Haku/0.1 (https://github.com/Lepune/haku; on-device search assistant)"
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 15; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
