package com.amaya.intelligence.ui.activities.project.local

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.data.local.dao.ProjectDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.amaya.intelligence.ui.components.shared.PermissionRequirementSheet
import com.amaya.intelligence.ui.components.shared.PermissionType
import com.amaya.intelligence.ui.screens.project.local.LocalProjectBrowserScreen
import com.amaya.intelligence.ui.theme.AmayaTheme

@AndroidEntryPoint
class LocalProjectActivity : AppCompatActivity() {

    @Inject lateinit var projectDao: ProjectDao

    private var hasStoragePermission by mutableStateOf(false)
    private var showStoragePermissionSheet by mutableStateOf(false)
    private val workspaceOnly by lazy { intent.getBooleanExtra(EXTRA_WORKSPACE_ONLY, false) }

    private val legacyStoragePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                hasStoragePermission = true
                showStoragePermissionSheet = false
            } else {
                showStoragePermissionSheet = true
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkStoragePermission()
        showStoragePermissionSheet = !hasStoragePermission
        setContent {
            AmayaTheme {
                LocalProjectBrowserScreen(
                    onWorkspaceSelected = { workspacePath ->
                        val canonical = runCatching { java.io.File(workspacePath).canonicalPath }.getOrDefault(workspacePath)
                        if (workspaceOnly) {
                            setResult(RESULT_OK, Intent().putExtra(RESULT_KEY, canonical))
                            finish()
                        } else lifecycleScope.launch {
                            val existing = projectDao.getByRootPath(canonical)
                            if (existing != null) finishWithProject(existing.id, canonical)
                            else {
                                setResult(RESULT_OK, Intent().putExtra(RESULT_KEY, canonical))
                                finish()
                            }
                        }
                    },
                    onDismiss = {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                )

                if (!hasStoragePermission && showStoragePermissionSheet) {
                    PermissionRequirementSheet(
                        permissionType = PermissionType.STORAGE,
                        onGrant = {
                            showStoragePermissionSheet = false
                            requestStoragePermission()
                        },
                        onDismiss = {
                            if (!hasStoragePermission) {
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun finishWithProject(projectId: Long, workspacePath: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(RESULT_KEY, workspacePath).putExtra(RESULT_PROJECT_ID, projectId)
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        checkStoragePermission()
        if (!hasStoragePermission) {
            showStoragePermissionSheet = true
        }
    }

    private fun checkStoragePermission() {
        hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyStoragePermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    companion object {
        const val REQUEST_CODE = 1001
        const val RESULT_KEY = "workspace_path"
        const val RESULT_PROJECT_ID = "project_id"
        private const val EXTRA_WORKSPACE_ONLY = "workspace_only"

        fun workspacePickerIntent(context: android.content.Context): Intent =
            Intent(context, LocalProjectActivity::class.java).putExtra(EXTRA_WORKSPACE_ONLY, true)

        fun startWorkspacePickerForResult(activity: android.app.Activity) {
            activity.startActivityForResult(workspacePickerIntent(activity), REQUEST_CODE)
        }

        fun start(context: android.content.Context) {
            val intent = android.content.Intent(context, LocalProjectActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun startForResult(activity: android.app.Activity) {
            val intent = android.content.Intent(activity, LocalProjectActivity::class.java)
            activity.startActivityForResult(intent, REQUEST_CODE)
        }
    }
}
