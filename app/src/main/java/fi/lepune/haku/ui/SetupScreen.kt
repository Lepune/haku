package fi.lepune.haku.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fi.lepune.haku.data.BackendPref
import fi.lepune.haku.data.ModelCatalog
import fi.lepune.haku.data.ModelFileState
import fi.lepune.haku.data.ModelKind
import fi.lepune.haku.data.ModelSpec

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    state: UiState,
    vm: ChatViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models & settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EngineStatusCard(state.engine, onRetry = vm::loadSelectedIfPresent)

            StorageCard(state.sharedStorage)

            SectionTitle("Model")
            ModelCatalog.CHAT.forEach { spec ->
                ModelCard(
                    canMoveToShared = state.sharedStorage && spec.id in state.privateCopies,
                    onMoveToShared = { vm.moveToShared(spec) },
                    spec = spec,
                    selected = state.settings.modelId == spec.id,
                    file = state.files[spec.id] ?: ModelFileState.Missing,
                    importingBytes = state.importing?.takeIf { it.first == spec.id }?.second,
                    onSelect = { vm.selectModel(spec.id) },
                    onDownload = { vm.download(spec) },
                    onCancel = { vm.cancelDownload(spec) },
                    onDelete = { vm.delete(spec) },
                    onImport = { uri -> vm.import(spec, uri) },
                )
            }

            SectionTitle("Deep search ranking")
            ModelCatalog.EMBEDDER.let { spec ->
                ModelCard(
                    canMoveToShared = state.sharedStorage && spec.id in state.privateCopies,
                    onMoveToShared = { vm.moveToShared(spec) },
                    spec = spec,
                    selected = false,
                    selectable = false,
                    file = state.files[spec.id] ?: ModelFileState.Missing,
                    importingBytes = state.importing?.takeIf { it.first == spec.id }?.second,
                    onSelect = {},
                    onDownload = { vm.download(spec) },
                    onCancel = { vm.cancelDownload(spec) },
                    onDelete = { vm.delete(spec) },
                    onImport = { uri -> vm.import(spec, uri) },
                )
            }

            HorizontalDivider()
            SectionTitle("Accelerator")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                BackendPref.entries.forEachIndexed { i, b ->
                    SegmentedButton(
                        selected = state.settings.backend == b,
                        onClick = { vm.updateSettings(state.settings.copy(backend = b)) },
                        shape = SegmentedButtonDefaults.itemShape(i, BackendPref.entries.size),
                    ) { Text(b.name) }
                }
            }
            Hint(
                "GPU is the fast path. NPU only works with the Tensor G4 NPU test model " +
                    "(Gemma 4 has no Tensor G4 NPU build yet); with other models NPU falls back to GPU, then CPU."
            )
            SwitchRow(
                "Multi-token prediction (experimental)",
                "Faster generation, but may garble numbers and words. Leave off unless testing.",
                state.settings.speculativeDecoding,
            ) { vm.updateSettings(state.settings.copy(speculativeDecoding = it)) }

            HorizontalDivider()
            SectionTitle("Price alerts")
            if (state.watches.isEmpty()) {
                Hint("None yet. Tap \"Seuraa hintaa\" under a price list; Haku re-checks every ~6 h and notifies when it gets cheaper.")
            } else {
                state.watches.forEach { w ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.query, maxLines = 1)
                            Text(
                                "Lowest " + String.format(java.util.Locale.ROOT, "%.2f €", w.bestPrice).replace('.', ',') +
                                    " · ${w.bestShop}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.deleteWatch(w.id) }) { Text("Remove") }
                    }
                }
                OutlinedButton(onClick = vm::checkWatchesNow) { Text("Check now") }
            }

            HorizontalDivider()
            SectionTitle("History")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${state.recent.size} saved searches and chats, stored only on this phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearHistory, enabled = state.recent.isNotEmpty()) { Text("Clear") }
            }

            HorizontalDivider()
            SectionTitle("Search")
            var searx by remember(state.settings.searxngUrl) { mutableStateOf(state.settings.searxngUrl) }
            OutlinedTextField(
                value = searx,
                onValueChange = { searx = it },
                label = { Text("SearXNG URL (optional)") },
                placeholder = { Text("http://100.x.y.z:8080") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Empty = DuckDuckGo directly from the phone. Use the Tailscale name or IP so it works away from home.")
            var searxTest by remember { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { searxTest = "Testing…"; vm.testSearxng(searx) { searxTest = it } },
                    enabled = searx.isNotBlank() && searxTest != "Testing…",
                ) { Text("Test") }
                searxTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("fi-fi" to "Finland", "wt-wt" to "Global").forEach { (code, label) ->
                    Row(
                        Modifier.selectable(state.settings.searchRegion == code) {
                            vm.updateSettings(state.settings.copy(searchRegion = code))
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(state.settings.searchRegion == code, onClick = null)
                        Spacer(Modifier.width(4.dp)); Text(label)
                    }
                }
            }

            var token by remember(state.settings.hfToken) { mutableStateOf(state.settings.hfToken) }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Hugging Face token (only if a download is refused)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.updateSettings(state.settings.copy(searxngUrl = searx, hfToken = token)) },
                enabled = searx != state.settings.searxngUrl || token != state.settings.hfToken,
            ) { Text("Save") }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StorageCard(shared: Boolean) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Model storage", style = MaterialTheme.typography.labelLarge)
            if (shared) {
                Text("Download/Haku — kept if the app is uninstalled or reinstalled.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text("App-private — deleted on uninstall. Allow file access to keep models in " +
                    "Download/Haku instead, so reinstalls don't need a re-download.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }) { Text("Allow file access") }
            }
        }
    }
}

