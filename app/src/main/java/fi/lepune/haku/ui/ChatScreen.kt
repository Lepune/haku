package fi.lepune.haku.ui

import android.content.Intent
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
    initialText: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSetup: () -> Unit,
    onModeChange: (Mode) -> Unit,
    onAskGemma: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
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
                    IconButton(onClick = onNewChat, enabled = state.engine is EngineState.Ready) {
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
                    state.messages.isEmpty() -> EmptyHints(state.settings.mode)
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            if (msg.role == Role.USER) UserBubble(msg.text)
                            else AssistantBlock(msg, canAsk = state.engine is EngineState.Ready && !state.generating, onAskGemma = onAskGemma)
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
private fun EmptyHints(mode: Mode) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Ask anything.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (mode) {
                Mode.SEARCH -> "Haku: results in seconds, no AI writing. Price questions (\"halvin …\", \"hinta\") " +
                    "give a list sorted by price; anything else gives web results. Tap \"Kysy Gemmalta\" " +
                    "under a result for a written answer."
                Mode.ASK -> "Kysy: Gemma answers and searches when it needs to."
                Mode.RESEARCH -> "Tutki: Gemma plans several searches, reads up to 6 pages and writes a sourced " +
                    "answer. Slowest, most thorough."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun AssistantBlock(msg: ChatMessage, canAsk: Boolean, onAskGemma: (String) -> Unit) {
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
        msg.instant?.let { InstantCard(it) }
        msg.results?.let { ResultsList(it) }

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
            if (msg.mode == Mode.SEARCH && msg.question != null && canAsk) {
                TextButton(onClick = { onAskGemma(msg.question) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Kysy Gemmalta →")
                }
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
                modifier = Modifier.weight(1f),
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
    val shown = if (finnishOnly) offers.filter { it.finnish } else offers
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (offers.isEmpty()) "No prices found" else "Cheapest first",
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
            )
            if (offers.any { it.finnish }) {
                FilterChip(
                    selected = finnishOnly, onClick = { finnishOnly = !finnishOnly },
                    label = { Text("Vain FI-yritykset") },
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
                    Text(
                        (if (o.from) "alk. " else "") + "%.2f €".format(o.price).replace('.', ','),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstantCard(h: Hit) {
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
            Text(h.snippet, style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
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
