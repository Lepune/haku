package fi.lepune.haku.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import fi.lepune.haku.data.Mode
import fi.lepune.haku.search.Offer
import fi.lepune.haku.tools.Hit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: UiState,
    prefill: String?,
    focusTick: Int,
    onPrefillConsumed: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSetup: () -> Unit,
    onModeChange: (Mode) -> Unit,
    onAskGemma: (Long) -> Unit,
    onOpenConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onWatch: (Long) -> Unit,
    onDiscussions: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val watchWithPermission: (Long) -> Unit = { id ->
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        onWatch(id)
    }
    val watched = state.watches.map { it.query.lowercase() }.toSet()
    var input by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(prefill) {
        if (prefill != null) { input = prefill; onPrefillConsumed() }
    }
    LaunchedEffect(focusTick) {
        if (focusTick > 0) {
            kotlinx.coroutines.delay(200) // let the home screen compose first
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Haku", fontWeight = FontWeight.SemiBold)
                        Text(
                            engineLabel(state.engine),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat, enabled = state.messages.isNotEmpty()) {
                        Icon(Icons.Default.Add, "New chat")
                    }
                    IconButton(onClick = onOpenSetup) { Icon(Icons.Default.Settings, "Models and settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.engine !is EngineState.Ready && state.settings.mode != Mode.SEARCH &&
                        state.messages.isEmpty() -> EngineGate(state.engine, onOpenSetup)
                    state.messages.isEmpty() -> HomeScreen(state.settings.mode, state.recent,
                        onOpenConversation, onDeleteConversation)
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            if (msg.role == Role.USER) UserBubble(msg.text)
                            else AssistantBlock(
                                msg,
                                canAsk = state.engine is EngineState.Ready && !state.generating,
                                watched = msg.question?.lowercase()?.let { it in watched } == true,
                                onAskGemma = onAskGemma,
                                onWatch = watchWithPermission,
                                onDiscussions = onDiscussions,
                            )
                        }
                    }
                }
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = state.engine is EngineState.Ready || state.settings.mode == Mode.SEARCH,
                generating = state.generating,
                mode = state.settings.mode,
                onModeChange = onModeChange,
                focus = focus,
                onSend = { onSend(input); input = "" },
                onStop = onStop,
            )
        }
    }
}

private fun engineLabel(e: EngineState): String = when (e) {
    EngineState.NoModel -> "No model loaded"
    is EngineState.Loading -> "Loading ${e.spec.name}…"
    is EngineState.Error -> "${e.spec.name}: failed to load"
    is EngineState.Ready -> buildString {
        append(e.model.spec.name).append(" · ").append(e.model.backend.name)
        append(" · loaded in %.1f s".format(e.model.loadMs / 1000.0))
        if (e.model.fallbacks.isNotEmpty()) append(" (fallback)")
    }
}

