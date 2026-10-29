package fi.lepune.haku.search

import fi.lepune.haku.tools.WebTools
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One second-hand listing from the self-hosted Tori index. */
data class ToriItem(
    val id: String,
    val title: String,
    val price: Double?,
    val city: String?,
    val url: String,
    val publishedAt: String?,
    /** price / median price of similar listings; < 1 = cheaper than usual. */
    val dealRatio: Double?,
    val sold: Boolean = false,
    /** Close in meaning but not the asked model (e.g. GTX 1660 for "RTX 3060"). */
    val similar: Boolean = false,
    /** A whole computer that contains the asked part ("Pelikone RTX 3060 | i5…"). */
    val inComputer: Boolean = false,
) {
    /** "−42 %" style discount vs similar listings, only when it's meaningfully cheaper. */
    val dealPercent: Int? get() = dealRatio?.takeIf { it in 0.0..0.85 }?.let { ((1 - it) * 100).toInt() }
}

/**
 * Client for a self-hosted Tori listing index (see the server's API: /search, /hunts, /healthz).
 * Parsing is lenient: a bare array or an object wrapping it under results/items/listings/hits.
 */
class ToriIndex(private val web: WebTools, private val baseUrl: () -> String) {

    val enabled: Boolean get() = base() != null

    fun search(query: String, maxPrice: Double? = null, limit: Int = 8): List<ToriItem> {
        val b = base() ?: return emptyList()
        val url = "$b/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .apply { maxPrice?.let { addQueryParameter("max_price", it.toString()) } }
            .build()
        return items(web.httpGet(url.toString())).filterNot { it.sold }
    }

    /** Saves a semantic hunt on the server; returns its id. */
    fun createHunt(query: String, maxPrice: Double? = null): Long {
        val b = base() ?: error("Tori index URL not set")
        val body = JSONObject().put("query", query).apply { maxPrice?.let { put("max_price", it) } }
        val o = JSONObject(web.httpPostJson("$b/hunts", body.toString()))
        val id = o.optLong("id", o.optLong("hunt_id", -1))
        check(id >= 0) { "Server did not return a hunt id" }
        return id
    }

    /** Hits the server hasn't returned before for this hunt. */
    fun newHits(huntId: Long): List<ToriItem> {
        val b = base() ?: return emptyList()
        return items(web.httpGet("$b/hunts/$huntId/hits?unseen=true"))
    }

    /** Settings "Test" button. */
    fun test(rawUrl: String): String {
        val b = normalize(rawUrl) ?: return "Enter a URL like https://tori.example.lan"
        return runCatching {
            val o = JSONObject(web.httpGet("$b/healthz"))
            val status = o.optString("status", "?")
            val n = o.optInt("listings", -1)
            val queue = o.opt("queue")?.toString()
            "Status $status · ${if (n >= 0) "$n listings" else "listings ?"}" + (queue?.let { " · queue $it" } ?: "")
        }.getOrElse { t ->
            val m = t.message.orEmpty()
            when {
                t is javax.net.ssl.SSLException || "CertPath" in m -> "Certificate not trusted: install the server's root CA."
                t is java.net.UnknownHostException -> "Host not found: is the phone using your Pi-hole/Tailscale DNS?"
                else -> "Failed: ${m.ifBlank { t.javaClass.simpleName }}"
            }
        }
    }

    private fun base(): String? = normalize(baseUrl())

    private fun normalize(raw: String): String? {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) return null
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u
    }

    private fun items(body: String): List<ToriItem> {
        val t = body.trim()
        val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else {
            val o = JSONObject(t)
            listOf("results", "items", "listings", "hits").firstNotNullOfOrNull { o.optJSONArray(it) } ?: JSONArray()
        }
        return (0 until arr.length()).mapNotNull { i ->
            val raw = arr.optJSONObject(i) ?: return@mapNotNull null
            // Hunt hits may wrap the listing: {"listing": {...}, "score": 0.8}
            val o = raw.optJSONObject("listing") ?: raw
            val id = o.opt("id")?.toString() ?: o.opt("listing_id")?.toString() ?: return@mapNotNull null
            val url = o.optString("url").ifBlank { "https://www.tori.fi/recommerce/forsale/item/$id" }
            ToriItem(
                id = id,
                title = o.optString("title").ifBlank { "(no title)" },
                price = o.optDouble("price").takeUnless { it.isNaN() },
                city = o.optString("city").ifBlank { null }?.takeUnless { it == "null" },
                url = url,
                publishedAt = o.optString("published_at").ifBlank { null }?.takeUnless { it == "null" },
                dealRatio = o.optDouble("deal_ratio").takeUnless { it.isNaN() },
                sold = o.optBoolean("sold", false),
            )
        }
    }
}
