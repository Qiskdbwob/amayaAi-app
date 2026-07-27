package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.AiToolDefinition
import com.amaya.intelligence.data.remote.api.ChatMessage
import com.amaya.intelligence.data.remote.api.MessageRole

/**
 * Per-turn memo of message token costs, keyed by object identity.
 *
 * The agent loop re-plans the window on every iteration over a history that only grows, so without
 * this the same multi-megabyte tool-result strings are re-scanned once per iteration — quadratic in
 * a long tool loop. Identity is the right key: [ChatMessage] is immutable and the loop appends the
 * same instances, while structural equality would itself have to walk those strings.
 */
internal class CostCache(
    /**
     * The calibration the memoised costs were measured under. Bound to the cache so a caller cannot
     * mix ratios against one memo and silently read back costs computed for a different one.
     */
    val ratio: Double = 1.0,
    private val maxEntries: Int = 4_096
) {
    private val costs = java.util.IdentityHashMap<ChatMessage, Int>()
    private val textCosts = java.util.IdentityHashMap<String, Int>()

    fun get(message: ChatMessage): Int? = costs[message]

    fun put(message: ChatMessage, cost: Int) {
        // Decayed copies are new instances each pass, so the map is bounded rather than pruned:
        // dropping it wholesale costs one re-measure and can never return a stale figure.
        if (costs.size >= maxEntries) costs.clear()
        costs[message] = cost
    }

    /**
     * Memo for a raw payload measurement.
     *
     * Tool-result bodies are the same String instances across every iteration of a turn, so without
     * this the decay pass re-scans hundreds of kilobytes once per agent iteration. Strings are
     * immutable, so an identity hit can never be stale.
     */
    fun textCost(value: String, compute: () -> Int): Int {
        textCosts[value]?.let { return it }
        val cost = compute()
        if (textCosts.size >= maxEntries) textCosts.clear()
        textCosts[value] = cost
        return cost
    }
}

/**
 * Single source of truth for context-window accounting.
 *
 * Before this existed the repository carried five divergent estimators that disagreed about
 * whether tool payloads, tool-call arguments, response items, and images cost anything. The two
 * halves of the budget equation used different formulas, so a request could be judged to fit and
 * then overflow at the provider.
 */
internal object TokenEstimator {
    const val DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768
    /** Conservative chars-per-token. Latin prose sits near 4; JSON, code, and Indonesian run denser. */
    private const val BASE_CHARS_PER_TOKEN = 3.4

    /** Role/delimiter framing every provider adds around a message. */
    private const val PER_MESSAGE_OVERHEAD = 8

    /** Provider-agnostic upper bound for one attached image. Images never cost their base64 length. */
    const val PER_IMAGE_TOKENS = 1_500

    fun text(value: String, ratio: Double = 1.0): Int {
        if (value.isBlank()) return 0
        // Counted in one pass rather than via split(): this runs over multi-hundred-KB tool payloads
        // on every planning pass, and split() allocated one String per word just to read its size.
        var words = 0
        var inWord = false
        for (character in value) {
            if (character.isWhitespace()) {
                inWord = false
            } else if (!inWord) {
                inWord = true
                words++
            }
        }
        return (maxOf(value.length / BASE_CHARS_PER_TOKEN, words * 1.3, 1.0) * ratio).toInt().coerceAtLeast(1)
    }

    fun message(message: ChatMessage, ratio: Double = 1.0, cache: CostCache? = null): Int {
        val memo = cache?.takeIf { it.ratio == ratio }
        memo?.get(message)?.let { return it }
        val cost = PER_MESSAGE_OVERHEAD +
            text(message.content.orEmpty(), ratio) +
            text(message.toolResult?.content.orEmpty(), ratio) +
            message.toolCalls.orEmpty().sumOf { text(it.arguments.toString(), ratio) } +
            message.responseItems.sumOf { text(it, ratio) } +
            message.images.size * PER_IMAGE_TOKENS +
            (if (message.toolResult?.metadata?.get("bridge_image_base64").isNullOrBlank()) 0 else PER_IMAGE_TOKENS)
        memo?.put(message, cost)
        return cost
    }

