package fi.lepune.haku.search

import fi.lepune.haku.tools.Hit

/** One row in the price list. */
data class Offer(
    val name: String,
    val price: Double,
    val shop: String,
    val url: String,
    val inStock: Boolean?,
    /** Page shows a Finnish business ID (Y-tunnus). */
    val finnish: Boolean,
    /** Comparison site that listed it, when the shop itself wasn't opened. */
    val via: String? = null,
    /** "from" price (lowest of several) rather than an exact offer. */
    val from: Boolean = false,
    /** Price read from a search snippet only — less reliable. */
    val fromSnippet: Boolean = false,
)

data class ShopResult(val offers: List<Offer>, val summary: String)

data class WebResult(val results: List<Hit>, val instant: Hit?, val summary: String)
