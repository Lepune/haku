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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
                    state.engine !is EngineState.Ready -> EngineGate(state.engine, onOpenSetup)
                    state.messages.isEmpty() -> EmptyHints(onPick = { input = it })
                    else -> LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.messages, key = { it.id }) { msg ->
                            if (msg.role == Role.USER) UserBubble(msg.text) else AssistantBlock(msg)
                        }
                    }
                }
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = state.engine is EngineState.Ready,
                generating = state.generating,
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
private fun EmptyHints(onPick: (String) -> Unit) {
    val hints = listOf(
        "Kuka on Suomen nykyinen pääministeri?",
        "Mikä on Jyväskylän väkiluku?",
        "Explain ACSR conductor construction",
        "Bitcoinin hinta euroina tänään",
    )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Ask anything. Haku searches when it needs to.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        hints.forEach { h ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPick(h) },
            ) { Text(h, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium) }
        }
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
private fun AssistantBlock(msg: ChatMessage) {
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
            val chips = msg.read.ifEmpty { msg.searched.take(3) }
            if (chips.isNotEmpty()) SourceChips(chips)
            msg.stats?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
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
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
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
