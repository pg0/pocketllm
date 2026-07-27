package com.redcoralstudios.pocketllm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.redcoralstudios.pocketllm.ui.ChatScreen
import com.redcoralstudios.pocketllm.ui.ChatViewModel
import com.redcoralstudios.pocketllm.ui.PocketLlmTheme

/**
 * Single screen. The model starts loading from the view model's constructor, so
 * the chat is the first and only thing the user sees.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PocketLlmTheme {
                ChatScreen(viewModel)
            }
        }
    }
}