    fun messages(messages: List<ChatMessage>, ratio: Double = 1.0, cache: CostCache? = null): Int =
        messages.sumOf { message(it, ratio, cache) }

    fun toolSchemas(tools: List<AiToolDefinition>): Int =
        tools.sumOf { (it.name.length + it.description.length + it.schemaJson().length + 3) / 4 }

    /**
     * Shrink [value] until it actually measures at or below [maxTokens] under this estimator.
     *
     * A char-budget alone is not enough: the word-count floor can push a "trimmed" string back over
     * the limit, and appending an elision marker adds tokens after the cut.
     */
    fun truncateToTokens(value: String, maxTokens: Int, marker: String = "\n… [truncated]", ratio: Double = 1.0): String {
        if (text(value, ratio) <= maxTokens) return value
        // Never hand back an unmarked prefix: content that was cut but looks complete is worse than
        // content that is obviously short. When even the marker does not fit, the marker wins and we
        // deliberately overrun by a few tokens rather than lie about the payload.
        if (maxTokens <= 0 || text(marker, ratio) >= maxTokens) return marker.trim()
        // text(v) is at least v.length / BASE_CHARS_PER_TOKEN * ratio, so no prefix longer than this
        // can ever fit. Bounding the search by the budget instead of the payload keeps a 200 KB tool
        // result from being re-scanned ~18 times per truncation.
        val maxFeasibleChars = if (ratio > 0) ((maxTokens + 1) * BASE_CHARS_PER_TOKEN / ratio).toInt() else value.length
        var high = minOf(value.length, maxFeasibleChars.coerceAtLeast(1))
        var low = 0
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (text(value.take(mid).trimEnd() + marker, ratio) <= maxTokens) low = mid else high = mid - 1
        }
        return value.take(low).trimEnd() + marker
    }
}

/**
 * Outcome of planning one provider request.
 *
 * [messages] and [evicted] always partition the input exactly — `messages.size + evicted.size`
 * equals the input size for every input. That invariant is what lets the caller decide whether any
 * context was actually lost, instead of inferring it from list sizes.
 */
internal data class ContextPlan(
    val messages: List<ChatMessage>,
    val evicted: List<ChatMessage>,
    /** Messages whose tool-result bytes were cut. Lossy even though the message itself was kept. */
    val truncatedOriginals: List<ChatMessage>,
    val usedTokens: Int,
    /**
     * First index of the contiguous retained window. Everything before it left the request, except
     * a separately pinned anchor. Callers track coverage against this boundary rather than against
     * `evicted.size`, which is a hole-punched count that shifts whenever the anchor stops fitting.
     */
    val evictionBoundary: Int = 0,
    /** The goal anchor when it was pinned outside the contiguous window, else null. */
    val pinnedAnchor: ChatMessage? = null
) {
    val isLossy: Boolean get() = evicted.isNotEmpty() || truncatedOriginals.isNotEmpty()
}

/**
 * Newest tool results stay verbatim; older ones decay to excerpts, then to one-line digests.
 *
 * Even rank 0 is capped as a fraction of the window: an unbounded newest result would swallow the
 * whole budget and force everything else — including the pinned goal — out of the request.
 */
private fun recencyCapTokens(rank: Int, maxTokens: Int): Int = when {
    rank == 0 -> (maxTokens * 0.7).toInt().coerceAtLeast(TOOL_RESULT_DIGEST_TOKENS)
    rank <= 2 -> (maxTokens * 0.3).toInt().coerceAtLeast(TOOL_RESULT_DIGEST_TOKENS)
    rank <= 6 -> 1_000
    else -> TOOL_RESULT_DIGEST_TOKENS
}

