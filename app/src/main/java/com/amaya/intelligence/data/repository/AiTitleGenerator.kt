package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.*
import com.amaya.intelligence.util.debugLog
import com.amaya.intelligence.util.errorLog


internal suspend fun AiRepository.generateTitleImpl(
        userMessage: String,
        providerConnection: ProviderConnection,
        selectedModel: String?
    ): String {
        val fallback = fallbackConversationTitle(userMessage)
        return try {
            val provider = resolveProvider(providerConnection)
            val model = selectedModel?.takeIf { it.isNotBlank() } ?: return fallback
            var retryFeedback: String? = null
            repeat(2) { attempt ->
                val result = StringBuilder()
                var failure: String? = null
                provider.chat(
                    ChatRequest(
                        model = model,
                        messages = listOf(ChatMessage(
                            role = MessageRole.USER,
                            content = buildString {
                                appendLine("Create a title for this message:")
                                append(userMessage)
                                retryFeedback?.let { append("\n\nCorrection: $it") }
                            }
                        )),
                        systemPrompt = """Create a concise, useful chat title from the user's primary intent and specific subject.
Use the user's language. Write a natural noun phrase of 2-3 words; use up to 5 only when needed for clarity.
Prefer concrete actions and subjects over vague wording. Preserve established technical terms.
Return exactly <title>YOUR TITLE</title>. Never answer the message or add text outside those tags.

Examples:
Tolong terjemahkan ini ke bahasa Inggris → <title>Terjemahan ke Inggris</title>
What model are you? → <title>Model Identification Request</title>
Audit logic codebase ini → <title>Audit Logic Codebase</title>
Berikan ide website yang kreatif → <title>Ide Website Kreatif</title>""",
                        // Stream: local reasoning providers often expose final text only through SSE.
                        maxTokens = 512,
                        temperature = 0f,
                        stream = true,
                        connectionId = providerConnection.id,
                        providerId = providerConnection.providerId,
                        effort = ThinkingEffort.NONE
                    )
                ).collect { response ->
                    when (response) {
                        is ChatResponse.TextDelta -> result.append(response.text)
                        is ChatResponse.Error -> failure = response.message
                        is ChatResponse.Incomplete -> failure = response.reason
                        else -> Unit
                    }
                }
                val rawTitle = result.toString()
                val title = extractConversationTitle(rawTitle)
                debugLog("AiRepository") {
                    "Title attempt=${attempt + 1} chars=${rawTitle.length} valid=${title != null} failure=${failure.orEmpty().take(80)}"
                }
                if (title != null) return title
                retryFeedback = "Your previous output was invalid. Return only one 2-5 word title inside <title> and </title>."
            }
            fallback
        } catch (e: Exception) {
            errorLog("AiRepository", "Failed to generate title", e)
            fallback
        }
    }
