package com.amaya.intelligence.ui.activities.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.ui.screens.browser.BrowserOperatorScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BrowserOperatorActivity : AppCompatActivity() {
    @Inject lateinit var browserSessionManager: BrowserSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                BrowserOperatorScreen(
                    browserSessionManager = browserSessionManager,
                    onClose = { finish() }
                )
            }
        }
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, BrowserOperatorActivity::class.java))
        }
    }
}