/** Floor for any surviving tool result, so a decayed body always says it was decayed. */
private const val TOOL_RESULT_DIGEST_TOKENS = 32

private fun toolResultDigest(message: ChatMessage): String {
    val result = message.toolResult ?: return TOOL_RESULT_TRUNCATION_MARKER
    val name = result.metadata["toolName"].orEmpty().ifBlank { "tool" }
    val state = if (result.isError) "error" else "ok"
    return "[$name result elided by context budget: $state, ${result.content.length} chars]"
}

private fun ChatMessage.isRealUserTurn(): Boolean =
    role == MessageRole.USER &&
        (!content.isNullOrBlank() || images.isNotEmpty()) &&
        content != STREAM_CONTINUATION_PROMPT

/**
 * Age-biased tool-result decay for the active turn.
 *
 * The previous implementation split the budget equally across every tool result, so a 200-byte
 * `read_file` got the same allowance as a 400 KB DOM dump and the newest result — the one the next
 * decision is made from — was truncated exactly as hard as the oldest.
 */
internal fun decayToolResults(
    span: List<ChatMessage>,
    maxTokens: Int,
    ratio: Double = 1.0,
    cache: CostCache? = null
): Pair<List<ChatMessage>, List<ChatMessage>> {
    if (TokenEstimator.messages(span, ratio, cache) <= maxTokens) return span to emptyList()
    val toolIndexes = span.indices.filter { span[it].role == MessageRole.TOOL && span[it].toolResult != null }
    if (toolIndexes.isEmpty()) return span to emptyList()

    val toolIndexSet = toolIndexes.toSet()
    // Each tool-result body is measured once here and reused by the frame-cost fold, the allowance
    // walk and the decay map. Measuring it three times per pass is what made a long tool loop
    // re-scan hundreds of kilobytes per agent iteration.
    val memo = cache?.takeIf { it.ratio == ratio }
    val bodyCost = HashMap<Int, Int>(toolIndexes.size)
    toolIndexes.forEach { index ->
        val body = span[index].toolResult?.content.orEmpty()
        bodyCost[index] = memo?.textCost(body) { TokenEstimator.text(body, ratio) }
            ?: TokenEstimator.text(body, ratio)
    }
    val fixedCost = span.indices
        .filterNot { it in toolIndexSet }
        .sumOf { TokenEstimator.message(span[it], ratio, cache) }
    // Everything on a tool message except the result body is non-negotiable, and every tool result
    // keeps at least a digest — a zero-length tool_result reads as "the tool returned nothing" and
    // is rejected outright by some providers.
    val toolFrameCost = toolIndexes.sumOf { index ->
        TokenEstimator.message(span[index], ratio, cache) - (bodyCost[index] ?: 0) + TOOL_RESULT_DIGEST_TOKENS
    }
    var remaining = (maxTokens - fixedCost - toolFrameCost).coerceAtLeast(0)

    // Newest first, so the freshest result claims the budget before anything older can starve it.
    val allowance = HashMap<Int, Int>()
    toolIndexes.asReversed().forEachIndexed { rank, index ->
        val cost = bodyCost[index] ?: 0
        val grant = minOf(cost, recencyCapTokens(rank, maxTokens), remaining)
        allowance[index] = grant
        remaining -= grant
    }

    val truncated = mutableListOf<ChatMessage>()
    val decayed = span.mapIndexed { index, message ->
        val result = message.toolResult
        if (index !in toolIndexSet || result == null) return@mapIndexed message
        val granted = allowance[index] ?: 0
        if ((bodyCost[index] ?: 0) <= granted) return@mapIndexed message
        truncated.add(message)
        val body = if (granted <= 0) {
            toolResultDigest(message)
        } else {
            TokenEstimator.truncateToTokens(
                result.content,
                granted,
                marker = TOOL_RESULT_TRUNCATION_MARKER,
                ratio = ratio
            )
        }
        message.copy(toolResult = result.copy(content = body))
    }
    if (TokenEstimator.messages(decayed, ratio, cache) <= maxTokens) return decayed to truncated

    // Second stage: shrink the arguments of tool calls that have already executed. A single
    // write_file carrying a whole file body is otherwise undecayable and takes the turn down with
    // it. The newest call is left verbatim — it may still be awaiting its result.
    val newestCallIndex = decayed.indexOfLast { !it.toolCalls.isNullOrEmpty() }
    val shrunk = decayed.mapIndexed { index, message ->
        if (index >= newestCallIndex || message.toolCalls.isNullOrEmpty()) return@mapIndexed message
        val trimmed = message.withDecayedToolArguments(TOOL_ARGUMENT_MAX_CHARS)
        if (trimmed !== message) truncated.add(message)
        trimmed
    }
    return shrunk to truncated
}

