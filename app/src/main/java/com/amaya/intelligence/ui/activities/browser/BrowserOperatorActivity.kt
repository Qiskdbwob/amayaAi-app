package com.amaya.intelligence.ui.activities.browser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.impl.local.browser.BrowserSessionManager
import com.amaya.intelligence.ui.screens.browser.BrowserOperatorScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class BrowserOperatorActivity : AppCompatActivity() {
    @Inject lateinit var browserSessionManager: BrowserSessionManager
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        browserSessionManager.provideUploadUris(uris?.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!browserSessionManager.canOpenOperator()) {
            Toast.makeText(this, "Browser requires an active Agent conversation", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Keep GeckoView coordinates aligned with the visible browser viewport.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            AmayaTheme {
                BrowserOperatorScreen(
                    browserSessionManager = browserSessionManager,
                    onClose = { finish() },
                    onPickFiles = filePicker::launch,
                    onOpenDownload = { download ->
                        browserSessionManager.openDownload(download)?.let { uri ->
                            startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, download.mimeType); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                        }
                    },
                    onDeleteDownload = browserSessionManager::deleteDownload,
                    onAuthHandoff = {
                        val url = browserSessionManager.uiState.value.activeUrl
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            CustomTabsIntent.Builder().build().launchUrl(this@BrowserOperatorActivity, Uri.parse(url))
                        }
                    }
                )
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= 10) browserSessionManager.releaseInactiveRuntimes()
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, BrowserOperatorActivity::class.java))
        }
    }
}
