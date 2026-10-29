package fi.lepune.haku.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import fi.lepune.haku.data.BackendPref
import fi.lepune.haku.data.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class LoadedModel(
    val spec: ModelSpec,
    val backend: BackendPref,
    val loadMs: Long,
    /** Backends that were tried and failed before this one, with the reason. */
    val fallbacks: List<Pair<BackendPref, String>>,
)

class LoadException(val attempts: List<Pair<BackendPref, String>>) :
    Exception(attempts.joinToString("\n") { (b, e) -> "${b.name}: $e" })

/** Thin wrapper around LiteRT-LM: one engine, one conversation at a time. */
class LlmEngine(private val context: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    var loaded: LoadedModel? = null
        private set

    @OptIn(ExperimentalApi::class)
    suspend fun load(
        spec: ModelSpec,
        file: File,
        preferred: BackendPref,
        speculative: Boolean,
    ): LoadedModel = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeInternal()
            val order = when {
                spec.npuOnly -> listOf(BackendPref.NPU)
                preferred == BackendPref.NPU -> listOf(BackendPref.NPU, BackendPref.GPU, BackendPref.CPU)
                preferred == BackendPref.GPU -> listOf(BackendPref.GPU, BackendPref.CPU)
                else -> listOf(BackendPref.CPU)
            }
            val failures = mutableListOf<Pair<BackendPref, String>>()
            for (b in order) {
                val start = SystemClock.elapsedRealtime()
                var e: Engine? = null
                try {
                    // Multi-token prediction: big decode speedup on GPU per Google's numbers.
                    ExperimentalFlags.enableSpeculativeDecoding = speculative && b != BackendPref.NPU
                    e = Engine(
                        EngineConfig(
                            modelPath = file.absolutePath,
                            backend = b.toBackend(),
                            maxNumTokens = spec.maxTokens,
                            cacheDir = context.cacheDir.path,
                        )
                    )
                    e.initialize()
                    engine = e
                    val result = LoadedModel(spec, b, SystemClock.elapsedRealtime() - start, failures.toList())
                    loaded = result
                    Log.i(TAG, "Loaded ${spec.name} on $b in ${result.loadMs} ms")
                    return@withContext result
                } catch (t: Throwable) {
                    Log.w(TAG, "Backend $b failed", t)
                    runCatching { e?.close() }
                    failures += b to (t.message ?: t.javaClass.simpleName).take(300)
                }
            }
            throw LoadException(failures)
        }
    }

    private var mainSystem: String = ""
    private var mainTools: ToolSet? = null
    @Volatile private var temp: Conversation? = null

    /** Start a fresh conversation (new chat). */
    suspend fun newConversation(systemPrompt: String, tools: ToolSet?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { conversation?.close() }
            mainSystem = systemPrompt
            mainTools = tools
            conversation = create(systemPrompt, tools, temperature = 0.5)
        }
    }

    private fun create(system: String, tools: ToolSet?, temperature: Double): Conversation {
        val e = checkNotNull(engine) { "Model not loaded" }
        return e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                tools = if (tools != null) listOf(tool(tools)) else emptyList(),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature),
            )
        )
    }

    /** Send into the main chat. Recreated lazily if a deep search used the engine in between. */
    fun send(text: String): Flow<Message> {
        val c = synchronized(this) {
            conversation ?: create(mainSystem, mainTools, temperature = 0.5).also { conversation = it }
        }
        return c.sendMessageAsync(text)
    }

    /**
     * Run [block] on a throwaway conversation with no tools (deep-search planning/synthesis).
     * Closes the main chat's context; the next [send] starts a fresh one.
     */
    suspend fun <T> isolated(system: String, temperature: Double, block: suspend (Conversation) -> T): T {
        val c = mutex.withLock {
            withContext(Dispatchers.IO) {
                synchronized(this@LlmEngine) {
                    runCatching { conversation?.close() }
                    conversation = null
                }
                create(system, null, temperature)
            }
        }
        temp = c
        try {
            return block(c)
        } finally {
            temp = null
            withContext(Dispatchers.IO) { runCatching { c.close() } }
        }
    }

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
        runCatching { temp?.cancelProcess() }
    }

    suspend fun close() = mutex.withLock { withContext(Dispatchers.IO) { closeInternal() } }

    private fun closeInternal() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
        loaded = null
    }

    private fun BackendPref.toBackend(): Backend = when (this) {
        BackendPref.CPU -> Backend.CPU()
        BackendPref.GPU -> Backend.GPU()
        BackendPref.NPU -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
    }

    companion object {
        private const val TAG = "HakuEngine"
    }
}