private const val ANCHOR_TRUNCATION_MARKER = "\n… [original request abridged by context budget]"
private const val TOOL_ARGUMENT_MAX_CHARS = 2_000
private const val TOOL_ARGUMENT_TRUNCATION_MARKER = "… [argument truncated by context budget]"

private fun ChatMessage.withDecayedToolArguments(maxCharsPerArgument: Int): ChatMessage {
    val calls = toolCalls ?: return this
    var changed = false
    val decayed = calls.map { call ->
        call.copy(arguments = call.arguments.mapValues { (_, value) ->
            if (value is String && value.length > maxCharsPerArgument) {
                changed = true
                value.take(maxCharsPerArgument).trimEnd() + TOOL_ARGUMENT_TRUNCATION_MARKER
            } else value
        })
    }
    return if (changed) copy(toolCalls = decayed) else this
}

/**
 * Choose the messages for one provider request.
 *
 * Guarantees, in priority order:
 *  1. The active user turn and everything after it is always present, or the plan is empty.
 *  2. The first real user message — the original goal — is pinned whenever it fits.
 *  3. An assistant tool-call message and its tool results are kept or dropped as one unit, so no
 *     `tool_call_id` is ever orphaned.
 *  4. Nothing is evicted while it fits. A conversation under budget is passed through untouched.
 *  5. [ContextPlan.messages] and [ContextPlan.evicted] partition the input exactly.
 */
