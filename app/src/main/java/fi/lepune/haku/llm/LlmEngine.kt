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

    /** Start a fresh conversation (new chat). */
    suspend fun newConversation(systemPrompt: String, tools: ToolSet?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { conversation?.close() }
            val e = checkNotNull(engine) { "Model not loaded" }
            conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    tools = if (tools != null) listOf(tool(tools)) else emptyList(),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.5),
                )
            )
        }
    }

    fun send(text: String): Flow<Message> =
        checkNotNull(conversation) { "No conversation" }.sendMessageAsync(text)

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
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
