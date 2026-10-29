package fi.lepune.haku

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fi.lepune.haku.ui.ChatScreen
import fi.lepune.haku.ui.ChatViewModel
import fi.lepune.haku.ui.HakuTheme
import fi.lepune.haku.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)

        setContent {
            HakuTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                var showSetup by rememberSaveable { mutableStateOf(false) }

                if (showSetup) {
                    BackHandler { showSetup = false }
                    SetupScreen(state = state, vm = vm, onBack = { showSetup = false })
                } else {
                    ChatScreen(
                        state = state,
                        initialText = shared,
                        onSend = vm::send,
                        onStop = vm::cancelGeneration,
                        onNewChat = vm::newChat,
                        onOpenSetup = { showSetup = true },
                    )
                }
            }
        }
    }
}