internal fun planContextWindow(
    messages: List<ChatMessage>,
    maxTokens: Int,
    ratio: Double = 1.0,
    cache: CostCache? = null
): ContextPlan {
    if (maxTokens <= 0) return ContextPlan(emptyList(), messages, emptyList(), 0)
    if (messages.isEmpty()) return ContextPlan(emptyList(), emptyList(), emptyList(), 0)

    // A stream-continuation instruction must never become the anchor: it carries no request of its
    // own, and anchoring on it would reduce the next request to one sentence with no antecedent.
    val activeIndex = messages.indexOfLast { it.isRealUserTurn() }.takeIf { it >= 0 }
        ?: messages.indexOfLast { it.role == MessageRole.USER && (!it.content.isNullOrBlank() || it.images.isNotEmpty()) }
    if (activeIndex < 0) return ContextPlan(emptyList(), messages, emptyList(), 0)

    val anchorIndex = messages.indexOfFirst { it.isRealUserTurn() }
    val anchorOriginal = if (anchorIndex in 0 until activeIndex) messages[anchorIndex] else null
    // A first message larger than half the window is pinned in abridged form rather than dropped:
    // the original request must reach the model, but it must not crowd out the active turn.
    val anchorOriginalCost = anchorOriginal?.let { TokenEstimator.message(it, ratio, cache) } ?: 0
    // Reserve the pinned goal BEFORE decaying the tail. Without this the newest tool result expands
    // to fill the entire window and the anchor is evicted for want of ~25 tokens — which is the
    // original "the model forgot the original request" defect, re-entered through the decay path.
    val anchorReserve = anchorOriginalCost.coerceAtMost(maxTokens / 2)
    val tailSpan = messages.subList(activeIndex, messages.size)

    var (tail, truncated) = decayToolResults(tailSpan, maxTokens - anchorReserve, ratio, cache)
    var used = TokenEstimator.messages(tail, ratio, cache)
    if (anchorReserve > 0 && used > maxTokens - anchorReserve) {
        // The active turn cannot spare room for the pin. Keep the turn; the fit checks below decide
        // whether the anchor still fits alongside it.
        val retry = decayToolResults(tailSpan, maxTokens, ratio, cache)
        tail = retry.first
        truncated = retry.second
        used = TokenEstimator.messages(tail, ratio, cache)
    }
    if (used > maxTokens) return ContextPlan(emptyList(), messages, emptyList(), used)

    // Prefer the message the user actually wrote. Abridging is a last resort that only applies when
    // the original cannot be pinned at all — and only to prose, since stripping an image-bearing or
    // blank-content turn would leave an empty user message that providers reject.
    val abridgeable = anchorOriginal != null &&
        !anchorOriginal.content.isNullOrBlank() &&
        anchorOriginal.images.isEmpty()
    val abridgedAnchor = if (abridgeable) anchorOriginal!!.copy(
        content = TokenEstimator.truncateToTokens(
            anchorOriginal.content.orEmpty(),
            (maxTokens / 4).coerceAtLeast(1),
            marker = ANCHOR_TRUNCATION_MARKER,
            ratio = ratio
        )
    ) else null
    val anchorCandidate = when {
        anchorOriginal == null -> null
        used + anchorOriginalCost <= maxTokens -> anchorOriginal
        abridgedAnchor != null && used + TokenEstimator.message(abridgedAnchor, ratio, cache) <= maxTokens -> abridgedAnchor
        else -> null
    }
    val anchorCost = anchorCandidate?.let { TokenEstimator.message(it, ratio, cache) } ?: 0
    val anchorFits = anchorCandidate != null
    if (anchorFits) used += anchorCost

    // Fill backwards over the middle, span-atomically, until the next whole span stops fitting.
    var cut = activeIndex
    while (cut > 0) {
        var spanStart = cut - 1
        if (messages[spanStart].role == MessageRole.TOOL) {
            (spanStart - 1 downTo 0).firstOrNull { messages[it].role == MessageRole.ASSISTANT }
                ?.let { spanStart = it }
        }
        val spanCost = (spanStart until cut).sumOf { index ->
            if (anchorFits && index == anchorIndex) 0 else TokenEstimator.message(messages[index], ratio, cache)
        }
        if (used + spanCost > maxTokens) break
        used += spanCost
        cut = spanStart
    }

    if (anchorFits && anchorOriginal != null && anchorCandidate !== anchorOriginal) {
        truncated = truncated + anchorOriginal
    }
    // Reported for coverage arithmetic only when the anchor sits outside the contiguous retained
    // window; inside it, it is simply one of the retained messages and was never evicted.
    val pinnedAnchor = anchorOriginal?.takeIf { anchorFits && anchorIndex in 0 until cut }

    val keptIndexes = sortedSetOf<Int>().apply {
        if (anchorFits) add(anchorIndex)
        addAll(cut until messages.size)
    }
    val kept = keptIndexes.map { index ->
        when {
            index >= activeIndex -> tail[index - activeIndex]
            index == anchorIndex && anchorCandidate != null -> anchorCandidate
            else -> messages[index]
        }
    }
    val evicted = messages.indices.filterNot(keptIndexes::contains).map(messages::get)
    return ContextPlan(
        messages = kept,
        evicted = evicted,
        truncatedOriginals = truncated,
        usedTokens = used,
        evictionBoundary = cut,
        pinnedAnchor = pinnedAnchor ?: anchorOriginal?.takeIf { anchorFits && anchorIndex < cut }
    )
}
