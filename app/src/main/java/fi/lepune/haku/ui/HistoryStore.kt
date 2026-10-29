package fi.lepune.haku.ui

import android.content.Context
import fi.lepune.haku.data.Mode
import fi.lepune.haku.search.Offer
import fi.lepune.haku.search.ToriItem
import fi.lepune.haku.tools.Hit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Conversation(
    val id: Long,
    val title: String,
    val mode: Mode,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
)

/** Recent searches and chats, kept as one small JSON file in app storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    @Synchronized
    fun list(): List<Conversation> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { runCatching { conv(arr.getJSONObject(it)) }.getOrNull() }
    }.getOrDefault(emptyList()).sortedByDescending { it.updatedAt }

    @Synchronized
    fun save(c: Conversation) {
        val all = list().filterNot { it.id == c.id }.toMutableList()
        all.add(0, c)
        write(all.take(MAX))
    }

    @Synchronized
    fun delete(id: Long) = write(list().filterNot { it.id == id })

    @Synchronized
    fun clear() { file.delete() }

    private fun write(all: List<Conversation>) {
        val arr = JSONArray()
        all.forEach { arr.put(json(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    // ------------------------------------------------------------------ JSON

    private fun json(c: Conversation) = JSONObject()
        .put("id", c.id).put("title", c.title).put("mode", c.mode.name).put("updatedAt", c.updatedAt)
        .put("messages", JSONArray().apply { c.messages.forEach { put(json(it)) } })

    private fun conv(o: JSONObject) = Conversation(
        id = o.getLong("id"),
        title = o.optString("title"),
        mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.ASK),
        updatedAt = o.optLong("updatedAt"),
        messages = o.optJSONArray("messages")?.let { a -> (0 until a.length()).map { msg(a.getJSONObject(it)) } }
            ?: emptyList(),
    )

    private fun json(m: ChatMessage) = JSONObject().apply {
        put("id", m.id); put("role", m.role.name); put("text", m.text); put("mode", m.mode.name)
        m.question?.let { put("question", it) }
        m.stats?.let { put("stats", it) }
        m.error?.let { put("error", it) }
        if (m.steps.isNotEmpty()) put("steps", JSONArray(m.steps))
        if (m.read.isNotEmpty()) put("read", hits(m.read))
        if (m.searched.isNotEmpty()) put("searched", hits(m.searched.take(5)))
        if (m.sources.isNotEmpty()) put("sources", hits(m.sources.map { it.copy(snippet = "") }))
        m.results?.let { put("results", hits(it)) }
        m.instant?.let { put("instant", hit(it)) }
        m.discussions?.let { put("disc", hits(it)) }
        m.site?.let { put("site", hit(it)) }
        m.offers?.let { put("offers", JSONArray().apply { it.forEach { o -> put(offer(o)) } }) }
        m.tori?.let { put("tori", JSONArray().apply { it.forEach { t -> put(toriJson(t)) } }) }
    }

    private fun msg(o: JSONObject) = ChatMessage(
        id = o.getLong("id"),
        role = runCatching { Role.valueOf(o.getString("role")) }.getOrDefault(Role.ASSISTANT),
        text = o.optString("text"),
        mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.ASK),
        question = o.optString("question").ifEmpty { null },
        stats = o.optString("stats").ifEmpty { null },
        error = o.optString("error").ifEmpty { null },
        steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        read = o.optJSONArray("read")?.let(::hitsOf) ?: emptyList(),
        searched = o.optJSONArray("searched")?.let(::hitsOf) ?: emptyList(),
        sources = o.optJSONArray("sources")?.let(::hitsOf) ?: emptyList(),
        results = o.optJSONArray("results")?.let(::hitsOf),
        instant = o.optJSONObject("instant")?.let(::hitOf),
        discussions = o.optJSONArray("disc")?.let(::hitsOf),
        site = o.optJSONObject("site")?.let(::hitOf),
        offers = o.optJSONArray("offers")?.let { a -> (0 until a.length()).map { offerOf(a.getJSONObject(it)) } },
        tori = o.optJSONArray("tori")?.let { a -> (0 until a.length()).map { toriOf(a.getJSONObject(it)) } },
        done = true,
    )

    private fun hit(h: Hit) = JSONObject().put("t", h.title).put("u", h.url).put("s", h.snippet)
    private fun hitOf(o: JSONObject) = Hit(o.optString("t"), o.optString("u"), o.optString("s"))
    private fun hits(l: List<Hit>) = JSONArray().apply { l.forEach { put(hit(it)) } }
    private fun hitsOf(a: JSONArray) = (0 until a.length()).map { hitOf(a.getJSONObject(it)) }

    private fun toriJson(t: ToriItem) = JSONObject().apply {
        put("id", t.id); put("t", t.title); put("u", t.url)
        t.price?.let { put("p", it) }; t.city?.let { put("c", it) }
        t.publishedAt?.let { put("d", it) }; t.dealRatio?.let { put("r", it) }
        if (t.similar) put("sim", true)
    }

    private fun toriOf(o: JSONObject) = ToriItem(
        id = o.optString("id"), title = o.optString("t"), url = o.optString("u"),
        price = if (o.has("p")) o.optDouble("p") else null,
        city = o.optString("c").ifEmpty { null },
        publishedAt = o.optString("d").ifEmpty { null },
        dealRatio = if (o.has("r")) o.optDouble("r") else null,
        similar = o.optBoolean("sim"),
    )

    private fun offer(o: Offer) = JSONObject().apply {
        put("n", o.name); put("p", o.price); put("shop", o.shop); put("u", o.url)
        o.inStock?.let { put("stock", it) }
        put("fi", o.finnish); o.via?.let { put("via", it) }
        put("from", o.from); put("snip", o.fromSnippet)
        o.shipping?.let { put("ship", it) }
        o.fx?.let { put("fx", it) }
    }

    private fun offerOf(o: JSONObject) = Offer(
        name = o.optString("n"), price = o.optDouble("p"), shop = o.optString("shop"), url = o.optString("u"),
        inStock = if (o.has("stock")) o.optBoolean("stock") else null,
        finnish = o.optBoolean("fi"), via = o.optString("via").ifEmpty { null },
        from = o.optBoolean("from"), fromSnippet = o.optBoolean("snip"),
        shipping = if (o.has("ship")) o.optDouble("ship") else null,
        fx = o.optString("fx").ifEmpty { null },
    )

    companion object {
        private const val MAX = 100
    }
}
