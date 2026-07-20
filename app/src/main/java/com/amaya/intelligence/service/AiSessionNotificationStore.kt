package com.amaya.intelligence.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Bounded process-safe notification conversation history. */
internal object AiSessionNotificationStore {
    private const val PREFS = "ai_session_notifications"
    private const val KEY_THREADS = "threads"
    private const val KEY_PROMOTION_DISMISSED = "promotion_dismissed"
    private const val MAX_THREADS = 24
    private const val MAX_MESSAGES = 10

    data class Message(
        val senderName: String,
        val content: String,
        val timestamp: Long,
        val mode: String,
        val isUser: Boolean,
        val sourceConversationId: Long
    )

    data class Entry(
        val threadKey: String,
        val title: String,
        val mode: String,
        val replyTargetConversationId: Long,
        val messages: List<Message>,
        val timestamp: Long
    ) {
        val conversationId: Long get() = replyTargetConversationId
        val message: String get() = messages.lastOrNull()?.content.orEmpty()
    }

    @Synchronized
    fun appendAi(
        context: Context,
        threadKey: String,
        title: String,
        mode: String,
        senderName: String,
        conversationId: Long,
        content: String,
        timestamp: Long
    ): Entry {
        val entries = read(context)
        val existing = entries.firstOrNull { it.threadKey == threadKey }
        val updated = Entry(
            threadKey = threadKey,
            title = title,
            mode = mode,
            replyTargetConversationId = conversationId,
            messages = (existing?.messages.orEmpty() + Message(senderName, content, timestamp, mode, false, conversationId)).takeLast(MAX_MESSAGES),
            timestamp = timestamp
        )
        write(context, (entries.filterNot { it.threadKey == threadKey } + updated).sortedByDescending(Entry::timestamp).take(MAX_THREADS))
        return updated
    }

    @Synchronized
    fun addReply(context: Context, conversationId: Long, reply: String): Entry? {
        val entries = read(context)
        val current = entries.firstOrNull { it.replyTargetConversationId == conversationId } ?: return null
        val now = System.currentTimeMillis()
        val updated = current.copy(
            messages = (current.messages + Message("You", reply, now, current.mode, true, conversationId)).takeLast(MAX_MESSAGES),
            timestamp = now
        )
        write(context, entries.map { if (it.threadKey == current.threadKey) updated else it })
        return updated
    }

    @Synchronized
    fun remove(context: Context, conversationId: Long) =
        write(context, read(context).filterNot { it.replyTargetConversationId == conversationId })

    @Synchronized
    fun clear(context: Context) = write(context, emptyList())

    fun markPromotionDismissed(context: Context, conversationId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_PROMOTION_DISMISSED, conversationId)
            .apply()
    }

    fun canRequestPromotion(context: Context, conversationId: Long): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_PROMOTION_DISMISSED, -1L) != conversationId

    @Synchronized
    fun read(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_THREADS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val messages = item.optJSONArray("messages") ?: return@mapNotNull null
                val parsed = (0 until messages.length()).mapNotNull { messageIndex ->
                    val message = messages.optJSONObject(messageIndex) ?: return@mapNotNull null
                    Message(
                        senderName = message.optString("senderName").ifBlank { "AI" },
                        content = message.optString("content").takeIf(String::isNotBlank) ?: return@mapNotNull null,
                        timestamp = message.optLong("timestamp"),
                        mode = message.optString("mode").ifBlank { "CHAT" },
                        isUser = message.optBoolean("isUser"),
                        sourceConversationId = message.optLong("sourceConversationId")
                    )
                }
                if (parsed.isEmpty()) return@mapNotNull null
                Entry(
                    threadKey = item.optString("threadKey").takeIf(String::isNotBlank) ?: return@mapNotNull null,
                    title = item.optString("title").ifBlank { "Amaya" },
                    mode = item.optString("mode").ifBlank { "CHAT" },
                    replyTargetConversationId = item.optLong("replyTargetConversationId"),
                    messages = parsed,
                    timestamp = item.optLong("timestamp")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray(entries.map { entry ->
            JSONObject()
                .put("threadKey", entry.threadKey)
                .put("title", entry.title)
                .put("mode", entry.mode)
                .put("replyTargetConversationId", entry.replyTargetConversationId)
                .put("timestamp", entry.timestamp)
                .put("messages", JSONArray(entry.messages.map { message ->
                    JSONObject()
                        .put("senderName", message.senderName)
                        .put("content", message.content)
                        .put("timestamp", message.timestamp)
                        .put("mode", message.mode)
                        .put("isUser", message.isUser)
                        .put("sourceConversationId", message.sourceConversationId)
                }))
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THREADS, array.toString())
            .remove("messages")
            .apply()
    }
}