@Composable
private fun EngineGate(e: EngineState, onOpenSetup: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (e) {
            is EngineState.Loading -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Loading ${e.spec.name}…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "First load compiles GPU kernels and can take ~10–20 s. Later loads are faster.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is EngineState.Error -> {
                Text("Could not load ${e.spec.name}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(e.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSetup) { Text("Models & settings") }
            }
            else -> {
                Icon(Icons.Default.Search, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Download a model to start", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Gemma 4 E4B is ~3.7 GB, one-time. Everything runs on the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSetup) { Text("Choose model") }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    mode: Mode,
    recent: List<Conversation>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        item {
            Text(
                when (mode) {
                    Mode.SEARCH -> "Haku: results in seconds, no AI writing. Price questions (\"halvin …\", \"hinta\") " +
                        "give a list sorted by price; anything else gives web results."
                    Mode.ASK -> "Kysy: Gemma answers and searches when it needs to."
                    Mode.RESEARCH -> "Tutki: Gemma searches several sources, reads the pages and writes a " +
                        "sourced answer. Slowest, most thorough."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        if (recent.isNotEmpty()) {
            item {
                Text("Recent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            items(recent, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(c.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            c.mode.label + " · " + relativeTime(c.updatedAt),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = { onDelete(c.id) }) {
                        Icon(Icons.Default.Close, "Delete", tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

private fun relativeTime(t: Long): String {
    val min = (System.currentTimeMillis() - t) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 24 * 60 -> "${min / 60} h ago"
        min < 7 * 24 * 60 -> "${min / (24 * 60)} d ago"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun AssistantBlock(
    msg: ChatMessage,
    canAsk: Boolean,
    watched: Boolean,
    onAskGemma: (Long) -> Unit,
    onWatch: (Long) -> Unit,
    onDiscussions: (Long) -> Unit,
) {
    var showSteps by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (msg.steps.isNotEmpty()) {
            Text(
                (if (showSteps) "▾ " else "▸ ") + "${msg.steps.size} tool step" + (if (msg.steps.size > 1) "s" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showSteps = !showSteps }.padding(vertical = 4.dp),
            )
            AnimatedVisibility(showSteps) {
                Column(Modifier.padding(start = 8.dp, bottom = 6.dp)) {
                    msg.steps.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        msg.offers?.let { OffersCard(it) }
        msg.site?.let { SiteCard(it) }
        msg.instant?.let { InstantCard(it, compact = msg.site != null) }
        msg.results?.let { r ->
            val rest = if (msg.site != null) r.filterNot { it.url == msg.site.url } else r
            if (rest.isNotEmpty() || msg.site == null) ResultsList(rest)
        }

        if (msg.text.isNotEmpty()) {
            SelectionContainer { Markdown(content = msg.text, modifier = Modifier.fillMaxWidth()) }
        }

        msg.status?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }

        msg.error?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }

        if (msg.done) {
            if (msg.mode != Mode.SEARCH) {
                val chips = msg.read.ifEmpty { msg.searched.take(3) }
                if (chips.isNotEmpty()) SourceChips(chips)
            }
            msg.stats?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
            }
            if (msg.mode == Mode.SEARCH && msg.question != null) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (!msg.offers.isNullOrEmpty()) {
                        AssistChip(
                            onClick = { if (!watched) onWatch(msg.id) },
                            label = { Text(if (watched) "Seurataan ✓" else "Seuraa hintaa") },
                        )
                    }
                    if (msg.discussions == null) {
                        AssistChip(
                            onClick = { onDiscussions(msg.id) },
                            enabled = !msg.loadingDiscussions,
                            label = { Text(if (msg.loadingDiscussions) "Haetaan…" else "Keskustelut") },
                        )
                    }
                    if (canAsk) {
                        AssistChip(onClick = { onAskGemma(msg.id) }, label = { Text("Kysy Gemmalta") })
                    }
                }
            }
            msg.discussions?.let { d ->
                Text("Keskustelut", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                if (d.isEmpty()) Text("No discussion threads found.", style = MaterialTheme.typography.bodySmall)
                else ResultsList(d)
            }
        }
    }
}

@Composable
private fun SourceChips(hits: List<Hit>) {
    val ctx = LocalContext.current
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        hits.forEach { h ->
            AssistChip(
                onClick = {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) }
                },
                label = {
                    Text(
                        Uri.parse(h.url).host?.removePrefix("www.") ?: h.title,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    generating: Boolean,
    mode: Mode,
    onModeChange: (Mode) -> Unit,
    focus: FocusRequester,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
      Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Mode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    enabled = !generating,
                    label = { Text(m.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text("Kysy / Ask…") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!generating && value.isNotBlank()) onSend() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Spacer(Modifier.size(8.dp))
            if (generating) {
                FilledIconButton(onClick = onStop) { Icon(Icons.Default.Close, "Stop") }
            } else {
                FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
      }
    }
}

// ---------------------------------------------------------------------- search-mode cards

@Composable
private fun OffersCard(offers: List<Offer>) {
    val ctx = LocalContext.current
    var finnishOnly by remember { mutableStateOf(false) }
    var byTotal by remember { mutableStateOf(false) }
    val filtered = if (finnishOnly) offers.filter { it.finnish } else offers
    // Unknown shipping sorts after known totals so "with delivery" ranking stays honest.
    val shown = if (byTotal) filtered.sortedWith(compareBy<Offer>({ it.total == null }, { it.total ?: it.price })) else filtered
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (offers.isEmpty()) "No prices found" else "Cheapest first",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (offers.any { it.finnish }) {
                FilterChip(
                    selected = finnishOnly, onClick = { finnishOnly = !finnishOnly },
                    label = { Text("Vain FI-yritykset") },
                )
            }
            if (offers.any { it.shipping != null }) {
                FilterChip(
                    selected = byTotal, onClick = { byTotal = !byTotal },
                    label = { Text("Sis. toimitus") },
                )
            }
        }
        if (offers.isEmpty()) {
            Text(
                "No shop page listed a matching product with a price. Try other words, or ask Gemma.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        shown.forEach { o ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o.url))) }
                },
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(o.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = buildList {
                            add(o.shop)
                            o.via?.let { add("via $it") }
                            when (o.inStock) { true -> add("in stock"); false -> add("out of stock"); null -> {} }
                            o.shipping?.let { add(if (it == 0.0) "free delivery" else "delivery " + euro(it)) }
                            if (o.fromSnippet) add("price from search snippet")
                        }.joinToString(" · ")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (o.finnish) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(end = 6.dp),
                                ) { Text("FI", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)) }
                            }
                            Text(meta, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.size(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            (if (o.from) "alk. " else "") + euro(o.price),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        )
                        o.total?.takeIf { o.shipping != 0.0 }?.let {
                            Text("yht. " + euro(it), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstantCard(h: Hit, compact: Boolean = false) {
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) }
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(h.title, style = MaterialTheme.typography.titleSmall)
            Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 3 else 8,
                overflow = TextOverflow.Ellipsis)
            Text("Wikipedia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun ResultsList(results: List<Hit>) {
    val ctx = LocalContext.current
    if (results.isEmpty()) {
        Text("No results.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(Modifier.fillMaxWidth()) {
        results.forEach { h ->
            Column(
                Modifier.fillMaxWidth().clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) }
                }.padding(vertical = 6.dp)
            ) {
                Text(Uri.parse(h.url).host?.removePrefix("www.") ?: "", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline)
                Text(h.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (h.snippet.isNotBlank()) {
                    Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun euro(v: Double) = String.format(java.util.Locale.ROOT, "%.2f €", v).replace('.', ',')

/** Big "go to site" card for navigational queries. */
@Composable
private fun SiteCard(h: Hit) {
    val ctx = LocalContext.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url))) }
        },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Uri.parse(h.url).host?.removePrefix("www.") ?: h.url,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(h.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("Avaa →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
