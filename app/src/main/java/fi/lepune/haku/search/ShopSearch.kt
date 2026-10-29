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
class ShopSearch(private val web: WebTools) {

    suspend fun run(question: String, step: (String) -> Unit): ShopResult {
        val t0 = SystemClock.elapsedRealtime()
        val terms = Intent.productTerms(question)
        val wantDims = Intent.dims(question).map(::dimKey)
        val wantStems = Intent.stems(terms)

        // 1. searches in parallel
        val queries = listOf(
            "$terms hinta",
            "site:hinta.fi $terms",
            "site:hintaopas.fi $terms",
            "$terms verkkokauppa",
        )
        step("Searching: " + queries.joinToString(" · "))
        val sem = Semaphore(3)
        val hitLists = coroutineScope {
            queries.map { q ->
                async(Dispatchers.IO) {
                    sem.withPermit { runCatching { web.webSearch(q, limit = 8) }.getOrDefault(emptyList()) }
                }
            }.awaitAll()
        }
        val hits = interleave(hitLists)
            .filterNot { h -> SKIP.any { host(h.url).endsWith(it) } }
            .distinctBy { it.url.substringBefore('#').trimEnd('/') }
            .take(MAX_PAGES)
        val tSearch = SystemClock.elapsedRealtime()

        // 2. open pages in parallel and read their offers
        step("Reading ${hits.size} pages")
        val pageSem = Semaphore(MAX_PAGES)
        data class Page(val hit: Hit, val offers: List<PageExtractor.RawOffer>, val finnish: Boolean)
        val pages = coroutineScope {
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

        // 3. match, normalise, sort
        val offers = mutableListOf<Offer>()
        for (p in pages) {
            val host = host(p.hit.url)
            for (o in p.offers) {
                if (o.currency != null && !o.currency.equals("EUR", true)) continue
                if (!matches(o.name, wantDims, wantStems)) continue
                // A shop page often names itself as the seller ("Cropper" on cropper.fi) — that's not a third party.
                val thirdParty = o.seller != null && !sameBrand(o.seller, host)
                offers += Offer(
                    name = o.name, price = o.price, shop = if (thirdParty) o.seller!! else host, url = p.hit.url,
                    inStock = o.inStock,
                    finnish = p.finnish && !thirdParty, // a seller listed on a comparison site: origin unknown
                    via = if (thirdParty) host else null, from = o.isLowest,
                )
            }
        }
        // Pages we couldn't read (or without structured data): try the price in the search snippet.
        val pagesWithOffers = pages.filter { it.offers.isNotEmpty() }.map { it.hit.url }.toSet()
        for (h in hits) {
            if (h.url in pagesWithOffers) continue
            val text = h.title + " " + h.snippet
            if (!matches(text, wantDims, wantStems)) continue
            val price = SNIPPET_PRICE.find(text)?.groupValues?.get(1)?.let(::parseEuro) ?: continue
            offers += Offer(h.title, price, host(h.url), h.url, null, finnish = false, fromSnippet = true)
        }

        val sorted = offers
            .filter { it.price in 1.0..1_000_000.0 }
            .sortedByDescending { it.finnish } // keep the FI-badged copy when deduplicating
            .distinctBy { brand(it.shop) + "|" + "%.2f".format(it.price) }
            .sortedBy { it.price }
            .take(MAX_ROWS)

        fun sec(ms: Long) = "%.1f".format(ms / 1000.0)
        val summary = "${sorted.size} offers from ${pages.size} pages · " +
            "search ${sec(tSearch - t0)} s · read ${sec(tRead - tSearch)} s"
        return ShopResult(sorted, summary)
    }

    // ------------------------------------------------------------------ matching

    private fun matches(name: String, wantDims: List<String>, wantStems: Set<String>): Boolean {
        if (wantDims.isNotEmpty()) {
            val have = Intent.dims(name).map(::dimKey).toSet()
            if (!wantDims.all { it in have }) return false
            return true // exact dimensions are a strong enough match (names are often in English)
        }
        val nonDimStems = wantStems.filter { s -> s.none { it.isDigit() } }
        if (nonDimStems.isEmpty()) return true
        val haveStems = Intent.stems(name)
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
        private val SNIPPET_PRICE = Regex("""(\d{1,3}(?:[  .]\d{3})*,\d{2})\s?€""")
        private val SKIP = listOf(
            "youtube.com", "facebook.com", "instagram.com", "tiktok.com", "reddit.com", "wikipedia.org",
            "pinterest.com", "x.com", "twitter.com", "duckduckgo.com",
        )
    }
}
