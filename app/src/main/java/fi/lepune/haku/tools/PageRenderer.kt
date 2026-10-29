package fi.lepune.haku.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume

/**
 * Renders a page the way a person's browser would (Chromium WebView, JavaScript on, images off),
 * off-screen, and returns the finished DOM. Used only for shop pages whose prices are built by
 * JavaScript and invisible to a plain download.
 */
class PageRenderer(context: Context) {
    private val app = context.applicationContext
    private val gate = Semaphore(MAX_PARALLEL)

    suspend fun render(url: String, timeoutMs: Long = 7_000): Document? = gate.withPermit {
        withTimeoutOrNull(timeoutMs) {
            val html = withContext(Dispatchers.Main) { load(url) } ?: return@withTimeoutOrNull null
            withContext(Dispatchers.Default) { Jsoup.parse(html, url) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val wv = WebView(app)
        var finished = false

        fun finish(html: String?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            runCatching { wv.stopLoading(); wv.destroy() }
            if (cont.isActive) cont.resume(html)
        }

        fun capture() {
            if (finished) return
            wv.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                val html = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
                finish(html)
            }
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            userAgentString = WebTools.BROWSER_UA
        }
        // Give it a phone-sized viewport so responsive layouts render their mobile markup.
        wv.layout(0, 0, 1080, 2400)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                // Let client-side rendering and price widgets settle, then snapshot the DOM.
                handler.postDelayed({ capture() }, SETTLE_MS)
            }
        }
        // Safety net: snapshot whatever exists if the page never reports finished.
        handler.postDelayed({ capture() }, HARD_LIMIT_MS)
        cont.invokeOnCancellation { handler.post { finish(null) } }
        wv.loadUrl(url)
    }

    companion object {
        private const val MAX_PARALLEL = 2
        private const val SETTLE_MS = 1_200L
        private const val HARD_LIMIT_MS = 5_500L
    }
}
