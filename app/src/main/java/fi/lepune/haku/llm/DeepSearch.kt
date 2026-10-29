package fi.lepune.haku.llm

import fi.lepune.haku.tools.Hit
import fi.lepune.haku.tools.PageExtractor
import fi.lepune.haku.tools.WebTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
class DeepSearch(private val llm: LlmEngine, private val web: WebTools) {

    sealed interface Event {
        data class Step(val label: String) : Event
        data class Searched(val hits: List<Hit>) : Event
        data class Read(val hit: Hit) : Event
        data class Token(val text: String) : Event
    }

    data class Source(val n: Int, val title: String, val url: String)

    suspend fun run(question: String, emit: (Event) -> Unit): List<Source> {
        // 1. plan
        emit(Event.Step("Planning searches"))
        val plan = plan(question)
        emit(Event.Step("Queries: " + plan.queries.joinToString(" · ")))

        // 2. search (sequential with a small gap; DuckDuckGo blocks bursts)
        val perQuery = mutableListOf<List<Hit>>()
        for (q in plan.queries) {
            emit(Event.Step("Searching: $q"))
            val hits = runCatching { withContext(Dispatchers.IO) { web.webSearch(q, limit = 8) } }
                .onFailure { emit(Event.Step("⚠ search failed: ${it.message}")) }
                .getOrDefault(emptyList())
            if (hits.isNotEmpty()) emit(Event.Searched(hits))
            perQuery += hits
            delay(350)
        }

        // 3. choose pages: round-robin across queries, dedupe, max 2 per site
        val pages = pickPages(perQuery, MAX_PAGES)
        emit(Event.Step("Reading ${pages.size} pages"))

        // 4. read + extract in parallel
        val sem = Semaphore(4)
        val evidence = coroutineScope {
            pages.map { hit ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        withTimeoutOrNull(20_000) {
                            runCatching {
                                val doc = web.fetchDoc(hit.url)
                                val products = PageExtractor.products(doc)
                                val text = PageExtractor.passages(doc, question, maxChars = 900)
                                val title = doc.title().ifBlank { hit.title }
                                emit(Event.Read(Hit(title, hit.url, "")))
                                Triple(Hit(title, hit.url, hit.snippet), products, text)
                            }.getOrElse {
                                emit(Event.Step("⚠ ${host(hit.url)}: ${it.message ?: "unreadable"}"))
                                null
                            }
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Keep search snippets for pages that failed to load — still better than nothing.
        val readUrls = evidence.map { it.first.url }.toSet()
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
        for (hit in snippetOnly.take(4)) {
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
        return sources
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
            .take(5)
            .toMutableList()
        if (queries.isEmpty()) queries += question.take(120)
        if (product) {
            // Price comparison sites embed structured offer data; worth one extra query.
            queries += "site:hinta.fi ${queries.first()}"
        }
        return Plan(queries.distinct(), product)
    }

    private fun plannerPrompt(question: String) = """
Today is ${today()}.
User question: $question

Write 3 to 5 different web search queries that together find what is needed to answer it.
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
- Start with the direct answer, then a short comparison or the key details. Be compact. Use a table only if comparing several items.
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
        private const val MAX_PAGES = 8
        private const val MAX_EVIDENCE_CHARS = 16_000
        private const val PLANNER_SYSTEM = "You write web search queries. Output only the queries, one per line."
        private val SKIP_HOSTS = listOf(
            "youtube.com", "youtu.be", "facebook.com", "instagram.com", "tiktok.com", "x.com",
            "twitter.com", "pinterest.com", "linkedin.com", "duckduckgo.com",
        )
    }
}
