package fi.lepune.haku.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ModelFileState {
    data object Missing : ModelFileState
    data class Downloading(val downloaded: Long, val total: Long) : ModelFileState {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Ready(val bytes: Long) : ModelFileState
    data class Failed(val reason: String) : ModelFileState
}

class ModelManager(private val context: Context, private val store: SettingsStore) {
    private val dm = context.getSystemService(DownloadManager::class.java)

    private fun dir(): File =
        (context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)).apply { mkdirs() }

    fun file(spec: ModelSpec) = File(dir(), spec.fileName)

    fun state(spec: ModelSpec): ModelFileState {
        val id = store.downloadId(spec.id)
        if (id >= 0) {
            dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    when (status) {
                        DownloadManager.STATUS_RUNNING,
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED -> return ModelFileState.Downloading(done, total)
                        DownloadManager.STATUS_SUCCESSFUL -> store.setDownloadId(spec.id, -1)
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            store.setDownloadId(spec.id, -1)
                            dm.remove(id)
                            return ModelFileState.Failed(failureText(reason))
                        }
                    }
                } else {
                    store.setDownloadId(spec.id, -1) // download was removed from the system
                }
            }
        }
        val f = file(spec)
        return if (f.exists() && f.length() > MIN_VALID_BYTES) ModelFileState.Ready(f.length())
        else ModelFileState.Missing
    }

    fun startDownload(spec: ModelSpec, hfToken: String) {
        cancel(spec)
        file(spec).delete()
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("Haku: ${spec.name}")
            .setDescription("Downloading model (~${spec.approxSizeMb} MB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, MODELS_DIR, spec.fileName)
        if (hfToken.isNotBlank()) req.addRequestHeader("Authorization", "Bearer ${hfToken.trim()}")
        store.setDownloadId(spec.id, dm.enqueue(req))
    }

    fun cancel(spec: ModelSpec) {
        val id = store.downloadId(spec.id)
        if (id >= 0) dm.remove(id)
        store.setDownloadId(spec.id, -1)
    }

    fun delete(spec: ModelSpec) {
        cancel(spec)
        file(spec).delete()
    }

    /** Copy a .litertlm file the user already has (e.g. sent over from the laptop). */
    suspend fun import(spec: ModelSpec, uri: Uri, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        cancel(spec)
        val target = file(spec)
        val tmp = File(target.parentFile, target.name + ".part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open the selected file" }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total - lastReport > 32L shl 20) {
                        onProgress(total); lastReport = total
                    }
                }
                onProgress(total)
            }
        }
        target.delete()
        check(tmp.renameTo(target)) { "Could not move imported file into place" }
    }

    private fun failureText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network data error"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
        DownloadManager.ERROR_CANNOT_RESUME -> "Download could not resume"
        401, 403 -> "Access denied ($reason). The model may need a Hugging Face token."
        404 -> "File not found on Hugging Face (404)"
        else -> "Download failed ($reason)"
    }

    companion object {
        const val MODELS_DIR = "models"
        private const val MIN_VALID_BYTES = 50L shl 20
    }
}
