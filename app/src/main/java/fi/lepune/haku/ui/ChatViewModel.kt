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
    val stats: String? = null,
    val error: String? = null,
    val done: Boolean = true,
    val mode: Mode = Mode.ASK,
    /** The question this reply answers (for "ask Gemma" follow-ups). */
    val question: String? = null,
    val offers: List<Offer>? = null,
    val results: List<Hit>? = null,
    val instant: Hit? = null,
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

    /** Assistant message currently receiving tool events. */
    @Volatile private var activeId = -1L

    private val tools = SearchTools(web) { ev -> onToolEvent(ev) }
    private val embedder = Embedder(app)
    private val shop = ShopSearch(web)
    private val quickWeb = QuickWeb(web)
    private val deep = DeepSearch(llm, web) {
        val spec = ModelCatalog.EMBEDDER
        if (models.state(spec) is ModelFileState.Ready) {
            embedder.ensure(models.file(spec)); embedder
        } else null
    }

    init {
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

    fun setMode(m: Mode) = updateSettings(_state.value.settings.copy(mode = m))

    fun updateSettings(new: Settings, reload: Boolean = false) {
        val old = _state.value.settings
        store.save(new)
        _state.update { it.copy(settings = new) }
        val needsReload = reload || old.backend != new.backend ||
            old.speculativeDecoding != new.speculativeDecoding || old.modelId != new.modelId
        if (needsReload) loadSelectedIfPresent()
    }

    private fun systemPrompt(spec: ModelSpec) =
        if (spec.supportsTools) Prompts.search() else Prompts.PLAIN

    // ------------------------------------------------------------------ chat

    fun newChat() {
        cancelGeneration()
        val ready = _state.value.engine as? EngineState.Ready ?: return
        _state.update { it.copy(messages = emptyList()) }
        viewModelScope.launch {
            val spec = ready.model.spec
            runCatching { llm.newConversation(systemPrompt(spec), if (spec.supportsTools) tools else null) }
        }
    }

    fun send(text: String, modeOverride: Mode? = null) {
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

        if (useDeep) {
            sendDeep(q)
            return
        }
        val toModel = if (Intent.isFinnish(q)) "$q\n\n(Vastaa suomeksi.)" else q

        genJob = viewModelScope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            var firstTokenAt = 0L
            var chars = 0
            try {
                llm.send(toModel).collect { chunk ->
                    val piece = chunk.toString()
                    if (piece.isEmpty()) return@collect
                    if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
                    chars += piece.length
                    editActive { it.copy(text = it.text + piece, status = null) }
                }
                val end = SystemClock.elapsedRealtime()
                editActive { it.copy(done = true, status = null, stats = stats(start, firstTokenAt, end, chars)) }
            } catch (e: CancellationException) {
                editActive { it.copy(done = true, status = null, stats = "stopped") }
                throw e
            } catch (t: Throwable) {
                editActive { it.copy(done = true, status = null, error = t.message ?: t.javaClass.simpleName) }
            } finally {
                activeId = -1L
                _state.update { it.copy(generating = false) }
            }
        }
    }

    /** Search mode: no language model, results in a couple of seconds. */
    private fun sendSearch(q: String) {
        val shopping = Intent.isShopping(q)
        val user = ChatMessage(nextId++, Role.USER, q, mode = Mode.SEARCH)
        val reply = ChatMessage(nextId++, Role.ASSISTANT, "", status = if (shopping) "Finding prices…" else "Searching…",
            done = false, mode = Mode.SEARCH, question = q)
        activeId = reply.id
        _state.update { it.copy(messages = it.messages + user + reply, generating = true) }

        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (shopping) {
                    val r = shop.run(q) { label -> editActive { it.copy(status = label, steps = it.steps + label) } }
                    editActive { it.copy(offers = r.offers, stats = r.summary, done = true, status = null) }
                } else {
                    val r = quickWeb.run(q)
                    editActive { it.copy(results = r.results, instant = r.instant, stats = r.summary, done = true, status = null) }
                }
            } catch (e: CancellationException) {
                editActive { it.copy(done = true, status = null, stats = "stopped") }
                throw e
            } catch (t: Throwable) {
                editActive { it.copy(done = true, status = null, error = t.message ?: t.javaClass.simpleName) }
            } finally {
                activeId = -1L
                _state.update { it.copy(generating = false) }
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
        }
        if (ev !is ToolEvent.Started) editActive { it.copy(status = "Thinking…") }
    }

    private inline fun editActive(crossinline f: (ChatMessage) -> ChatMessage) {
        val id = activeId
        if (id < 0) return
        _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) f(it) else it }) }
    }

    private fun shortHost(url: String): String =
        runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull() ?: url.take(30)

    /** ~4 chars per token is close enough for Gemma's tokenizer on mixed fi/en text. */
    private fun stats(start: Long, first: Long, end: Long, chars: Int): String? {
        if (first == 0L) return null
        val ttft = (first - start) / 1000.0
        val genSec = ((end - first).coerceAtLeast(1)) / 1000.0
        val tps = chars / 4.0 / genSec
        return "%.1f s to first token · ~%.0f tok/s".format(ttft, tps)
    }

    override fun onCleared() {
        cancelGeneration()
        // viewModelScope is already cancelled here; release native memory on a plain thread.
        Thread { kotlinx.coroutines.runBlocking { llm.close() }; embedder.close() }.start()
    }
}
