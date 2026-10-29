package fi.lepune.haku.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fi.lepune.haku.data.ModelCatalog
import fi.lepune.haku.data.ModelFileState
import fi.lepune.haku.data.ModelManager
import fi.lepune.haku.data.ModelSpec
import fi.lepune.haku.data.Mode
import fi.lepune.haku.data.Settings
import fi.lepune.haku.data.SettingsStore
import fi.lepune.haku.llm.DeepSearch
import fi.lepune.haku.llm.Embedder
import fi.lepune.haku.llm.LlmEngine
import fi.lepune.haku.llm.LoadException
import fi.lepune.haku.llm.LoadedModel
import fi.lepune.haku.llm.Prompts
import fi.lepune.haku.search.Intent
import fi.lepune.haku.search.Offer
import fi.lepune.haku.search.QuickWeb
import fi.lepune.haku.search.ShopSearch
import fi.lepune.haku.tools.Hit
import fi.lepune.haku.tools.PageRenderer
import fi.lepune.haku.alerts.PriceWatches
import fi.lepune.haku.alerts.Watch
import fi.lepune.haku.alerts.Hunt
import fi.lepune.haku.alerts.ToriHunts
import fi.lepune.haku.search.ToriIndex
import fi.lepune.haku.search.ToriItem
import kotlinx.coroutines.async
import fi.lepune.haku.tools.SearchTools
import fi.lepune.haku.tools.ToolEvent
import fi.lepune.haku.tools.WebTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val status: String? = null,
    val steps: List<String> = emptyList(),
    val searched: List<Hit> = emptyList(),
    val read: List<Hit> = emptyList(),
    /** Numbered sources for inline citations: index 0 is [1]. */
    val sources: List<Hit> = emptyList(),
    val stats: String? = null,
    val error: String? = null,
    val done: Boolean = true,
    val mode: Mode = Mode.ASK,
    /** The question this reply answers (for "ask Gemma" follow-ups). */
    val question: String? = null,
    val offers: List<Offer>? = null,
    /** Second-hand listings from the self-hosted Tori index (Haku mode, price questions). */
    val tori: List<ToriItem>? = null,
    val results: List<Hit>? = null,
    val instant: Hit? = null,
    val discussions: List<Hit>? = null,
    val site: Hit? = null,
    val loadingDiscussions: Boolean = false,
)

