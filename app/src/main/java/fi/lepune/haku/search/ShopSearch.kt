package fi.lepune.haku.search

import android.os.SystemClock
import fi.lepune.haku.tools.Hit
import fi.lepune.haku.tools.PageExtractor
import fi.lepune.haku.tools.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.ceil

/**
 * Price search without any language model:
 * search engines + Finnish price comparison sites → open result pages in parallel →
 * read schema.org offers → keep only matching products → sort by price.
 */
class ShopSearch(
    private val web: WebTools,
    /** Off-screen browser for JavaScript-only shops; null in background jobs. */
    private val renderer: (suspend (String) -> org.jsoup.nodes.Document?)? = null,
) {

    private data class Page(val hit: Hit, val offers: List<PageExtractor.RawOffer>, val finnish: Boolean)

    /**
     * [partial] receives the first list as soon as plain pages are read; the final result adds
     * shops that needed the off-screen browser.
     */
    suspend fun run(
        question: String,
        step: (String) -> Unit,
        partial: (ShopResult) -> Unit = {},
    ): ShopResult {
        val t0 = SystemClock.elapsedRealtime()
        val terms = Intent.productTerms(question)
        val want = Want(Intent.dims(question).map(::dimKey), Intent.stems(terms), Intent.modelCodes(terms))
        val used = Intent.isUsed(question)

        // 1. searches in parallel. Used/niche hardware isn't in Finnish retail: add second-hand markets.
        val queries = if (used) listOf(
            "$terms käytetty",
            "site:tori.fi $terms",
            "site:huuto.net $terms",
            "$terms used ebay",
            "$terms hinta",
        ) else listOf(
            "$terms hinta",
            "site:hinta.fi $terms",
            "site:hintaopas.fi $terms",
            "$terms verkkokauppa",
        )
        step("Searching: " + queries.joinToString(" · "))
        val sem = Semaphore(3)
        val (hitLists, rates) = coroutineScope {
            // Foreign listings (eBay in USD/GBP) are converted to euros instead of dropped.
            val fx = async(Dispatchers.IO) { runCatching { web.eurRates() }.getOrDefault(emptyMap()) }
            val lists = queries.map { q ->
                async(Dispatchers.IO) {
                    sem.withPermit { runCatching { web.webSearch(q, limit = 8) }.getOrDefault(emptyList()) }
                }
            }.awaitAll()
            lists to fx.await()
        }
        val hits = interleave(hitLists)
            .filterNot { h -> SKIP.any { host(h.url).endsWith(it) } }
            .distinctBy { it.url.substringBefore('#').trimEnd('/') }
            .take(MAX_PAGES)
        val tSearch = SystemClock.elapsedRealtime()

        // 2. plain download of every page in parallel
        step("Reading ${hits.size} pages")
        val pageSem = Semaphore(MAX_PAGES)
        val fetched = coroutineScope {
            hits.map { h ->
                async(Dispatchers.IO) {
                    pageSem.withPermit {
                        withTimeoutOrNull(7_000) {
                            runCatching {
                                val doc = web.fetchDoc(h.url)
                                Page(h, PageExtractor.offers(doc), PageExtractor.isFinnishCompany(doc))
                            }.getOrNull()
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val tRead = SystemClock.elapsedRealtime()
        fun sec(ms: Long) = "%.1f".format(ms / 1000.0)

        val first = assemble(fetched, hits, want, rates)
        val baseSummary = "search ${sec(tSearch - t0)} s · read ${sec(tRead - tSearch)} s"

        // 3. pages that look right but gave no price: render them like a browser would
        val pricedUrls = fetched.filter { p -> p.offers.any { matches(it.name, want) } }.map { it.hit.url }.toSet()
        val toRender = if (renderer == null) emptyList() else hits.filter { h ->
            h.url !in pricedUrls && host(h.url) !in COMPARISON && matches(h.title + " " + h.snippet, want)
        }.take(MAX_RENDER)
        if (toRender.isEmpty()) {
            return ShopResult(first, "${first.size} offers from ${fetched.size} pages · $baseSummary")
        }
        partial(ShopResult(first, "${first.size} offers so far · rendering ${toRender.size} more…"))
        step("Rendering ${toRender.size} JavaScript pages: " + toRender.joinToString(", ") { host(it.url) })

        val rendered = withTimeoutOrNull(RENDER_BUDGET_MS) {
            coroutineScope {
                toRender.map { h ->
                    async {
                        val doc = renderer!!.invoke(h.url) ?: return@async null
                        val offer = PageExtractor.visibleOffer(doc) ?: return@async null
                        Page(h, listOf(offer), PageExtractor.isFinnishCompany(doc))
                    }
                }.awaitAll().filterNotNull()
            }
        }.orEmpty()
        val tRender = SystemClock.elapsedRealtime()

        val renderedUrls = rendered.map { it.hit.url }.toSet()
        val all = fetched.filterNot { it.hit.url in renderedUrls } + rendered
        val merged = assemble(all, hits, want, rates)
        return ShopResult(
            merged,
            "${merged.size} offers from ${all.size} pages · $baseSummary · render ${sec(tRender - tRead)} s (${rendered.size}/${toRender.size})",
        )
    }

    private fun assemble(pages: List<Page>, hits: List<Hit>, want: Want, rates: Map<String, Double>): List<Offer> {
        val offers = mutableListOf<Offer>()
        for (p in pages) {
            val host = host(p.hit.url)
            for (o in p.offers) {
                val cur = o.currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "EUR" }
                // 1 EUR = rate CUR; unknown currency -> skip rather than show a wrong number.
                val rate = if (cur == null) 1.0 else rates[cur] ?: continue
                if (!matches(o.name, want)) continue
                // A shop page often names itself as the seller ("Cropper" on cropper.fi) — that's not a third party.
                val thirdParty = o.seller != null && !sameBrand(o.seller, host)
                offers += Offer(
                    name = o.name, price = o.price / rate, shop = if (thirdParty) o.seller!! else host, url = p.hit.url,
                    inStock = o.inStock,
                    finnish = p.finnish && !thirdParty, // a seller listed on a comparison site: origin unknown
                    via = if (thirdParty) host else null, from = o.isLowest, shipping = o.shipping?.div(rate),
                    fx = cur,
                )
            }
        }
        // Pages without a usable price: fall back to a price shown in the search snippet.
        val pagesWithOffers = pages.filter { it.offers.isNotEmpty() }.map { it.hit.url }.toSet()
        for (h in hits) {
            if (h.url in pagesWithOffers) continue
            val text = h.title + " " + h.snippet
            if (!matches(text, want)) continue
            val price = SNIPPET_PRICE.find(text)?.groupValues?.get(1)?.let(::parseEuro) ?: continue
            offers += Offer(h.title, price, host(h.url), h.url, null, finnish = false, fromSnippet = true)
        }
        return offers
            .filter { it.price in 1.0..1_000_000.0 }
            .sortedByDescending { it.finnish } // keep the FI-badged copy when deduplicating
            .distinctBy { brand(it.shop) + "|" + "%.2f".format(it.price) }
            .sortedBy { it.price }
            .take(MAX_ROWS)
    }

    // ------------------------------------------------------------------ matching

    private data class Want(val dims: List<String>, val stems: Set<String>, val codes: List<String>)

    private fun matches(name: String, want: Want): Boolean {
        if (want.dims.isNotEmpty()) {
            val have = Intent.dims(name).map(::dimKey).toSet()
            if (!want.dims.all { it in have }) return false
            return true // exact dimensions are a strong enough match (names are often in English)
        }
        val nonDimStems = want.stems.filter { s -> s.none { it.isDigit() } }
        val haveStems = Intent.stems(name)
        if (want.codes.isNotEmpty()) {
            // A model code ("mi100", "rtx5070") identifies the product; every one must be in the
            // name ("AMD Instinct MI-100 32GB" matches). Other words only need a loose overlap,
            // since listings rarely repeat "card" or "graphics".
            val c = Intent.compact(name)
            if (!want.codes.all { it in c }) return false
            val need = nonDimStems.size / 3
            return nonDimStems.count { s -> haveStems.any { it.startsWith(s.take(4)) || s.startsWith(it.take(4)) } } >= need
        }
        if (nonDimStems.isEmpty()) return true
        val need = ceil(nonDimStems.size / 2.0).toInt()
        return nonDimStems.count { s -> haveStems.any { it.startsWith(s.take(4)) || s.startsWith(it.take(4)) } } >= need
    }

    /** Order-independent key so "120x120x200" matches "200x120x120". */
    private fun dimKey(d: String) = d.split('x').mapNotNull { it.toDoubleOrNull() }.sorted().joinToString("x")

    /** "Cropper", "cropper.fi", "www.Cropper.com" -> "cropper" */
    private fun brand(s: String) = s.lowercase().removePrefix("www.")
        .substringBefore('.').filter { it.isLetterOrDigit() }

    private fun sameBrand(seller: String, host: String): Boolean {
        val a = brand(seller.replace(" ", ""))
        val b = brand(host)
        return a.isNotEmpty() && (a == b || a.startsWith(b) || b.startsWith(a))
    }

    private fun interleave(lists: List<List<Hit>>): List<Hit> {
        val out = mutableListOf<Hit>()
        val depth = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until depth) for (l in lists) l.getOrNull(i)?.let(out::add)
        return out
    }

    private fun parseEuro(s: String): Double? =
        s.replace(Regex("[\\s ]"), "").replace(".", "").replace(',', '.').toDoubleOrNull()

    private fun host(url: String) = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url

    companion object {
        private const val MAX_PAGES = 12
        private const val MAX_ROWS = 15
        private const val MAX_RENDER = 4
        private const val RENDER_BUDGET_MS = 12_000L
        /** Price-comparison sites: their lists are already read from structured data. */
        private val COMPARISON = setOf("hinta.fi", "hintaopas.fi", "hintaseuranta.fi", "pricerunner.com")
        private val SNIPPET_PRICE = Regex("""(\d{1,3}(?:[  .]\d{3})*,\d{2})\s?€""")
        private val SKIP = listOf(
            "youtube.com", "facebook.com", "instagram.com", "tiktok.com", "reddit.com", "wikipedia.org",
            "pinterest.com", "x.com", "twitter.com", "duckduckgo.com",
        )
    }
}
