package com.amaya.intelligence.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.ContextCompat
import com.amaya.intelligence.R
import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.domain.models.RunningSession
import com.amaya.intelligence.domain.models.SessionPhase
import com.amaya.intelligence.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Projects local turns into separate activity, message, approval, and issue channels. */
@AndroidEntryPoint
class AiSessionNotificationService : Service() {
    @Inject lateinit var intelligenceService: IntelligenceService
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var completionCollector: Job? = null
    private var idleStopJob: Job? = null
    private var visibleActivityIds = emptySet<Int>()
    private var visibleApprovalIds = emptySet<Int>()
    private var liveConversationId: Long? = null
    private val publishedShortcutIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        promote(activitySummary(intelligenceService.runningSessions.value))
        removeLegacyConversationShortcuts()
        completionCollector = serviceScope.launch { intelligenceService.completedSessions.collect(::postTerminalNotification) }
        collector = serviceScope.launch {
            intelligenceService.runningSessions.collect { sessions ->
                idleStopJob?.cancel()
                idleStopJob = null
                if (sessions.isEmpty()) {
                    idleStopJob = serviceScope.launch {
                        delay(250)
                        if (intelligenceService.runningSessions.value.isEmpty()) finishForegroundWork()
                    }
                } else updateNotifications(sessions)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        idleStopJob?.cancel()
        idleStopJob = null
        promote(activitySummary(intelligenceService.runningSessions.value))
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collector?.cancel()
        completionCollector?.cancel()
        idleStopJob?.cancel()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun finishForegroundWork() {
        val manager = NotificationManagerCompat.from(this)
        visibleActivityIds.forEach(manager::cancel)
        visibleApprovalIds.forEach(manager::cancel)
        manager.cancel(ACTIVITY_SUMMARY_ID)
        visibleActivityIds = emptySet()
        visibleApprovalIds = emptySet()
        liveConversationId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotifications(sessions: List<RunningSession>) {
        val manager = NotificationManagerCompat.from(this)
        val activityIds = sessions.mapTo(mutableSetOf()) { activityId(it.notificationThreadKey) }
        val approvals = sessions.filter { it.approvalId != null }
        val approvalIds = approvals.mapNotNullTo(mutableSetOf()) { it.approvalId?.let(::approvalId) }
        (visibleActivityIds - activityIds).forEach(manager::cancel)
        (visibleApprovalIds - approvalIds).forEach(manager::cancel)

        val retained = liveConversationId?.let { id ->
            sessions.firstOrNull { it.conversationId == id && it.mode == AssistantMode.AGENT && it.isDelegating }
        }
        val live = retained ?: sessions.filter { it.mode == AssistantMode.AGENT && it.isDelegating }
            .minByOrNull(RunningSession::updatedAt)
        liveConversationId = live?.conversationId

        sessions.forEach { session ->
            publishConversationShortcut(session.conversationId, session.notificationThreadKey, session.notificationTitle, session.mode)
            manager.notify(activityId(session.notificationThreadKey), activityNotification(session))
        }
        approvals.forEach { session -> session.approvalId?.let { manager.notify(approvalId(it), approvalNotification(session)) } }
        val summary = activitySummary(sessions)
        manager.notify(ACTIVITY_SUMMARY_ID, summary)
        visibleActivityIds = activityIds
        visibleApprovalIds = approvalIds
        promote(summary)
    }

    private fun activitySummary(sessions: List<RunningSession>): Notification =
        NotificationCompat.Builder(this, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (sessions.isEmpty()) "Starting AI activity" else "${sessions.size} active ${if (sessions.size == 1) "session" else "sessions"}")
            .setContentText(activitySummaryLine(sessions))
            .setStyle(NotificationCompat.InboxStyle().also { style ->
                sessions.take(6).forEach { style.addLine("${it.title}: ${activityPrimary(it)}") }
            })
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(GROUP_ACTIVITY)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(null))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun activityNotification(session: RunningSession): Notification {
        val live = session.mode == AssistantMode.AGENT && session.isDelegating && session.conversationId == liveConversationId
        val builder = NotificationCompat.Builder(this, CHANNEL_ACTIVITY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(session.notificationTitle)
            .setContentText(activityPrimary(session))
            .setStyle(activityStyle(session, live))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setGroup(GROUP_ACTIVITY)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(session.conversationId))
            .setDeleteIntent(dismissIntent(session.conversationId))
            .setShortcutId(shortcutId(session.notificationThreadKey))
            .setLocusId(locusId(session.notificationThreadKey))

        if (live && AiSessionNotificationStore.canRequestPromotion(this, session.conversationId)) {
            builder.setRequestPromotedOngoing(true).setShortCriticalText(liveChipLabel(session))
        }
        val notification = builder.build()
        if (live && Build.VERSION.SDK_INT >= 36 && NotificationCompat.isRequestPromotedOngoing(notification)) {
            NotificationCompat.hasPromotableCharacteristics(notification)
            NotificationManagerCompat.from(this).canPostPromotedNotifications()
        }
        return notification
    }

    private fun activityStyle(session: RunningSession, live: Boolean): NotificationCompat.Style {
        if (!live) return NotificationCompat.BigTextStyle().bigText(activityExpanded(session))
        val total = session.totalDelegates
        val style = NotificationCompat.ProgressStyle()
            .setProgressIndeterminate(total <= 0)
            .setProgress(if (total > 0) session.completedDelegates.coerceIn(0, total) else 0)
            .setProgressTrackerIcon(IconCompat.createWithResource(this, R.drawable.ic_notification))
        if (total > 0) style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(total))
        return style
    }

    private fun approvalNotification(session: RunningSession): Notification {
        val approval = session.approvalId ?: error("Approval notification requires an approval ID")
        val highRisk = session.approvalRisk?.lowercase() in setOf("high", "root")
        val text = approvalExpanded(session, highRisk)
        return NotificationCompat.Builder(this, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(session.notificationTitle)
            .setContentText("${session.notificationSender}: ${text.lineSequence().first()}")
            .setStyle(eventMessageStyle(session, text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(session.conversationId))
            .setDeleteIntent(dismissIntent(session.conversationId))
            .setShortcutId(shortcutId(session.notificationThreadKey))
            .setLocusId(locusId(session.notificationThreadKey))
            .addAction(approvalAction(session, approval, confirmed = false))
            .also { builder ->
                if (highRisk) builder.addAction(reviewAction(session))
                else builder.addAction(approvalAction(session, approval, confirmed = true))
            }
            .build()
    }

    private fun postTerminalNotification(session: RunningSession) {
        visibleActivityIds = visibleActivityIds - activityId(session.notificationThreadKey)
        session.approvalId?.let { visibleApprovalIds = visibleApprovalIds - approvalId(it) }
        val manager = NotificationManagerCompat.from(this)
        manager.cancel(activityId(session.notificationThreadKey))
        session.approvalId?.let { manager.cancel(approvalId(it)) }
        if (session.phase == SessionPhase.COMPLETED) postCompletedMessage(session) else postIssue(session)
    }

    private fun postCompletedMessage(session: RunningSession) {
        val message = session.latestAssistantMessage.trim().takeLast(1_000)
        if (message.isBlank() || AiSessionNotificationVisibility.isVisible(session.conversationId)) return
        val entry = AiSessionNotificationStore.appendAi(
            context = this,
            threadKey = session.notificationThreadKey,
            title = session.notificationTitle,
            mode = session.mode.name,
            senderName = session.notificationSender,
            conversationId = session.conversationId,
            content = message,
            timestamp = System.currentTimeMillis()
        )
        publishConversationShortcut(session.conversationId, session.notificationThreadKey, session.notificationTitle, session.mode)
        val replyIntent = Intent(this, AiSessionNotificationActionReceiver::class.java)
            .setAction(AiSessionNotificationActionReceiver.ACTION_REPLY)
            .putExtra(AiSessionNotificationActionReceiver.EXTRA_CONVERSATION_ID, session.conversationId)
        val replyPending = PendingIntent.getBroadcast(
            this,
            session.conversationId.hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, "Reply", replyPending)
            .addRemoteInput(RemoteInput.Builder(AiSessionNotificationActionReceiver.KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(session.notificationTitle)
            .setContentText(message.take(180))
            .setStyle(messageStyle(entry))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(session.conversationId))
            .setDeleteIntent(dismissIntent(session.conversationId))
            .setShortcutId(shortcutId(session.notificationThreadKey))
            .setLocusId(locusId(session.notificationThreadKey))
            .addAction(reply)
            .build()
        NotificationManagerCompat.from(this).apply {
            notify(messageId(session.notificationThreadKey), notification)
        }
    }

    private fun messageStyle(entry: AiSessionNotificationStore.Entry): NotificationCompat.MessagingStyle =
        messageStyle(this, entry)

    private fun eventMessageStyle(session: RunningSession, text: String): NotificationCompat.MessagingStyle {
        val you = userPerson()
        val sender = Person.Builder()
            .setName(session.notificationSender)
            .setKey("amaya-event-${session.notificationThreadKey}-${session.notificationSender}")
            .setIcon(senderIcon(session.mode.name))
            .setBot(true)
            .build()
        return NotificationCompat.MessagingStyle(you)
            .setConversationTitle(session.notificationTitle)
            .setGroupConversation(true)
            .addMessage(text, session.updatedAt, sender)
    }

    private fun senderIcon(mode: String): IconCompat = personIcon(
        this,
        R.mipmap.ic_avatar_ai
    )

    private fun userPerson(): Person = userPerson(this)

    private fun postIssue(session: RunningSession) {
        if (AiSessionNotificationVisibility.isVisible(session.conversationId)) return
        val reason = session.detail.trim().take(300).ifBlank { issuePrimary(session) }
        val text = "${issuePrimary(session)}\n$reason"
        val notification = NotificationCompat.Builder(this, CHANNEL_ISSUES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(session.notificationTitle)
            .setContentText("${session.notificationSender}: ${issuePrimary(session)}")
            .setStyle(eventMessageStyle(session, text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setGroup(GROUP_ISSUES)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(session.conversationId))
            .setShortcutId(shortcutId(session.notificationThreadKey))
            .setLocusId(locusId(session.notificationThreadKey))
            .build()
        NotificationManagerCompat.from(this).notify(issueId(session.notificationThreadKey), notification)
    }

    private fun approvalAction(session: RunningSession, approvalId: String, confirmed: Boolean): NotificationCompat.Action {
        val intent = Intent(this, AiSessionNotificationActionReceiver::class.java)
            .setAction(if (confirmed) AiSessionNotificationActionReceiver.ACTION_APPROVE else AiSessionNotificationActionReceiver.ACTION_DECLINE)
            .putExtra(AiSessionNotificationActionReceiver.EXTRA_APPROVAL_ID, approvalId)
            .putExtra(AiSessionNotificationActionReceiver.EXTRA_CONVERSATION_ID, session.conversationId)
        val pending = PendingIntent.getBroadcast(
            this,
            31 * session.conversationId.hashCode() + confirmed.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            if (confirmed) "Approve" else "Decline",
            pending
        ).setAuthenticationRequired(confirmed).build()
    }

    private fun reviewAction(session: RunningSession): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_notification, "Review", openAppIntent(session.conversationId))
            .setAuthenticationRequired(true)
            .build()

    private fun dismissIntent(conversationId: Long): PendingIntent = PendingIntent.getBroadcast(
        this,
        17 * conversationId.hashCode(),
        Intent(this, AiSessionNotificationActionReceiver::class.java)
            .setAction(AiSessionNotificationActionReceiver.ACTION_DISMISS)
            .putExtra(AiSessionNotificationActionReceiver.EXTRA_CONVERSATION_ID, conversationId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun activityPrimary(session: RunningSession): String = when (session.phase) {
        SessionPhase.STARTING -> "Starting"
        SessionPhase.THINKING -> "Thinking"
        SessionPhase.STREAMING -> "Writing response"
        SessionPhase.TOOL -> toolActivityLabel(session)
        SessionPhase.COMPACTING -> "Compacting context"
        SessionPhase.DELEGATING -> session.activeDelegateName?.let { "Delegating to $it" } ?: "Delegating"
        SessionPhase.WAITING_APPROVAL -> "Waiting for approval"
        SessionPhase.COMPLETED -> "Completed"
        SessionPhase.FAILED -> "Failed"
        SessionPhase.STOPPED -> "Stopped"
    }

    private fun activityExpanded(session: RunningSession): String = listOf(
        activityPrimary(session),
        sanitizedDetail(session.detail).takeIf { it.isNotBlank() && it != activityPrimary(session) },
        delegationProgress(session)
    ).filterNotNull().joinToString("\n")

    private fun activitySummaryLine(sessions: List<RunningSession>): String {
        if (sessions.isEmpty()) return "Preparing local runtime"
        val counts = sessions.groupingBy { activityPrimary(it) }.eachCount()
        return counts.entries.take(3).joinToString(" · ") { (label, count) -> "$count ${label.lowercase()}" }
    }

    private fun toolActivityLabel(session: RunningSession): String {
        val raw = session.status.removePrefix("Tools:").removePrefix("Tool ").trim()
        return raw.ifBlank { "Using tool" }.replaceFirstChar { it.uppercase() }
    }

    private fun approvalPrimary(session: RunningSession): String =
        session.approvalLabel?.removePrefix("Tools:")?.trim()?.ifBlank { null } ?: "Tool action"

    private fun approvalExpanded(session: RunningSession, highRisk: Boolean): String {
        val title = if (highRisk) "REVIEW NEEDED" else "APPROVAL NEEDED"
        val command = sanitizedDetail(session.detail).takeIf(String::isNotBlank) ?: approvalPrimary(session)
        val risk = session.approvalRisk?.replaceFirstChar { it.uppercase() }?.let { "Risk: $it" }

        return listOfNotNull(
            title,
            command,
            risk,
            if (highRisk) "Open Amaya to inspect the complete request." else null
        ).distinct().joinToString("\n")
    }

    private fun issuePrimary(session: RunningSession): String = when (session.phase) {
        SessionPhase.STOPPED -> "Response stopped"
        SessionPhase.FAILED -> "Response failed"
        else -> "Response interrupted"
    }

    private fun delegationProgress(session: RunningSession): String? = session.totalDelegates.takeIf { it > 0 }
        ?.let { "${session.completedDelegates.coerceAtMost(it)} of $it delegated tasks completed" }

    private fun sanitizedDetail(value: String): String {
        val raw = value.trim().replace(Regex("(?i)(authorization|api[_-]?key|token|password)(\\s*[:=]\\s*)(\\S+)"), "$1$2•••")
        if (raw.isBlank()) return ""
        val normalized = raw.replace('\\', '/')
        val file = File(normalized)
        return when {
            Regex("^[A-Za-z]:/").containsMatchIn(normalized) -> shortenPath(normalized.substringAfter("/amaya/", file.name))
            normalized.count { it == '/' } >= 3 -> shortenPath(normalized)
            else -> normalized.take(220)
        }
    }

    private fun shortenPath(path: String): String {
        val parts = path.split('/').filter(String::isNotBlank)
        return if (parts.size <= 5) parts.joinToString("/").take(220)
        else (parts.take(3) + "…" + parts.takeLast(1)).joinToString("/").take(220)
    }

    private fun liveChipLabel(session: RunningSession): String = when {
        session.totalDelegates > 0 -> "${session.completedDelegates}/${session.totalDelegates}"
        else -> "Agent"
    }

    private fun publishConversationShortcut(conversationId: Long, threadKey: String, title: String, mode: AssistantMode) {
        val icon = threadIcon(mode)
        val shortcut = ShortcutInfoCompat.Builder(this, shortcutId(threadKey))
            .setShortLabel(title.take(10).ifBlank { "Amaya" })
            .setLongLabel(title.take(60).ifBlank { "Amaya" })
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(EXTRA_OPEN_CONVERSATION_ID, conversationId))
            .setIcon(IconCompat.createWithResource(this, icon))
            .setPerson(
                Person.Builder()
                    .setName(title.ifBlank { "Amaya" })
                    .setKey("amaya-shortcut-$threadKey")
                    .setIcon(IconCompat.createWithResource(this, icon))
                    .build()
            )
            .setLocusId(locusId(threadKey))
            .setIsConversation()
            .setLongLived(true)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(this, shortcut) }
    }

    private fun threadIcon(mode: AssistantMode): Int = when (mode) {
        AssistantMode.CHAT -> R.mipmap.ic_shortcut_chat
        AssistantMode.PROJECT -> R.mipmap.ic_shortcut_folder
        AssistantMode.AGENT -> R.mipmap.ic_shortcut_group
    }

    private fun removeLegacyConversationShortcuts() {
        val legacyIds = ShortcutManagerCompat.getDynamicShortcuts(this)
            .map(ShortcutInfoCompat::getId)
            .filter { it.startsWith("amaya-conversation-") }
        if (legacyIds.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(this, legacyIds)
    }

    private fun openAppIntent(conversationId: Long?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        conversationId?.let { intent.putExtra(EXTRA_OPEN_CONVERSATION_ID, it) }
        return PendingIntent.getActivity(
            this,
            conversationId?.hashCode() ?: ACTIVITY_SUMMARY_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun promote(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(ACTIVITY_SUMMARY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ACTIVITY_SUMMARY_ID, notification)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ACTIVITY, "AI activity", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Silent progress for active AI sessions"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Completed AI responses"
                    enableVibration(true)
                    setShowBadge(true)
                },
                NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tool requests waiting for your decision"
                    enableVibration(true)
                    setShowBadge(true)
                },
                NotificationChannel(CHANNEL_ISSUES, "Session issues", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Interrupted or failed AI responses"
                    enableVibration(false)
                    setShowBadge(true)
                }
            )
        )
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION_ID = "open_conversation_id"
        private const val CHANNEL_ACTIVITY = "ai_activity_v2"
        private const val CHANNEL_MESSAGES = "ai_messages_v2"
        private const val CHANNEL_APPROVALS = "ai_approvals_v2"
        private const val CHANNEL_ISSUES = "ai_session_issues_v2"
        private const val GROUP_ACTIVITY = "com.amaya.intelligence.AI_ACTIVITY"
        private const val GROUP_ISSUES = "com.amaya.intelligence.AI_ISSUES"
        private const val ACTIVITY_SUMMARY_ID = 44_500

        fun activityId(threadKey: String): Int = 45_000 xor threadKey.hashCode()
        fun messageId(threadKey: String): Int = 55_000 xor threadKey.hashCode()
        fun messageId(id: Long): Int = messageId("chat:$id")
        fun approvalId(boundApprovalId: String): Int = 65_000 xor boundApprovalId.hashCode()
        fun issueId(threadKey: String): Int = 75_000 xor threadKey.hashCode()
        fun shortcutId(threadKey: String): String = "amaya-thread-$threadKey"
        fun locusId(threadKey: String): LocusIdCompat = LocusIdCompat(shortcutId(threadKey))

        private fun senderIcon(context: Context, mode: String): IconCompat = personIcon(
            context,
            R.mipmap.ic_avatar_ai
        )

        private fun userPerson(context: Context): Person = Person.Builder()
            .setName("You")
            .setKey("amaya-you")
            .setIcon(personIcon(context, R.mipmap.ic_avatar_person))
            .build()

        private fun personIcon(context: Context, resourceId: Int): IconCompat {
            return IconCompat.createWithResource(context, resourceId)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AiSessionNotificationService::class.java))
        }

        fun refreshCompletedSummary(context: Context) {
            // No longer maintaining a group summary for messages
        }

        private fun messageStyle(context: Context, entry: AiSessionNotificationStore.Entry): NotificationCompat.MessagingStyle {
            val you = userPerson(context)
            return NotificationCompat.MessagingStyle(you)
                .setConversationTitle(entry.title)
                .setGroupConversation(true)
                .also { style ->
                    entry.messages.forEach { message ->
                        // Native contract: null sender means the MessagingStyle user (You).
                        val sender = if (message.isUser) null else Person.Builder()
                            .setName(message.senderName)
                            .setKey("amaya-sender-${entry.threadKey}-${message.senderName}")
                            .setIcon(senderIcon(context, message.mode))
                            .setBot(true)
                            .build()
                        style.addMessage(message.content, message.timestamp, sender)
                    }
                }
        }

        internal fun refreshMessage(context: Context, entry: AiSessionNotificationStore.Entry) {
            NotificationManagerCompat.from(context).notify(
                messageId(entry.threadKey),
                NotificationCompat.Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(entry.title)
                    .setContentText(entry.messages.lastOrNull()?.let { "${it.senderName}: ${it.content}" }?.take(180))
                    .setStyle(messageStyle(context, entry))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setShortcutId(shortcutId(entry.threadKey))
                    .setLocusId(locusId(entry.threadKey))
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            entry.conversationId.hashCode(),
                            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_CONVERSATION_ID, entry.conversationId),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                    )
                    .build()
            )
        }
    }
}