@Composable
private fun EngineStatusCard(e: EngineState, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text("Engine", style = MaterialTheme.typography.labelLarge)
            when (e) {
                EngineState.NoModel -> Text("No model loaded")
                is EngineState.Loading -> {
                    Text("Loading ${e.spec.name}…")
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                is EngineState.Ready -> {
                    Text(
                        "${e.model.spec.name} running on ${e.model.backend.name}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (e.model.backend == BackendPref.NPU) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Loaded in %.1f s".format(e.model.loadMs / 1000.0), style = MaterialTheme.typography.bodySmall)
                    e.model.fallbacks.forEach { (b, err) ->
                        Text("${b.name} failed: $err", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                is EngineState.Error -> {
                    SelectionContainer {
                        Text(e.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    canMoveToShared: Boolean,
    onMoveToShared: () -> Unit,
    spec: ModelSpec,
    selected: Boolean,
    selectable: Boolean = true,
    file: ModelFileState,
    importingBytes: Long?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onImport: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth().let { if (selectable) it.selectable(selected, onClick = onSelect) else it },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectable) {
                    RadioButton(selected, onClick = null)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(spec.name, fontWeight = FontWeight.SemiBold)
                    Text("~${"%.1f".format(spec.approxSizeMb / 1000.0)} GB · ${when { spec.kind == ModelKind.EMBEDDING -> "ranking"; spec.supportsTools -> "search tools"; else -> "chat only" }}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(spec.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(8.dp))

            if (importingBytes != null) {
                Text("Importing… ${importingBytes shr 20} MB", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                return@Column
            }

            when (file) {
                ModelFileState.Missing, is ModelFileState.Failed -> {
                    if (file is ModelFileState.Failed) {
                        Text(file.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDownload) { Text("Download") }
                        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Import file") }
                    }
                }
                is ModelFileState.Downloading -> {
                    val mb = file.downloaded shr 20
                    val totalMb = if (file.total > 0) file.total shr 20 else spec.approxSizeMb.toLong()
                    Text("Downloading $mb / $totalMb MB", style = MaterialTheme.typography.bodySmall)
                    if (file.total > 0) LinearProgressIndicator(progress = { file.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                is ModelFileState.Ready -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloaded · ${file.bytes shr 20} MB" + if (file.shared) " · Download/Haku" else " · app-private",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Delete") }
                    }
                    if (canMoveToShared) {
                        OutlinedButton(onClick = onMoveToShared) { Text("Move to Download/Haku") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(t: String) =
    Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
