package fi.lepune.haku

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.lepune.haku.data.Mode
import fi.lepune.haku.ui.ChatScreen
import fi.lepune.haku.ui.ChatViewModel
import fi.lepune.haku.ui.HakuTheme
import fi.lepune.haku.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    /** Bumped to pull the keyboard up (assistant gesture, widget). */
    private var focusTick by mutableIntStateOf(0)
    /** Text to put in the input box (share sheet). */
    private var prefill by mutableStateOf<String?>(null)
    private var showSetup by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)

        setContent {
            HakuTheme {
                val state by vm.state.collectAsStateWithLifecycle()

                if (showSetup) {
                    BackHandler { showSetup = false }
                    SetupScreen(state = state, vm = vm, onBack = { showSetup = false })
                } else {
                    // One back swipe from a conversation returns to the home screen.
                    BackHandler(enabled = state.messages.isNotEmpty()) { vm.goHome() }
                    ChatScreen(
                        state = state,
                        prefill = prefill,
                        focusTick = focusTick,
                        onPrefillConsumed = { prefill = null },
                        onSend = vm::send,
                        onStop = vm::cancelGeneration,
                        onNewChat = vm::newChat,
                        onOpenSetup = { showSetup = true },
                        onModeChange = vm::setMode,
                        onAskGemma = vm::askAbout,
                        onOpenConversation = vm::openConversation,
                        onDeleteConversation = vm::deleteConversation,
                        onWatch = vm::watchPrice,
                        onDiscussions = vm::loadDiscussions,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_ASSIST, ACTION_FOCUS_SEARCH -> {
                // Fresh search screen with the keyboard up, like a search bar.
                showSetup = false
                vm.goHome()
                focusTick++
            }
            Intent.ACTION_WEB_SEARCH -> {
                val q = intent.getStringExtra(SearchManager.QUERY)
                if (!q.isNullOrBlank()) runSearch(q) else focusTick++
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val q = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!q.isNullOrBlank()) runSearch(q)
            }
            Intent.ACTION_SEND -> {
                prefill = intent.getStringExtra(Intent.EXTRA_TEXT)
                focusTick++
            }
        }
    }

    private fun runSearch(q: String) {
        showSetup = false
        vm.goHome()
        vm.send(q.trim().take(300), Mode.SEARCH)
    }

    companion object {
        const val ACTION_FOCUS_SEARCH = "fi.lepune.haku.FOCUS_SEARCH"
    }
}
