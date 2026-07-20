package com.amaya.intelligence.ui.activities.cronjob.local

import android.os.Bundle
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.entity.AgentEntity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.data.repository.CronJobRepository
import com.amaya.intelligence.ui.screens.cronjob.local.LocalCronJobScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LocalCronJobActivity : AppCompatActivity() {

    @Inject
    lateinit var cronJobRepository: CronJobRepository
    @javax.inject.Inject lateinit var agentDao: AgentDao
    private val agentId by lazy { intent.getLongExtra(EXTRA_AGENT_ID, -1L).takeIf { it > 0 } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                val agent = androidx.compose.runtime.produceState<AgentEntity?>(initialValue = null, agentId) {
                    value = agentId?.let { agentDao.getById(it) }
                }.value
                LocalCronJobScreen(
                    title = agent?.name?.let { "$it · Reminders" } ?: "Reminders",
                    ownerAgentId = agentId,
                    onNavigateBack = { finish() },
                    cronJobRepository = cronJobRepository
                )
            }
        }
    }

    companion object {
        private const val EXTRA_AGENT_ID = "agent_id"

        fun start(activity: android.app.Activity, agentId: Long? = null) {
            activity.startActivity(android.content.Intent(activity, LocalCronJobActivity::class.java).apply {
                agentId?.let { putExtra(EXTRA_AGENT_ID, it) }
            })
        }
    }
}
