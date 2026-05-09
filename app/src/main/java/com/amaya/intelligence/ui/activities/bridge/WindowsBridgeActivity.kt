package com.amaya.intelligence.ui.activities.bridge

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
import com.amaya.intelligence.ui.screens.bridge.WindowsBridgeScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Standalone Activity for the Windows Bridge management screen.
 *
 * Follows the same pattern as [RemoteSessionActivity] — separate Activity with
 * Hilt injection, Compose content, and a static `start()` helper.
 */
@AndroidEntryPoint
class WindowsBridgeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            AmayaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WindowsBridgeScreen(
                        onBack = { finish() },
                        onStartChat = {
                            WindowsBridgeChatActivity.start(this@WindowsBridgeActivity)
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, WindowsBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
