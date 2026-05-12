package com.amaya.intelligence.ui.activities.opencode

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.amaya.intelligence.ui.screens.chat.opencode.OpencodeChatScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OpencodeChatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpencodeChatScreen(
                        onExit = { finish() },
                        onNavigateToSettings = {
                            OpencodeSettingsActivity.start(this@OpencodeChatActivity)
                        },
                        onNavigateToWorkspace = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, OpencodeChatActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
