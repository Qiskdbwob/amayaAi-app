package com.amaya.intelligence.ui.screens.chat.shared

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.amaya.intelligence.ui.viewmodels.ChatViewModel

/**
 * How close to the end of the content the user has to be for the list to keep
 * following new content. Anything stricter (pixel-exact) makes a few pixels of
 * drift silently disable auto-follow.
 */
internal val CHAT_AUTO_FOLLOW_THRESHOLD = 72.dp

/**
 * How long after the user sends a message the list keeps following growth even if the
 * provider has not flagged the turn active yet. Long enough to cover the round trip from
 * "message appended" to "streaming", short enough that it can't be mistaken for a tap.
 */
internal const val CHAT_FOLLOW_BURST_MS = 800L

/**
 * Non-observable flag telling the two auto-follow coroutines apart: while it is
 * set, the list is being scrolled by us, not dragged by the user. Deliberately
 * not a [androidx.compose.runtime.MutableState] — flipping it must not recompose.
 */
internal class ChatScrollOwner {
    var following = false
}

/**
 * How far the rendered content now reaches past the bottom of the viewport,
 * in pixels. 0 means the tail of the conversation is fully on screen.
 *
 * `canScrollForward` is the authority here: during streaming the tail item is
 * pushed below the viewport and drops out of `visibleItemsInfo` entirely, so a
 * measurement based on visible items alone would report "nothing below" exactly
 * when the most content is hidden.
 */
internal fun LazyListState.distanceToContentEnd(): Int {
    if (!canScrollForward) return 0
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return 0
    // Tail item not rendered at all — it is somewhere below, distance unknown
    // but definitely more than a viewport.
    val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
        ?: return info.viewportEndOffset - info.viewportStartOffset
    return ((last.offset + last.size) - info.viewportEndOffset).coerceAtLeast(0)
}

/** Instant move to the end of the content. No-op when already there. */
internal suspend fun LazyListState.snapToContentEnd() {
    if (!canScrollForward) return
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return
    val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
    if (last == null) {
        scrollToItem(total - 1, Int.MAX_VALUE)
        return
    }
    val distance = (last.offset + last.size) - info.viewportEndOffset
    if (distance > 0) scrollBy(distance.toFloat())
}

/** Animated move to the end of the content — for explicit user actions only. */
internal suspend fun LazyListState.animateToContentEnd() {
    if (!canScrollForward) return
    val total = layoutInfo.totalItemsCount
    if (total == 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == total - 1 }) {
        animateScrollToItem(total - 1)
    }
    // Settle the remainder: the tail item can be taller than the viewport.
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 } ?: return
    val distance = (last.offset + last.size) - info.viewportEndOffset
    if (distance > 0) animateScrollBy(distance.toFloat())
}


internal data class ChatAutoFollowState(
    val shouldAutoScroll: Boolean,
    val jumpToBottom: () -> Unit
)

@Composable
internal fun rememberChatAutoFollow(
    listState: LazyListState,
    conversationKey: String,
    drawerVisible: Boolean,
    turnActive: Boolean,
    inputBarHeight: MutableIntState,
    scrollEvents: SharedFlow<ChatViewModel.ScrollReason>
): ChatAutoFollowState {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val scope = rememberCoroutineScope()
    var shouldAutoScroll by remember { mutableStateOf(true) }
    LaunchedEffect(conversationKey) { shouldAutoScroll = true }
    val scrollOwner = remember { ChatScrollOwner() }
    val drawerVisibleState = rememberUpdatedState(drawerVisible)
    val contentReach = remember(listState) { snapshotFlow { listState.distanceToContentEnd() } }
    val turnActiveState = rememberUpdatedState(turnActive)
    val followBurst = remember { mutableStateOf(false) }
    val armFollowBurst = remember {
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    LaunchedEffect(Unit) {
        armFollowBurst.collectLatest {
            followBurst.value = true
            try {
                delay(CHAT_FOLLOW_BURST_MS)
            } finally {
                followBurst.value = false
            }
        }
    }
    LaunchedEffect(listState, inputBarHeight) {
        var previousViewport = 0
        var previousComposer = inputBarHeight.intValue
        snapshotFlow {
            val info = listState.layoutInfo
            (info.viewportEndOffset - info.viewportStartOffset) to inputBarHeight.intValue
        }.collect { (viewport, composer) ->
            val viewportShrank = previousViewport != 0 && viewport < previousViewport
            val composerGrew = composer > previousComposer
            previousViewport = viewport
            previousComposer = composer
            if (viewportShrank || composerGrew) armFollowBurst.tryEmit(Unit)
        }
    }
    LaunchedEffect(contentReach, density) {
        val thresholdPx = with(density) { CHAT_AUTO_FOLLOW_THRESHOLD.toPx() }
        contentReach.collect { reach ->
            when {
                reach <= thresholdPx -> shouldAutoScroll = true
                listState.isScrollInProgress && !scrollOwner.following -> shouldAutoScroll = false
            }
        }
    }
    LaunchedEffect(contentReach) {
        var previousReach = 0
        contentReach.collect { reach ->
            val grew = reach > previousReach
            previousReach = reach
            if (!grew || !shouldAutoScroll || drawerVisibleState.value) return@collect
            if (!turnActiveState.value && !followBurst.value) return@collect
            if (listState.isScrollInProgress && !scrollOwner.following) return@collect
            scrollOwner.following = true
            try {
                listState.snapToContentEnd()
            } finally {
                scrollOwner.following = false
                previousReach = 0
            }
        }
    }
    val jumpToBottom: () -> Unit = {
        shouldAutoScroll = true
        scope.launch {
            scrollOwner.following = true
            try {
                listState.animateToContentEnd()
            } finally {
                scrollOwner.following = false
            }
        }
    }
    LaunchedEffect(scrollEvents) {
        scrollEvents.collect { reason ->
            when (reason) {
                ChatViewModel.ScrollReason.NEW_MESSAGE -> {
                    shouldAutoScroll = true
                    armFollowBurst.tryEmit(Unit)
                }
                ChatViewModel.ScrollReason.NEW_TOOL -> Unit
            }
        }
    }
    return ChatAutoFollowState(shouldAutoScroll, jumpToBottom)
}
