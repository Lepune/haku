package fi.lepune.haku.data

import android.content.Context
import androidx.core.content.edit

enum class BackendPref(val label: String) {
    GPU("GPU"),
    CPU("CPU"),
    NPU("NPU (experimental)"),
}

data class Settings(
    val modelId: String = ModelCatalog.DEFAULT.id,
    val backend: BackendPref = BackendPref.GPU,
    /** Multi-token prediction. Off by default: suspected cause of duplicated tokens (garbled numbers/words). */
    val speculativeDecoding: Boolean = false,
    /** App-driven research: plan queries, read many pages, answer from extracted evidence. */
    val deepSearch: Boolean = false,
    val searxngUrl: String = "",
    val hfToken: String = "",
    val searchRegion: String = "fi-fi",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("haku", Context.MODE_PRIVATE)

    fun load(): Settings = Settings(
        modelId = prefs.getString("modelId", null)?.takeIf { ModelCatalog.byId(it) != null }
            ?: ModelCatalog.DEFAULT.id,
        backend = runCatching { BackendPref.valueOf(prefs.getString("backend", "GPU")!!) }
            .getOrDefault(BackendPref.GPU),
        speculativeDecoding = prefs.getBoolean("speculative_v2", false),
        deepSearch = prefs.getBoolean("deepSearch", false),
        searxngUrl = prefs.getString("searxngUrl", "") ?: "",
        hfToken = prefs.getString("hfToken", "") ?: "",
        searchRegion = prefs.getString("searchRegion", "fi-fi") ?: "fi-fi",
    )

    fun save(s: Settings) = prefs.edit {
        putString("modelId", s.modelId)
        putString("backend", s.backend.name)
        putBoolean("speculative_v2", s.speculativeDecoding)
        putBoolean("deepSearch", s.deepSearch)
        putString("searxngUrl", s.searxngUrl.trim())
        putString("hfToken", s.hfToken.trim())
        putString("searchRegion", s.searchRegion)
    }

    fun downloadId(modelId: String): Long = prefs.getLong("dl_$modelId", -1L)
    fun setDownloadId(modelId: String, id: Long) = prefs.edit {
        if (id < 0) remove("dl_$modelId") else putLong("dl_$modelId", id)
    }
}
