package fi.lepune.haku.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** A saved price search: notify when anything matching gets cheaper than [bestPrice]. */
data class Watch(
    val id: Long,
    val query: String,
    val bestPrice: Double,
    val bestShop: String,
    val bestUrl: String,
    val lastChecked: Long,
)

class PriceWatches(private val context: Context) {
    private val file = File(context.filesDir, "watches.json")

    @Synchronized
    fun list(): List<Watch> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Watch(o.getLong("id"), o.getString("q"), o.getDouble("p"), o.optString("shop"),
                o.optString("u"), o.optLong("checked"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun upsert(w: Watch) {
        val all = list().filterNot { it.id == w.id || it.query.equals(w.query, true) } + w
        write(all)
        schedule()
    }

    @Synchronized
    fun delete(id: Long) {
        write(list().filterNot { it.id == id })
        schedule()
    }

    private fun write(all: List<Watch>) {
        val arr = JSONArray()
        all.forEach {
            arr.put(JSONObject().put("id", it.id).put("q", it.query).put("p", it.bestPrice)
                .put("shop", it.bestShop).put("u", it.bestUrl).put("checked", it.lastChecked))
        }
        file.writeText(arr.toString())
    }

    /** Periodic check every 6 h while at least one watch exists. */
    fun schedule() {
        val wm = WorkManager.getInstance(context)
        if (list().isEmpty()) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<PriceCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun checkNow() {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<PriceCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    companion object {
        private const val WORK_NAME = "price-watch"
    }
}
