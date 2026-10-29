package fi.lepune.haku.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

sealed interface ModelFileState {
    data object Missing : ModelFileState
    data class Downloading(val downloaded: Long, val total: Long) : ModelFileState {
        val fraction: Float get() = if (total > 0) downloaded.toFloat() / total else 0f
    }
    data class Ready(val bytes: Long, val shared: Boolean) : ModelFileState
    data class Failed(val reason: String) : ModelFileState
}

/**
 * Models live in one of two places:
 *  - shared: Download/Haku/ — survives uninstall/reinstall, visible in the Files app.
 *    Needs "All files access" (only way to read back files after a reinstall).
 *  - private: Android/data/<app>/files/models — deleted on uninstall.
 * New downloads go to shared whenever access is granted.
 */
class ModelManager(private val context: Context, private val store: SettingsStore) {
    private val dm = context.getSystemService(DownloadManager::class.java)

    fun hasSharedAccess(): Boolean = Environment.isExternalStorageManager()

    private fun privateDir(): File =
        (context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)).apply { mkdirs() }

    @Suppress("DEPRECATION")
    fun sharedDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SHARED_SUBDIR
    )

    private fun privateFile(spec: ModelSpec) = File(privateDir(), spec.fileName)
    private fun sharedFile(spec: ModelSpec) = File(sharedDir(), spec.fileName)

    private fun valid(f: File) = runCatching { f.exists() && f.length() > MIN_VALID_BYTES }.getOrDefault(false)

    /** The file the engine should load (or where a new download will land). */
    fun file(spec: ModelSpec): File {
        val shared = sharedFile(spec)
        if (hasSharedAccess() && valid(shared)) return shared
        val priv = privateFile(spec)
        if (valid(priv)) return priv
        return if (hasSharedAccess()) shared else priv
    }

    fun isPrivateCopy(spec: ModelSpec) = valid(privateFile(spec)) && !(hasSharedAccess() && valid(sharedFile(spec)))

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
                    store.setDownloadId(spec.id, -1)
                }
            }
        }
        val f = file(spec)
        return if (valid(f)) ModelFileState.Ready(f.length(), shared = f.parentFile == sharedDir())
        else ModelFileState.Missing
    }

    fun startDownload(spec: ModelSpec, hfToken: String) {
        cancel(spec)
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("Haku: ${spec.name}")
            .setDescription("Downloading model (~${spec.approxSizeMb} MB)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        if (hasSharedAccess()) {
            sharedDir().mkdirs()
            sharedFile(spec).delete()
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$SHARED_SUBDIR/${spec.fileName}")
        } else {
            privateFile(spec).delete()
            req.setDestinationInExternalFilesDir(context, MODELS_DIR, spec.fileName)
        }
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
        privateFile(spec).delete()
        if (hasSharedAccess()) sharedFile(spec).delete()
    }

    /** Copy a .litertlm file the user already has (e.g. copied over from a computer). */
    suspend fun import(spec: ModelSpec, uri: Uri, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        cancel(spec)
        val input = context.contentResolver.openInputStream(uri)
        requireNotNull(input) { "Could not open the selected file" }
        val target = if (hasSharedAccess()) sharedFile(spec) else privateFile(spec)
        copyInto(input, target, onProgress)
    }

    /** Move a model from app-private storage to Download/Haku so it survives reinstalls. */
    suspend fun moveToShared(spec: ModelSpec, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        check(hasSharedAccess()) { "All files access not granted" }
        val src = privateFile(spec)
        check(valid(src)) { "No private copy to move" }
        val dst = sharedFile(spec)
        sharedDir().mkdirs()
        if (!src.renameTo(dst)) {
            copyInto(src.inputStream(), dst, onProgress)
            src.delete()
        }
    }

    private fun copyInto(input: InputStream, target: File, onProgress: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        input.use { inp ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                var lastReport = 0L
                while (true) {
                    val n = inp.read(buf)
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
        check(tmp.renameTo(target)) { "Could not move file into place" }
    }

    private fun failureText(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network data error"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
        DownloadManager.ERROR_CANNOT_RESUME -> "Download could not resume"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
        401, 403 -> "Access denied ($reason). The model may need a Hugging Face token."
        404 -> "File not found on Hugging Face (404)"
        else -> "Download failed ($reason)"
    }

    companion object {
        const val MODELS_DIR = "models"
        const val SHARED_SUBDIR = "Haku"
        private const val MIN_VALID_BYTES = 50L shl 20
    }
}