sealed interface EngineState {
    data object NoModel : EngineState
    data class Loading(val spec: ModelSpec) : EngineState
    data class Ready(val model: LoadedModel) : EngineState
    data class Error(val spec: ModelSpec, val message: String) : EngineState
}

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val engine: EngineState = EngineState.NoModel,
    val settings: Settings = Settings(),
    val files: Map<String, ModelFileState> = emptyMap(),
    val importing: Pair<String, Long>? = null,
    val sharedStorage: Boolean = false,
    /** Model ids that sit in app-private storage (lost on uninstall). */
    val privateCopies: Set<String> = emptySet(),
    val recent: List<Conversation> = emptyList(),
    val watches: List<Watch> = emptyList(),
    val hunts: List<Hunt> = emptyList(),
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val models = ModelManager(app, store)
    private val llm = LlmEngine(app)
    private val web = WebTools { _state.value.settings }

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var genJob: Job? = null
    private var nextId = 1L
    private val history = HistoryStore(app)
    private val watchStore = PriceWatches(app)
    private val huntStore = ToriHunts(app)
    private val tori = ToriIndex(web) { _state.value.settings.toriUrl }
    /** History id of the conversation on screen; null = home screen / not yet saved. */
    private var currentConvId: Long? = null

    /** Assistant message currently receiving tool events. */
    @Volatile private var activeId = -1L
    /** Wall time spent inside tool calls for the active answer (network, parsing). */
    private val toolMs = java.util.concurrent.atomic.AtomicLong(0)

    private val embedder = Embedder(app)
    private val tools = SearchTools(web, ranker = { passageRanker() }) { ev -> onToolEvent(ev) }
    private val renderer = PageRenderer(app)
    private val shop = ShopSearch(web) { url -> renderer.render(url) }
    private val quickWeb = QuickWeb(web)
    private val deep = DeepSearch(llm, web) {
        val spec = ModelCatalog.EMBEDDER
        if (models.state(spec) is ModelFileState.Ready) {
            embedder.ensure(models.file(spec)); embedder
        } else null
    }

    init {
        // Free the space of models the app no longer offers (e.g. the 3.7 GB E4B).
        viewModelScope.launch(Dispatchers.IO) { ModelCatalog.RETIRED.forEach { runCatching { models.delete(it) } } }
        _state.update { it.copy(recent = history.list(), watches = watchStore.list(), hunts = huntStore.list()) }
        watchStore.schedule()
        huntStore.schedule()
        refreshFiles()
        viewModelScope.launch {
            // Poll download progress; cheap and keeps the models screen live.
            while (isActive) {
                delay(1000)
                refreshFiles()
            }
        }
        loadSelectedIfPresent()
    }

    // ------------------------------------------------------------------ models

    private fun refreshFiles() {
        val before = _state.value.files
        val now = ModelCatalog.ALL.associate { it.id to models.state(it) }
        val shared = models.hasSharedAccess()
        val priv = ModelCatalog.ALL.filter { models.isPrivateCopy(it) }.map { it.id }.toSet()
        if (shared != _state.value.sharedStorage || priv != _state.value.privateCopies) {
            _state.update { it.copy(sharedStorage = shared, privateCopies = priv) }
        }
        if (now != before) {
            _state.update { it.copy(files = now) }
            // Auto-load when the selected model finishes downloading.
            val sel = _state.value.settings.modelId
            if (before[sel] !is ModelFileState.Ready && now[sel] is ModelFileState.Ready &&
                _state.value.engine !is EngineState.Loading
            ) loadSelectedIfPresent()
        }
    }

    fun loadSelectedIfPresent() {
        val s = _state.value.settings
        val spec = ModelCatalog.byId(s.modelId) ?: return
        if (models.state(spec) !is ModelFileState.Ready) {
            _state.update { it.copy(engine = EngineState.NoModel) }
            return
        }
        cancelGeneration()
        _state.update { it.copy(engine = EngineState.Loading(spec)) }
        viewModelScope.launch {
            try {
                val loaded = llm.load(spec, models.file(spec), s.backend, s.speculativeDecoding)
                llm.newConversation(systemPrompt(spec), if (spec.supportsTools) tools else null)
                _state.update { it.copy(engine = EngineState.Ready(loaded), messages = emptyList()) }
                // Warm the embedding model now so the first question doesn't pay for loading it.
                if (spec.supportsTools) launch(Dispatchers.IO) { ensureEmbedder() }
            } catch (e: LoadException) {
                _state.update { it.copy(engine = EngineState.Error(spec, e.message ?: "Load failed")) }
            } catch (t: Throwable) {
                _state.update { it.copy(engine = EngineState.Error(spec, t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    fun selectModel(id: String) {
        updateSettings(_state.value.settings.copy(modelId = id), reload = true)
    }

    fun download(spec: ModelSpec) {
        models.startDownload(spec, _state.value.settings.hfToken)
        refreshFiles()
    }

    fun cancelDownload(spec: ModelSpec) {
        models.cancel(spec); refreshFiles()
    }

    fun delete(spec: ModelSpec) {
        if ((_state.value.engine as? EngineState.Ready)?.model?.spec?.id == spec.id) {
            viewModelScope.launch { llm.close() }
            _state.update { it.copy(engine = EngineState.NoModel) }
        }
        if (spec.id == ModelCatalog.EMBEDDER.id) embedder.close()
        models.delete(spec); refreshFiles()
    }

    fun import(spec: ModelSpec, uri: Uri) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(importing = spec.id to 0L) }
                models.import(spec, uri) { bytes -> _state.update { it.copy(importing = spec.id to bytes) } }
            } catch (t: Throwable) {
                _state.update { it.copy(engine = EngineState.Error(spec, "Import failed: ${t.message}")) }
            } finally {
                _state.update { it.copy(importing = null) }
                refreshFiles()
            }
        }
    }

    fun moveToShared(spec: ModelSpec) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(importing = spec.id to 0L) }
                models.moveToShared(spec) { bytes -> _state.update { it.copy(importing = spec.id to bytes) } }
            } catch (t: Throwable) {
                _state.update { it.copy(engine = EngineState.Error(spec, "Move failed: ${t.message}")) }
            } finally {
                _state.update { it.copy(importing = null) }
                refreshFiles()
            }
        }
    }

    /** Settings "Test": one query against the SearXNG instance; [done] runs on the main thread. */
    fun testSearxng(url: String, done: (String) -> Unit) {
        viewModelScope.launch {
            val r = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { web.testSearxng(url) }.getOrElse { "Failed: ${it.message}" } }
            done(r)
        }
    }

    fun setMode(m: Mode) = updateSettings(_state.value.settings.copy(mode = m))

    fun updateSettings(new: Settings, reload: Boolean = false) {
        val old = _state.value.settings
        store.save(new)
        _state.update { it.copy(settings = new) }
        val needsReload = reload || old.backend != new.backend ||
            old.speculativeDecoding != new.speculativeDecoding || old.modelId != new.modelId
        if (needsReload) loadSelectedIfPresent()
    }

    /** Loads EmbeddingGemma if its file is present; false if it isn't available. */
    private suspend fun ensureEmbedder(): Boolean {
        val spec = ModelCatalog.EMBEDDER
        if (models.state(spec) !is ModelFileState.Ready) return false
        return embedder.ensure(models.file(spec))
    }

    /** Cosine similarity of passages to the query, only when the embedder is already loaded. */
    private fun passageRanker(): fi.lepune.haku.tools.Ranker? {
        if (!embedder.isReady) return null
        return { query, texts ->
            if (texts.isEmpty()) FloatArray(0) else {
                val q = embedder.embedQuery(query)
                val vecs = embedder.embedDocs(texts)
                FloatArray(vecs.size) { i -> Embedder.cosine(q, vecs[i]) }
            }
        }
    }

    private fun systemPrompt(spec: ModelSpec) =
        if (spec.supportsTools) Prompts.search() else Prompts.PLAIN

    // ------------------------------------------------------------------ chat

    /** Back to the home screen: keep the conversation in history, start a fresh model context. */
    fun goHome() {
        cancelGeneration()
        saveCurrent()
        currentConvId = null
        _state.update { it.copy(messages = emptyList(), generating = false) }
        resetModelContext()
    }

    fun newChat() = goHome()

    fun openConversation(id: Long) {
        val c = _state.value.recent.firstOrNull { it.id == id } ?: return
        cancelGeneration()
        saveCurrent()
        currentConvId = c.id
        nextId = (c.messages.maxOfOrNull { it.id } ?: 0L) + 1
        _state.update { it.copy(messages = c.messages, generating = false) }
        // The model can't resume an old context; follow-ups start fresh.
        resetModelContext()
    }

    fun deleteConversation(id: Long) {
        history.delete(id)
        if (currentConvId == id) currentConvId = null
        _state.update { it.copy(recent = history.list()) }
    }

    fun clearHistory() {
        history.clear()
        _state.update { it.copy(recent = emptyList()) }
    }

    /**
     * Start watching the price search behind [msgId]; baseline = today's cheapest real offer.
     * With a Tori index configured, also saves a hunt there so new second-hand listings notify.
     */
    fun watchPrice(msgId: Long) {
        val msg = _state.value.messages.firstOrNull { it.id == msgId } ?: return
        val q = msg.question ?: return
        val best = msg.offers.orEmpty().filterNot { it.fromSnippet || it.from }.minByOrNull { it.price }
            ?: msg.offers.orEmpty().minByOrNull { it.price }
        if (best != null) {
            watchStore.upsert(Watch(System.currentTimeMillis(), q, best.price, best.shop, best.url, System.currentTimeMillis()))
            _state.update { it.copy(watches = watchStore.list()) }
        }
        if (tori.enabled && msg.tori != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val terms = Intent.productTerms(q)
                runCatching { tori.createHunt(terms) }
                    .onSuccess { id ->
                        huntStore.add(Hunt(id, terms, q, System.currentTimeMillis()))
                        _state.update { it.copy(hunts = huntStore.list()) }
                    }
                    .onFailure { e -> editMsg(msgId) { it.copy(error = "Tori hunt failed: ${e.message}") } }
            }
        }
    }

    /**
     * The index searches by meaning, so "RTX 3060" also returns GTX 1660s and gaming PCs.
     * With a model code in the query: the exact part cheapest first (max 8), then whole
     * computers that contain it (max 4), then at most 4 similar models.
     */
    private fun rankTori(q: String, items: List<ToriItem>): List<ToriItem> {
        if (Intent.modelCodes(Intent.productTerms(q)).isEmpty()) return items.take(8)
        val byPrice = compareBy<ToriItem>({ it.price == null }, { it.price ?: 0.0 })
        val (exact, other) = items.partition { Intent.sameModel(it.title, q) }
        val wantsComputer = Intent.isComputer(q)
        val (pcs, parts) = exact.partition { !wantsComputer && Intent.isComputer(it.title) }
        return parts.sortedWith(byPrice).take(8) +
            pcs.sortedWith(byPrice).take(4).map { it.copy(inComputer = true) } +
            other.take(4).map { it.copy(similar = true) }
    }

    fun deleteHunt(id: Long) {
        huntStore.delete(id)
        _state.update { it.copy(hunts = huntStore.list()) }
    }

    fun testTori(url: String, done: (String) -> Unit) {
        viewModelScope.launch {
            done(kotlinx.coroutines.withContext(Dispatchers.IO) { tori.test(url) })
        }
    }

    fun deleteWatch(id: Long) {
        watchStore.delete(id)
        _state.update { it.copy(watches = watchStore.list()) }
    }

    fun checkWatchesNow() = watchStore.checkNow()

    fun loadDiscussions(msgId: Long) {
        val msg = _state.value.messages.firstOrNull { it.id == msgId } ?: return
        val q = msg.question ?: return
        if (msg.loadingDiscussions || msg.discussions != null) return
        fun edit(f: (ChatMessage) -> ChatMessage) =
            _state.update { s -> s.copy(messages = s.messages.map { if (it.id == msgId) f(it) else it }) }
        edit { it.copy(loadingDiscussions = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val d = runCatching { quickWeb.discussions(q) }.getOrDefault(emptyList())
            edit { it.copy(discussions = d, loadingDiscussions = false) }
            saveCurrent()
        }
    }

    private fun resetModelContext() {
        val ready = _state.value.engine as? EngineState.Ready ?: return
        viewModelScope.launch {
            val spec = ready.model.spec
            runCatching { llm.newConversation(systemPrompt(spec), if (spec.supportsTools) tools else null) }
        }
    }

    private fun saveCurrent() {
        val msgs = _state.value.messages.filter { it.done }
        if (msgs.none { it.role == Role.ASSISTANT }) return
        val id = currentConvId ?: System.currentTimeMillis().also { currentConvId = it }
        val title = msgs.firstOrNull { it.role == Role.USER }?.text?.take(80) ?: "…"
        runCatching { history.save(Conversation(id, title, msgs.first().mode, System.currentTimeMillis(), msgs)) }
        _state.update { it.copy(recent = history.list()) }
    }

    /** "Kysy Gemmalta": answer from the results already on screen instead of searching again. */
    fun askAbout(msgId: Long) {
        val msg = _state.value.messages.firstOrNull { it.id == msgId } ?: return
        val q = msg.question ?: return
        fun eur(v: Double) = String.format(java.util.Locale.ROOT, "%.2f €", v).replace('.', ',')
        val ctx = buildString {
            // Snippet prices are guesses from search results; the model trusted them over real offers.
            // One running source number across shops and Tori, so citations point at one row each.
            var n = 0
            msg.offers?.filterNot { it.fromSnippet }?.takeIf { it.isNotEmpty() }?.let { offers ->
                append("New, from shops (cheapest first):\n")
                offers.forEach { o ->
                    append("[${++n}] ${eur(o.price)}")
                    if (o.from) append(" (from)")
                    o.shipping?.let { append(" + delivery ${eur(it)}") }
                    o.fx?.let { append(" (converted from $it)") }
                    append(" — ${o.name} — ${o.shop}")
                    when (o.inStock) { true -> append(", in stock"); false -> append(", out of stock"); null -> {} }
                    if (o.finnish) append(", Finnish company")
                    append('\n')
                }
            }
            msg.tori?.filterNot { it.similar || it.inComputer }?.takeIf { it.isNotEmpty() }?.let { items ->
                append("Used, private sellers on Tori (cheapest first):\n")
                items.forEach { t ->
                    append("[${++n}] ${t.price?.let(::eur) ?: "no price"} — ${t.title}")
                    t.city?.let { append(", $it") }
                    t.dealPercent?.let { append(", $it % below similar listings") }
                    append('\n')
                }
            }
            msg.instant?.let { append("Wikipedia: ${it.title}: ${it.snippet}\n") }
            msg.results?.takeIf { it.isNotEmpty() }?.let { r ->
                append("Web results already found:\n")
                r.take(8).forEachIndexed { i, h -> append("${i + 1}. ${h.title} (${h.url}) — ${h.snippet}\n") }
            }
        }
        val compare = if (msg.tori.orEmpty().any { !it.similar } && msg.offers.orEmpty().any { !it.fromSnippet })
            "\nGive the cheapest new offer and the cheapest used Tori listing separately (price, shop or city), " +
                "each with its own source number. If the cheapest new offer is out of stock, also give the cheapest " +
                "new one in stock. Only the asked model; ignore other models and whole computers." else ""
        val few = msg.offers.orEmpty().size in 1..2
        val note = if (few) "\nOnly ${msg.offers!!.size} offer(s) were found: say that the list is thin, " +
            "mention whether delivery or currency changes the picture, and search the web for other listings." else ""
        send(q, Mode.ASK, context = (ctx + compare + note).ifBlank { null })
    }

    fun send(text: String, modeOverride: Mode? = null, context: String? = null) {
        val q = text.trim()
        if (q.isEmpty() || _state.value.generating) return
        val mode = modeOverride ?: _state.value.settings.mode

        if (mode == Mode.SEARCH) {
            sendSearch(q)
            return
        }
        val ready = _state.value.engine as? EngineState.Ready ?: return
        val useDeep = mode == Mode.RESEARCH && ready.model.spec.supportsTools

        val user = ChatMessage(nextId++, Role.USER, q, mode = mode)
        val reply = ChatMessage(nextId++, Role.ASSISTANT, "", status = if (useDeep) "Planning…" else "Thinking…",
            done = false, mode = mode, question = q)
        activeId = reply.id
        _state.update { it.copy(messages = it.messages + user + reply, generating = true) }
        tools.resetBudget()
        toolMs.set(0)

        if (useDeep) {
            sendDeep(q)
            return
        }
        val canSearch = ready.model.spec.supportsTools

        genJob = viewModelScope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            var chars = 0
            var searchMs = 0L
            try {
                // The app searches first (no model round needed to decide that), then the model
                // only has to read the evidence. It can still call tools if the evidence lacks it.
                val ctx = context ?: if (!canSearch) null else {
                    val t = SystemClock.elapsedRealtime()
                    tools.prefetch(q).also {
                        searchMs = SystemClock.elapsedRealtime() - t
                        editActive { m -> m.copy(status = "Reading results…") }
                    }
                }
                val withCtx = if (ctx == null) q else
                    "$ctx\n\nAnswer from these results, citing [n]. Call a tool only if they clearly lack the answer.\n\nQuestion: $q"
                val toModel = if (Intent.isFinnish(q)) "$withCtx\n\n(Vastaa suomeksi.)" else withCtx
                val modelStart = SystemClock.elapsedRealtime()
                llm.send(toModel).collect { chunk ->
                    val piece = chunk.toString()
                    if (piece.isEmpty()) return@collect
                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                    chars += piece.length
                    editActive { it.copy(text = it.text + piece, status = null) }
                }
                val end = SystemClock.elapsedRealtime()
                editActive { it.copy(done = true, status = null,
                    stats = askStats(start, modelStart, firstTokenAt, end, chars, searchMs, toolMs.get())) }
            } catch (e: CancellationException) {
                editActive { it.copy(done = true, status = null, stats = "stopped") }
                throw e
            } catch (t: Throwable) {
                editActive { it.copy(done = true, status = null, error = t.message ?: t.javaClass.simpleName) }
            } finally {
                activeId = -1L
                _state.update { it.copy(generating = false) }
                saveCurrent()
            }
        }
    }

    /** Search mode: no language model, results in a couple of seconds. */
    private fun sendSearch(q: String) {
        val shopping = Intent.isShopping(q) || Intent.isUsed(q)
        val user = ChatMessage(nextId++, Role.USER, q, mode = Mode.SEARCH)
        val reply = ChatMessage(nextId++, Role.ASSISTANT, "", status = if (shopping) "Finding prices…" else "Searching…",
            done = false, mode = Mode.SEARCH, question = q)
        activeId = reply.id
        _state.update { it.copy(messages = it.messages + user + reply, generating = true) }

        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (shopping) {
                    // Own Tori index in parallel: fast, shown as soon as it answers.
                    val replyId = reply.id
                    val toriD = if (!tori.enabled) null else async {
                        runCatching { rankTori(q, tori.search(Intent.productTerms(q), limit = 20)) }
                            .onFailure { e -> editMsg(replyId) { m -> m.copy(steps = m.steps + "⚠ Tori index: ${e.message}") } }
                            .getOrNull()
                            ?.also { items -> editMsg(replyId) { m -> m.copy(tori = items) } }
                    }
                    val r = shop.run(
                        q,
                        step = { label -> editActive { it.copy(status = label, steps = it.steps + label) } },
                        partial = { p -> editActive { it.copy(offers = p.offers, stats = p.summary) } },
                    )
                    toriD?.await()
                    editActive { it.copy(offers = r.offers, stats = r.summary, done = true, status = null) }
                } else {
                    val r = quickWeb.run(q)
                    editActive { it.copy(results = r.results, instant = r.instant, site = r.site, stats = r.summary, done = true, status = null) }
                }
            } catch (e: CancellationException) {
                editActive { it.copy(done = true, status = null, stats = "stopped") }
                throw e
            } catch (t: Throwable) {
                editActive { it.copy(done = true, status = null, error = t.message ?: t.javaClass.simpleName) }
            } finally {
                activeId = -1L
                _state.update { it.copy(generating = false) }
                saveCurrent()
            }
        }
    }

    private fun sendDeep(q: String) {
        genJob = viewModelScope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            var chars = 0
            try {
                val result = deep.run(q) { ev ->
                    when (ev) {
                        is DeepSearch.Event.Step -> editActive { it.copy(status = ev.label, steps = it.steps + ev.label) }
                        is DeepSearch.Event.Searched -> editActive { it.copy(searched = (it.searched + ev.hits).distinctBy { h -> h.url }) }
                        is DeepSearch.Event.Read -> editActive { it.copy(read = (it.read + ev.hit).distinctBy { h -> h.url }) }
                        is DeepSearch.Event.Token -> {
                            if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                            chars += ev.text.length
                            editActive { it.copy(text = it.text + ev.text, status = null) }
                        }
                    }
                }
                val end = SystemClock.elapsedRealtime()
                // Compact sources: site name as the link text, full URL behind it.
                val list = if (result.sources.isEmpty()) "" else "\n\n" + result.sources.joinToString("  ") {
                    "[${it.n}] [${shortHost(it.url)}](${it.url})"
                }
                val total = "%.0f s total".format((end - start) / 1000.0)
                editActive {
                    it.copy(text = it.text + list, done = true, status = null,
                        stats = listOfNotNull(total, result.timings,
                            stats(start, firstTokenAt, end, chars)?.substringAfter(" · ")).joinToString(" · "))
                }
            } catch (e: CancellationException) {
                editActive { it.copy(done = true, status = null, stats = "stopped") }
                throw e
            } catch (t: Throwable) {
                editActive { it.copy(done = true, status = null, error = t.message ?: t.javaClass.simpleName) }
            } finally {
                activeId = -1L
                _state.update { it.copy(generating = false) }
                saveCurrent()
            }
        }
    }

    fun cancelGeneration() {
        llm.cancel()
        genJob?.cancel()
        genJob = null
    }

    private fun onToolEvent(ev: ToolEvent) {
        when (ev) {
            is ToolEvent.Started -> editActive { it.copy(status = ev.label, steps = it.steps + ev.label) }
            is ToolEvent.Searched -> editActive { it.copy(searched = (it.searched + ev.hits).distinctBy { h -> h.url }) }
            is ToolEvent.Read -> editActive { it.copy(read = (it.read + ev.hit).distinctBy { h -> h.url }) }
            is ToolEvent.Failed -> editActive { it.copy(steps = it.steps + "⚠ ${ev.label}: ${ev.error}") }
            is ToolEvent.Finished -> {
                toolMs.addAndGet(ev.ms)
                editActive { it.copy(status = "Thinking…") }
            }
            is ToolEvent.Sources -> editActive { it.copy(sources = ev.all) }
        }
    }

    /** Edit a message by id (for results that arrive independently of the active reply). */
    private inline fun editMsg(id: Long, crossinline f: (ChatMessage) -> ChatMessage) {
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) f(it) else it }) }
    }

    private inline fun editActive(crossinline f: (ChatMessage) -> ChatMessage) {
        val id = activeId
        if (id < 0) return
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) f(it) else it }) }
    }

    private fun shortHost(url: String): String =
        runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url.take(30)

    /**
     * Kysy timing: search (app) · model reading · extra tool calls the model made itself.
     * "model" is prompt processing plus any tool-call writing, i.e. everything on the GPU
     * before the first visible word.
     */
    private fun askStats(start: Long, modelStart: Long, first: Long, end: Long, chars: Int,
                         searchMs: Long, extraToolMs: Long): String? {
        if (first == 0L) return null
        val tps = chars / 4.0 / (((end - first).coerceAtLeast(1)) / 1000.0)
        fun s(ms: Long) = "%.1f".format(ms.coerceAtLeast(0) / 1000.0)
        val parts = buildList {
            if (searchMs > 0) add("search ${s(searchMs)} s")
            add("model ${s(first - modelStart - extraToolMs)} s")
            if (extraToolMs > 0) add("extra tools ${s(extraToolMs)} s")
        }
        return "${s(first - start)} s to first token (${parts.joinToString(" · ")}) · ~%.0f tok/s".format(tps)
    }

    /**
     * ~4 chars per token is close enough for Gemma's tokenizer on mixed fi/en text.
     * With [toolMs], time to first token is split into tools (search, fetching pages) and
     * model (reading the results + deciding what to call), so it's clear which side is slow.
     */
    private fun stats(start: Long, first: Long, end: Long, chars: Int, toolMs: Long = 0): String? {
        if (first == 0L) return null
        val ttftMs = first - start
        val genSec = ((end - first).coerceAtLeast(1)) / 1000.0
        val tps = chars / 4.0 / genSec
        val split = if (toolMs > 0) " (tools %.1f s · model %.1f s)".format(toolMs / 1000.0,
            (ttftMs - toolMs).coerceAtLeast(0) / 1000.0) else ""
        return "%.1f s to first token%s · ~%.0f tok/s".format(ttftMs / 1000.0, split, tps)
    }

    override fun onCleared() {
        cancelGeneration()
        // viewModelScope is already cancelled here; release native memory on a plain thread.
        Thread { kotlinx.coroutines.runBlocking { llm.close() }; embedder.close() }.start()
    }
}
