package fi.lepune.haku.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fi.lepune.haku.data.SettingsStore
import fi.lepune.haku.search.ToriIndex
import fi.lepune.haku.tools.WebTools
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A semantic search saved on the Tori index server; the phone polls it for new matches. */
data class Hunt(val id: Long, val query: String, val question: String, val created: Long)

class ToriHunts(private val context: Context) {
    private val file = File(context.filesDir, "tori_hunts.json")

    @Synchronized
    fun list(): List<Hunt> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Hunt(o.getLong("id"), o.getString("q"), o.optString("question", o.getString("q")), o.optLong("created"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(h: Hunt) {
        write(list().filterNot { it.id == h.id || it.query.equals(h.query, true) } + h)
        schedule()
    }

    /** Stops polling it on this phone. */
    @Synchronized
    fun delete(id: Long) {
        write(list().filterNot { it.id == id })
        schedule()
    }

    private fun write(all: List<Hunt>) {
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().put("id", it.id).put("q", it.query).put("question", it.question).put("created", it.created))
        }
        file.writeText(arr.toString())
    }

    /** Hourly while any hunt exists: the server indexes new listings within about an hour. */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (list().isEmpty()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<ToriHuntWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    companion object {
        private const val WORK_NAME = "tori-hunts"
    }
}

/** Polls each hunt's unseen hits and posts one notification per new listing (max 3 per hunt per run). */
class ToriHuntWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = SettingsStore(applicationContext)
        val index = ToriIndex(WebTools { store.load() }) { store.load().toriUrl }
        if (!index.enabled) return Result.success()
        for (h in ToriHunts(applicationContext).list()) {
            val hits = runCatching { index.newHits(h.id) }.getOrNull() ?: continue
            hits.take(3).forEach { notify(h, it) }
        }
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(h: Hunt, item: fi.lepune.haku.search.ToriItem) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tori hunts", NotificationManager.IMPORTANCE_DEFAULT))
        val nid = (item.id.hashCode() and 0x7fffffff)
        val open = PendingIntent.getActivity(
            ctx, nid, Intent(Intent.ACTION_VIEW, Uri.parse(item.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val price = item.price?.let { String.format(java.util.Locale.ROOT, "%.0f €", it) } ?: "?"
        val deal = item.dealPercent?.let { " · −$it % vs similar" } ?: ""
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Tori: $price · ${item.title}")
            .setContentText("${h.query}${item.city?.let { " · $it" } ?: ""}$deal")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(nid, n)
    }

    companion object {
        private const val CHANNEL = "tori_hunts"
    }
}
