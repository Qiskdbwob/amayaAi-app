package com.amaya.intelligence.ui.activities.project.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.ui.screens.project.local.LocalProjectListScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LocalProjectListActivity : AppCompatActivity() {
    @Inject lateinit var projectDao: ProjectDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                val projects by projectDao.observeAll().collectAsState(initial = emptyList())
                LocalProjectListScreen(
                    projects = projects,
                    snackbarHostState = remember { SnackbarHostState() },
                    onNavigateBack = { finish() },
                    onAddProject = { LocalProjectActivity.startForResult(this) },
                    onOpenProject = { LocalProjectDetailActivity.startForResult(this, it.id) }
                )
            }
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LocalProjectActivity.REQUEST_CODE && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(LocalProjectActivity.RESULT_KEY)
            val projectId = data?.getLongExtra(LocalProjectActivity.RESULT_PROJECT_ID, -1L) ?: -1L
            if (path != null && projectId > 0L) finishWithProject(projectId, path)
        }
    }

    private fun finishWithProject(projectId: Long, path: String) {
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(LocalProjectActivity.RESULT_KEY, path)
                .putExtra(LocalProjectActivity.RESULT_PROJECT_ID, projectId)
        )
        finish()
    }

    companion object {
        const val REQUEST_CODE = 1010
        fun startForResult(activity: Activity) {
            activity.startActivityForResult(Intent(activity, LocalProjectListActivity::class.java), REQUEST_CODE)
        }
    }
}
