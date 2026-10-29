package fi.lepune.haku.tools

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Pulls the useful bits out of a page so the small model only sees evidence:
 *  - products from schema.org JSON-LD / meta tags (name, price, stock) — most shops embed this
 *    even when the visible page is rendered with JavaScript
 *  - the few text passages that best match the question
 */
object PageExtractor {

    data class Product(
        val name: String,
        val price: String?,
        val currency: String?,
        val availability: String?,
    ) {
        fun line(): String = buildString {
            append(name.take(120))
            if (price != null) append(" — ").append(price).append(' ').append(currency ?: "")
            if (availability != null) append(" (").append(availability).append(')')
        }
    }

    // ------------------------------------------------------------------ products

    fun products(doc: Document, max: Int = 6): List<Product> {
        val out = mutableListOf<Product>()
        for (script in doc.select("script[type=\"application/ld+json\"]")) {
            val raw = script.data().trim()
            if (raw.isEmpty()) continue
            val parsed = runCatching {
                if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
            }.getOrNull() ?: continue
            walk(parsed, out, depth = 0)
            if (out.size >= max) break
        }
        if (out.isEmpty()) metaProduct(doc)?.let { out += it }
        return out.distinctBy { it.name + it.price }.take(max)
    }

    private fun walk(node: Any?, out: MutableList<Product>, depth: Int) {
        if (depth > 6 || out.size >= 12) return
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out, depth + 1)
            is JSONObject -> {
                if (isType(node, "Product")) productFrom(node)?.let { out += it }
                // Common containers: @graph, ItemList.itemListElement[].item, mainEntity
                for (key in listOf("@graph", "itemListElement", "item", "mainEntity", "hasVariant")) {
                    if (node.has(key)) walk(node.opt(key), out, depth + 1)
                }
            }
        }
    }

    private fun isType(o: JSONObject, type: String): Boolean = when (val t = o.opt("@type")) {
        is String -> t.equals(type, true) || t.endsWith("/$type", true)
        is JSONArray -> (0 until t.length()).any { t.optString(it).equals(type, true) }
        else -> false
    }

    private fun productFrom(o: JSONObject): Product? {
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        val offers = o.opt("offers")
        val offer: JSONObject? = when (offers) {
            is JSONObject -> offers
            is JSONArray -> (0 until offers.length()).mapNotNull { offers.optJSONObject(it) }
                .minByOrNull { priceNumber(it.optString("price")) ?: Double.MAX_VALUE }
            else -> null
        }
        var price: String? = null
        var currency: String? = null
        var availability: String? = null
        if (offer != null) {
            val p = offer.optString("price").ifBlank { offer.optJSONObject("priceSpecification")?.optString("price").orEmpty() }
            val low = offer.optString("lowPrice")
            val high = offer.optString("highPrice")
            price = when {
                p.isNotBlank() -> p
                low.isNotBlank() && high.isNotBlank() && low != high -> "$low–$high"
                low.isNotBlank() -> "from $low"
                else -> null
            }
            currency = offer.optString("priceCurrency").ifBlank {
                offer.optJSONObject("priceSpecification")?.optString("priceCurrency").orEmpty()
            }.ifBlank { null }
            availability = offer.optString("availability").substringAfterLast('/').ifBlank { null }
            val count = offer.optInt("offerCount", 0)
            if (count > 1 && availability == null) availability = "$count offers"
        }
        return Product(name.trim(), price, currency, availability)
    }

    private fun metaProduct(doc: Document): Product? {
        val price = doc.selectFirst("meta[property=\"product:price:amount\"], meta[property=\"og:price:amount\"], [itemprop=price]")
            ?.let { it.attr("content").ifBlank { it.text() } }?.trim()?.ifBlank { null } ?: return null
        val currency = doc.selectFirst("meta[property=\"product:price:currency\"], meta[property=\"og:price:currency\"], [itemprop=priceCurrency]")
            ?.attr("content")?.ifBlank { null }
        val name = doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.ifBlank { null } ?: doc.title()
        return Product(name, price, currency, null)
    }

    private fun priceNumber(s: String): Double? = s.replace(",", ".").filter { it.isDigit() || it == '.' }.toDoubleOrNull()

    // ------------------------------------------------------------------ text

    private val PRICE = Regex("""(\d[\d  .]*([.,]\d{1,2})?\s?(€|eur\b|EUR\b))|(€\s?\d)""", RegexOption.IGNORE_CASE)
    private val DIMENSION = Regex("""\d+\s?[x×]\s?\d+""")

    /**
     * Split the readable part of a page into ~[target]-char chunks for embedding.
     * Adjacent short blocks are merged so each chunk carries some context.
     */
    fun chunks(doc: Document, target: Int = 450, maxChunks: Int = 60): List<String> {
        val clean = doc.clone()
        clean.select("script, style, noscript, svg, nav, header, footer, aside, form, iframe, button, " +
            "[role=navigation], [aria-hidden=true]").remove()
        val root = clean.selectFirst("article") ?: clean.selectFirst("main") ?: clean.body() ?: return emptyList()
        val blocks = root.select("h1, h2, h3, h4, p, li, td, dd, dt, blockquote, [class*=price], [class*=hinta]")
            .asSequence()
            .map { it.text().trim() }
            .filter { it.length >= 15 }
            .distinct()
            .toList()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (b in blocks) {
            val piece = if (b.length > target * 2) b.take(target * 2) else b
            if (cur.isNotEmpty() && cur.length + piece.length > target) {
                out += cur.toString(); cur.clear()
                if (out.size >= maxChunks) return out
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(piece)
        }
        if (cur.isNotEmpty() && out.size < maxChunks) out += cur.toString()
        return out
    }

    fun hasPrice(text: String) = PRICE.containsMatchIn(text)

    /** Best-matching passages for the question, in page order, up to [maxChars]. */
    fun passages(doc: Document, question: String, maxChars: Int = 900): String {
        val clean = doc.clone()
        clean.select("script, style, noscript, svg, nav, header, footer, aside, form, iframe, button, " +
            "[role=navigation], [aria-hidden=true]").remove()
        val root = clean.selectFirst("article") ?: clean.selectFirst("main") ?: clean.body() ?: return ""

        val stems = question.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 }
            .map { it.take(5) }
            .distinct()

        val blocks = root.select("h1, h2, h3, p, li, td, dd, [class*=price], [class*=hinta]")
            .map { it.text().trim() }
            .filter { it.length in 15..600 }
            .distinct()

        val scored = blocks.mapIndexed { i, text ->
            val low = text.lowercase()
            var score = stems.count { low.contains(it) }.toDouble()
            if (PRICE.containsMatchIn(text)) score += 1.5
            if (DIMENSION.containsMatchIn(text)) score += 0.5
            Triple(i, text, score)
        }.filter { it.third > 0 }

        val picked = scored.sortedByDescending { it.third }
            .let { top ->
                val chosen = mutableListOf<Triple<Int, String, Double>>()
                var len = 0
                for (t in top) {
                    if (len + t.second.length > maxChars) continue
                    chosen += t; len += t.second.length + 1
                }
                chosen
            }
            .sortedBy { it.first }

        return if (picked.isNotEmpty()) picked.joinToString("\n") { it.second }
        else root.text().take(maxChars / 2)
    }
}
