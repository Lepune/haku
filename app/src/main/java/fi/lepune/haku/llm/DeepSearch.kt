package fi.lepune.haku.llm

import fi.lepune.haku.tools.Hit
import fi.lepune.haku.tools.PageExtractor
import fi.lepune.haku.tools.WebTools
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * App-driven research. The small model only does what it is good at:
 *   1. turn the question into search queries
 *   2. write the answer from a compact evidence pack
 * Searching, choosing pages, reading and extracting are plain code.
 */
class DeepSearch(
    private val llm: LlmEngine,
    private val web: WebTools,
    /** Returns a ready embedder, or null to fall back to keyword ranking. */
    private val embedder: suspend () -> Embedder?,
) {

    sealed interface Event {
        data class Step(val label: String) : Event
        data class Searched(val hits: List<Hit>) : Event
        data class Read(val hit: Hit) : Event
        data class Token(val text: String) : Event
    }

    data class Source(val n: Int, val title: String, val url: String)

    private data class Scored(val index: Int, val text: String, val score: Float)

    private data class PageRead(
        val hit: Hit,
        val products: List<PageExtractor.Product>,
        val chunks: List<Scored>,
        val keywordText: String,
    )

    /** Result of a deep search: numbered sources plus a per-phase timing summary. */
    data class Result(val sources: List<Source>, val timings: String)

    suspend fun run(question: String, emit: (Event) -> Unit): Result {
        val t0 = SystemClock.elapsedRealtime()

        // 1+2. Search the raw question immediately while Gemma plans extra queries in parallel.
        val searchSem = Semaphore(2)
        suspend fun search(q: String): List<Hit> = searchSem.withPermit {
            emit(Event.Step("Searching: $q"))
            runCatching { withContext(Dispatchers.IO) { web.webSearch(q, limit = 8) } }
                .onFailure { emit(Event.Step("⚠ search failed: ${it.message}")) }
                .getOrDefault(emptyList())
                .also { if (it.isNotEmpty()) emit(Event.Searched(it)) }
        }
        val base = question.trim().take(120)
        val (plan, firstHits) = coroutineScope {
            val first = async { search(base) }
            val p = async { plan(question) }
            p.await() to first.await()
        }
        val extra = plan.queries.filterNot { it.equals(base, ignoreCase = true) }.take(MAX_EXTRA_QUERIES)
        if (extra.isNotEmpty()) emit(Event.Step("More queries: " + extra.joinToString(" · ")))
        val more = coroutineScope { extra.map { q -> async { search(q) } }.awaitAll() }
        val perQuery = listOf(firstHits) + more
        val tSearch = SystemClock.elapsedRealtime()

        // 3. choose pages: round-robin across queries, dedupe, max 2 per site
        val pages = pickPages(perQuery, MAX_PAGES)
        emit(Event.Step("Reading ${pages.size} pages"))

        // 4. read + extract in parallel. Each page's chunks are embedded as soon as it arrives,
        //    so ranking (CPU) overlaps with downloading (network).
        val emb = embedder()
        val qVec = emb?.let { e -> runCatching { withContext(Dispatchers.IO) { e.embedQuery(question) } }.getOrNull() }
        if (qVec == null) emit(Event.Step("Keyword ranking" + (emb?.lastError?.let { " (embedder: $it)" } ?: " (no embedding model)")))
        val sem = Semaphore(MAX_PAGES)
        val pagesRead = coroutineScope {
            pages.map { hit ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                            runCatching {
                                val doc = web.fetchDoc(hit.url)
                                val products = PageExtractor.products(doc)
                                val title = doc.title().ifBlank { hit.title }
                                val scored: List<Scored> = if (qVec != null && emb != null) {
                                    runCatching {
                                        val chunks = PageExtractor.chunks(doc)
                                        val vecs = if (chunks.isEmpty()) emptyList() else emb.embedDocs(chunks)
                                        chunks.mapIndexed { i, c ->
                                            val bonus = if (PageExtractor.hasPrice(c)) PRICE_BONUS else 0f
                                            Scored(i, c, Embedder.cosine(qVec, vecs[i]) + bonus)
                                        }
                                    }.getOrElse { emptyList() }
                                } else emptyList()
                                val keywordText = if (scored.isEmpty()) PageExtractor.passages(doc, question, maxChars = 900) else ""
                                emit(Event.Read(Hit(title, hit.url, "")))
                                PageRead(Hit(title, hit.url, hit.snippet), products, scored, keywordText)
                            }.getOrElse {
                                emit(Event.Step("⚠ ${host(hit.url)}: ${it.message ?: "unreadable"}"))
                                null
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Semantic selection: best chunks across ALL pages, within a character budget.
        val evidence: List<Triple<Hit, List<PageExtractor.Product>, String>> = if (qVec != null) {
            val all = pagesRead.flatMapIndexed { p, r -> r.chunks.map { p to it } }
            val chosen = mutableMapOf<Int, MutableList<Scored>>()
            var budget = TEXT_BUDGET
            for ((p, sc) in all.sortedByDescending { it.second.score }) {
                if (sc.score < MIN_SCORE) break
                val list = chosen.getOrPut(p) { mutableListOf() }
                if (list.size >= MAX_CHUNKS_PER_PAGE || sc.text.length > budget) continue
                list += sc; budget -= sc.text.length
                if (budget < 200) break
            }
            emit(Event.Step("Ranked ${all.size} passages by meaning, kept ${chosen.values.sumOf { it.size }}"))
            pagesRead.mapIndexed { p, r ->
                val best = chosen[p].orEmpty()
                Triple(p, best.maxOfOrNull { it.score } ?: -1f, r to best.sortedBy { it.index }.joinToString("\n") { it.text })
            }
                .filter { (_, _, pr) -> pr.first.chunks.isNotEmpty() && (pr.second.isNotBlank() || pr.first.products.isNotEmpty()) }
                .sortedByDescending { it.second }
                .map { (_, _, pr) -> Triple(pr.first.hit, pr.first.products, pr.second) }
                // Pages whose embedding failed still contribute their keyword passages.
                .plus(pagesRead.filter { it.chunks.isEmpty() && (it.keywordText.isNotBlank() || it.products.isNotEmpty()) }
                    .map { Triple(it.hit, it.products, it.keywordText) })
        } else {
            pagesRead.map { Triple(it.hit, it.products, it.keywordText) }
        }

        val tRead = SystemClock.elapsedRealtime()

        // Keep search snippets for pages that failed to load — still better than nothing.
        val readUrls = pagesRead.map { it.hit.url }.toSet()
        val snippetOnly = pages.filter { it.url !in readUrls && it.snippet.isNotBlank() }

        val sources = mutableListOf<Source>()
        val pack = StringBuilder()
        for ((hit, products, text) in evidence) {
            val n = sources.size + 1
            sources += Source(n, hit.title, hit.url)
            pack.append("[$n] ").append(hit.title.take(100)).append(" (").append(host(hit.url)).append(")\n")
            if (products.isNotEmpty()) {
                pack.append("Products:\n")
                products.forEach { pack.append("- ").append(it.line()).append('\n') }
            }
            if (text.isNotBlank()) pack.append("Text: ").append(text).append('\n')
            pack.append('\n')
        }
        for (hit in snippetOnly.take(3)) {
            val n = sources.size + 1
            sources += Source(n, hit.title, hit.url)
            pack.append("[$n] ").append(hit.title.take(100)).append(" (").append(host(hit.url))
                .append(", search snippet only)\n").append(hit.snippet).append("\n\n")
        }

        // 5. answer
        emit(Event.Step("Writing answer from ${sources.size} sources"))
        val prompt = buildString {
            append("Question: ").append(question).append("\n\n")
            if (pack.isEmpty()) append("Evidence: none — no pages could be read.\n")
            else append("Evidence from ${sources.size} sources:\n\n").append(pack.take(MAX_EVIDENCE_CHARS))
        }
        llm.isolated(synthesisSystem(), temperature = 0.3) { c ->
            c.sendMessageAsync(prompt).collect { chunk ->
                val t = chunk.toString()
                if (t.isNotEmpty()) emit(Event.Token(t))
            }
        }
        val tEnd = SystemClock.elapsedRealtime()
        fun sec(ms: Long) = "%.1f".format(ms / 1000.0)
        val timings = "search ${sec(tSearch - t0)} s · read ${sec(tRead - tSearch)} s · answer ${sec(tEnd - tRead)} s"
        return Result(sources, timings)
    }

    // ------------------------------------------------------------------ planning

    private data class Plan(val queries: List<String>, val product: Boolean)

    private suspend fun plan(question: String): Plan {
        val raw = runCatching {
            llm.isolated(PLANNER_SYSTEM, temperature = 0.2) { c ->
                withContext(Dispatchers.IO) { c.sendMessage(plannerPrompt(question)).toString() }
            }
        }.getOrDefault("")

        val lines = raw.lines().map { it.trim() }
        val product = lines.any { it.equals("PRODUCT", ignoreCase = true) }
        val queries = lines
            .filterNot { it.equals("PRODUCT", true) || it.equals("GENERAL", true) }
            .map { it.trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('"', '\'', ' ') }
            .filter { it.length in 3..120 }
            .distinct()
            .take(3)
            .toMutableList()
        if (queries.isEmpty()) queries += question.take(120)
        if (product) {
            // Price comparison sites embed structured offer data; worth one extra query.
            queries.add(0, "site:hinta.fi ${queries.first()}")
        }
        return Plan(queries.distinct(), product)
    }

    private fun plannerPrompt(question: String) = """
Today is ${today()}.
User question: $question

Write 2 or 3 different web search queries that together find what is needed to answer it.
- Short keyword queries, like a person types into a search engine.
- Finnish queries for things about Finland or Finnish shops; English for international info. Mix both when useful.
- Cover different angles (e.g. a shop, a comparison, the manufacturer's own site).
If the question is about buying something or prices, write PRODUCT on the first line.
Output only the lines, nothing else.
    """.trimIndent()

    private fun synthesisSystem() = """
You answer the user's question using only the evidence below, collected from web pages. Today is ${today()}.
Rules:
- Reply in the language of the question.
- Start with the direct answer, then only the key details. Keep it short: about 120 words unless the question needs more. Use a short table only when comparing several items.
- After each fact, cite its source number like [2].
- Quote prices exactly as given in the evidence with their source. Never invent a price, shop, product or URL. Simple arithmetic on given numbers is fine.
- If the evidence does not cover part of the question (for example shipping costs or import VAT), say so plainly instead of guessing.
- Do not write a sources list; the app adds it.
    """.trimIndent()

    // ------------------------------------------------------------------ helpers

    private fun pickPages(perQuery: List<List<Hit>>, max: Int): List<Hit> {
        val chosen = mutableListOf<Hit>()
        val perHost = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        val depth = perQuery.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until depth) {
            for (hits in perQuery) {
                val h = hits.getOrNull(rank) ?: continue
                val host = host(h.url)
                val key = h.url.substringBefore('#').trimEnd('/')
                if (key in seen || SKIP_HOSTS.any { host.endsWith(it) }) continue
                if ((perHost[host] ?: 0) >= 2) continue
                seen += key
                perHost[host] = (perHost[host] ?: 0) + 1
                chosen += h
                if (chosen.size >= max) return chosen
            }
        }
        return chosen
    }

    private fun host(url: String) = url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url

    private fun today() = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))

    companion object {
        private const val MAX_PAGES = 6
        private const val MAX_EXTRA_QUERIES = 3
        /** Don't wait for slow sites; whatever has arrived by then is used. */
        private const val PAGE_TIMEOUT_MS = 8_000L
        private const val MAX_EVIDENCE_CHARS = 16_000
        private const val TEXT_BUDGET = 5_000
        private const val MAX_CHUNKS_PER_PAGE = 3
        private const val MIN_SCORE = 0.15f
        private const val PRICE_BONUS = 0.03f
        private const val PLANNER_SYSTEM = "You write web search queries. Output only the queries, one per line."
        private val SKIP_HOSTS = listOf(
            "youtube.com", "youtu.be", "facebook.com", "instagram.com", "tiktok.com", "x.com",
            "twitter.com", "pinterest.com", "linkedin.com", "duckduckgo.com",
        )
    }
}
