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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fi.lepune.haku.data.SettingsStore
import fi.lepune.haku.search.ShopSearch
import fi.lepune.haku.tools.WebTools

/** Re-runs each saved price search (no browser rendering, no model) and notifies on a lower price. */
class PriceCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val watches = PriceWatches(applicationContext)
        val store = SettingsStore(applicationContext)
        val shop = ShopSearch(WebTools { store.load() })
        for (w in watches.list()) {
            val r = runCatching { shop.run(w.query, step = {}) }.getOrNull() ?: continue
            val best = r.offers.filterNot { it.fromSnippet || it.from }.minByOrNull { it.price }
            val now = System.currentTimeMillis()
            if (best != null && best.price < w.bestPrice - 0.01) {
                notify(w, best.price, best.shop, best.url)
                watches.upsert(w.copy(bestPrice = best.price, bestShop = best.shop, bestUrl = best.url, lastChecked = now))
            } else {
                watches.upsert(w.copy(lastChecked = now))
            }
        }
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notify(w: Watch, price: Double, shop: String, url: String) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Price alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, w.id.toInt(), Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val fmt = { v: Double -> String.format(java.util.Locale.ROOT, "%.2f €", v).replace('.', ',') }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Halvempi: ${fmt(price)} · $shop")
            .setContentText("${w.query} — oli ${fmt(w.bestPrice)} (${w.bestShop})")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(w.id.toInt(), n)
    }

    companion object {
        private const val CHANNEL = "price_alerts"
    }
}
