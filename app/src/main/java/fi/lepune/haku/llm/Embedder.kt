package fi.lepune.haku.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EmbeddingGemma 2 on the CPU, so it can run while Gemma 4 uses the GPU.
 * Vectors are L2-normalised, so cosine similarity is a plain dot product.
 */
class Embedder(private val context: Context) {
    private var engine: EmbeddingEngine? = null
    private var loadedPath: String? = null

    @Volatile var lastError: String? = null
        private set

    val isReady: Boolean get() = engine != null

    suspend fun ensure(file: File): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@Embedder) {
            if (engine != null && loadedPath == file.absolutePath) return@synchronized true
            closeLocked()
            try {
                val e = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = file.absolutePath,
                        backend = Backend.CPU(),
                        cacheDir = context.cacheDir.path,
                    )
                )
                e.initialize()
                engine = e
                loadedPath = file.absolutePath
                lastError = null
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Embedding engine failed", t)
                lastError = (t.message ?: t.javaClass.simpleName).take(200)
                false
            }
        }
    }

    /** Embed for retrieval. Batched; blocking — call off the main thread. */
    fun embed(texts: List<String>): List<FloatArray> {
        val e = checkNotNull(engine) { "Embedding model not loaded" }
        val opts = EmbeddingOptions(normalize = true, outputSize = DIM)
        return texts.chunked(BATCH).flatMap { batch ->
            e.computeEmbeddingBatch(batch.map { listOf(InputData.Text(it)) }, opts).map { it.embedding }
        }
    }

    fun embedQuery(q: String) = embed(listOf(QUERY_PREFIX + q.trim())).first()
    fun embedDocs(docs: List<String>) = embed(docs.map { DOC_PREFIX + it.trim() })

    fun close() = synchronized(this) { closeLocked() }

    private fun closeLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "HakuEmbed"
        /** Matryoshka truncation: 256 dims is plenty for passage ranking and 3x cheaper to compare. */
        const val DIM = 256
        private const val BATCH = 16
        private const val QUERY_PREFIX = "task: search query | text: "
        private const val DOC_PREFIX = "task: search result | text: "

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
            return s
        }
    }
}
