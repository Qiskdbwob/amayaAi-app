package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolInfoIcon
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Shared expand/collapse/mount motion for every tool/thinking/group card. */
internal object ToolCallMotion {
    val motionSpec: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
    val enter = expandVertically(animationSpec = motionSpec) + fadeIn(tween(durationMillis = 180, easing = FastOutSlowInEasing))
    val exit = shrinkVertically(animationSpec = motionSpec) + fadeOut(tween(durationMillis = 140, easing = FastOutSlowInEasing))
}

/** Standard one-pixel outline for every top-level timeline card. */
@Composable
internal fun toolCardBorder(): BorderStroke = BorderStroke(
    1.dp,
    if (isSystemInDarkTheme()) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.08f)
)

/** Maximum expanded-body height. Header + body stay within roughly one quarter screen. */
@Composable
internal fun toolCardBodyMaxHeight(): Dp =
    (LocalConfiguration.current.screenHeightDp.dp / 4 - 44.dp).coerceAtLeast(120.dp)

/**
 * Cap on the work-summary card's body while its turn is running.
 *
 * Bigger than a tool body — this is the whole turn's activity, not one result — but
 * still leaves the answer streaming underneath it in view.
 */
@Composable
internal fun workCardLiveBodyMaxHeight(): Dp =
    (LocalConfiguration.current.screenHeightDp.dp * 0.38f).coerceAtLeast(200.dp)

/**
 * A block that can be pinned to a maximum height, scrolls internally, and sticks to its
 * newest content while it is bounded.
 *
 * This is what stops a live card from pushing the conversation around: once the content
 * passes [maxHeight] the block's height stops changing entirely, so a new event scrolls
 * into view *inside* the card instead of growing it and shoving everything below down.
 * The edge gradients stand in for a scrollbar.
 *
 * @param bounded when false the block is an ordinary Column — no cap, no gesture
 *        interception, no fades. Toggling it never changes the composition structure, so
 *        nothing inside is remounted.
 * @param followKey change it whenever a new event lands in this block. It re-arms the
 *        stick-to-end latch, so having scrolled up to read an earlier event never leaves
 *        the block permanently detached from the live one.
 */
@Composable
internal fun ToolFollowingScrollBlock(
    fadeColor: Color,
    maxHeight: Dp,
    bounded: Boolean,
    followKey: Any? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    var stickToEnd by remember { mutableStateOf(true) }

    // A new event re-arms following. Without this the latch was one-way in practice:
    // one drag anywhere over the block turned it off, and nothing ever turned it back on
    // for the rest of the turn — the block simply stopped tracking while the turn kept
    // streaming into it.
    LaunchedEffect(followKey, bounded) {
        if (bounded) stickToEnd = true
    }

    LaunchedEffect(bounded, stickToEnd) {
        if (!bounded || !stickToEnd) return@LaunchedEffect
        // Watches the offset as well as the range. Keyed on maxValue alone this stopped
        // tracking whenever content grew inside a child that is itself capped, or when a
        // reflow above the tail moved the tail without changing the scroll range at all —
        // neither of those emits a maxValue change, so the tail quietly drifted off.
        //
        // Instant, for the same reason the chat list follows instantly: content can
        // arrive faster than a duration animation settles, and a chasing scroll reads as
        // lag. Both values only update after layout, so this lands on real geometry.
        snapshotFlow { scrollState.maxValue to scrollState.value }
            .collect { (max, value) -> if (value < max) scrollState.scrollTo(max) }
    }

    val scrollableState = rememberScrollableState { delta ->
        stickToEnd = false
        // Claim only what actually moved. Claiming the whole delta the way the small
        // tool/thinking bodies do would be wrong here: this block can be nearly two
        // fifths of the screen, so swallowing edge deltas leaves the user unable to
        // scroll the conversation at all with a finger anywhere over the card.
        val consumed = -scrollState.dispatchRawDelta(-delta)
        // Back at the bottom by hand — resume following.
        if (!scrollState.canScrollForward) stickToEnd = true
        consumed
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Both the cap and the scroll node are dropped entirely when unbounded, not
            // just disabled. Modifier.verticalScroll rejects an infinite max-height
            // constraint even with enabled = false — the node stays attached and still
            // runs the check — and inside a LazyColumn item that constraint is exactly
            // what arrives. Dropping modifier elements does not remount anything: the
            // composition is unchanged, only the node chain is re-diffed.
            .then(if (bounded) Modifier.heightIn(max = maxHeight) else Modifier)
            .scrollable(scrollableState, Orientation.Vertical, enabled = bounded)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (bounded) Modifier.verticalScroll(scrollState, enabled = false) else Modifier),
            content = content
        )
        if (bounded && scrollState.canScrollBackward) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(fadeColor, fadeColor.copy(alpha = 0f))))
            )
        }
        if (bounded && scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor)))
            )
        }
    }
}

/** Scrollable result block with affordance fades. Call only from visible expandable content. */
@Composable
internal fun ToolScrollableBlock(
    fadeColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = toolCardBodyMaxHeight())
    ) {
        val scrollableState = rememberScrollableState { delta ->
            scrollState.dispatchRawDelta(-delta)
            // Consume edge deltas too. Nested chat LazyColumn must never receive
            // a drag/fling started inside a scrollable tool result block.
            delta
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .scrollable(scrollableState, Orientation.Vertical)
                .verticalScroll(scrollState, enabled = false),
            content = content
        )
        if (scrollState.canScrollBackward) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(fadeColor, fadeColor.copy(alpha = 0f))))
            )
        }
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(Brush.verticalGradient(listOf(fadeColor.copy(alpha = 0f), fadeColor)))
            )
        }
    }
}

/**
 * The mount fade shared by every timeline event: message rows, tool cards, thinking
 * cards, the work-summary card.
 *
 * Alpha only, and the content is laid out at its final height from the very first frame.
 * `AnimatedVisibility(enter = fadeIn())` cannot do that — it composes nothing while
 * hidden, so the row contributes zero height for a frame and then snaps to full. Inside
 * a card that is itself expanding, that reads as a jump; at the tail of the chat it
 * hands the auto-follow loop a target that is still settling.
 *
 * @param animate decided once by the caller, from whether this event is genuinely new.
 *        Anything read back from history, or re-composed because a collapsed parent was
 *        re-opened, passes false and appears instantly.
 */
@Composable
internal fun Modifier.mountFade(animate: Boolean): Modifier {
    var mounted by remember { mutableStateOf(!animate) }
    val alpha by animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "mount_fade"
    )
    LaunchedEffect(Unit) { mounted = true }
    return if (animate) graphicsLayer { this.alpha = alpha } else this
}

/**
 * Drives [toolHeaderShimmer]. Returns 0 when nothing is running, so an idle card costs
 * no per-frame invalidation.
 */
@Composable
internal fun rememberToolShimmerProgress(active: Boolean): Float =
    if (active) {
        val transition = rememberInfiniteTransition(label = "tool_shimmer")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart),
            label = "tool_shimmer_progress"
        ).value
    } else 0f

/**
 * Travelling gleam across a running card's header label. Shared by tool cards, subagent
 * rows, thinking cards and the work-summary card so "this is in progress" reads the same
 * everywhere.
 */
internal fun Modifier.toolHeaderShimmer(active: Boolean, progress: Float): Modifier {
    if (!active) return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            val peakX = (progress * (w * 3f)) - w
            val hw = w * 0.6f
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 1f),
                        Color.White.copy(alpha = 0.7f),
                        Color.White.copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0.7f),
                        Color.White.copy(alpha = 1f)
                    ),
                    start = Offset(peakX - hw, 0f),
                    end = Offset(peakX + hw, 0f)
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

/** Fades an overlong one-line card header without adding an ellipsis. */
internal fun Modifier.toolHeaderFade(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    0.78f to Color.White,
                    1f to Color.Transparent
                ),
                blendMode = BlendMode.DstIn
            )
        }

/** Lead-icon pill shared by tool, thinking, group, and browser cards. */
@Composable
internal fun ToolLeadIconPill(
    icon: ToolInfoIcon,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.18f))
    ) {
        Box(
            modifier = Modifier.size(width = 28.dp, height = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = mapToolIcon(icon),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = tint.copy(alpha = 0.88f)
            )
        }
    }
}

@Composable
fun mapToolIcon(icon: ToolInfoIcon): ImageVector {
    return when (icon) {
        ToolInfoIcon.EDIT      -> Icons.Default.Edit
        ToolInfoIcon.READ      -> Icons.Default.Visibility
        ToolInfoIcon.WRITE     -> Icons.Default.Add
        ToolInfoIcon.RUN       -> Icons.Default.Terminal
        ToolInfoIcon.CHECK     -> Icons.Default.CheckCircle
        ToolInfoIcon.SEARCH    -> Icons.Default.Search
        ToolInfoIcon.WEB_READ  -> Icons.Default.Language
        ToolInfoIcon.MESSAGE   -> Icons.Default.ChatBubble
        ToolInfoIcon.LIST      -> Icons.AutoMirrored.Filled.FormatListBulleted
        ToolInfoIcon.FIND      -> Icons.AutoMirrored.Filled.ManageSearch
        ToolInfoIcon.TASK      -> Icons.Default.Flag
        ToolInfoIcon.BROWSER   -> Icons.Default.Language
        ToolInfoIcon.DOCS      -> Icons.AutoMirrored.Filled.MenuBook
        ToolInfoIcon.GENERATE  -> Icons.Default.AutoAwesome
        ToolInfoIcon.FILE      -> Icons.Default.Description
        ToolInfoIcon.FOLDER    -> Icons.Default.Folder
        ToolInfoIcon.COMMAND   -> Icons.Default.PlayArrow
        ToolInfoIcon.TERMINAL  -> Icons.Default.Terminal
        ToolInfoIcon.WORLD     -> Icons.Default.Public
        ToolInfoIcon.LINK      -> Icons.Default.Link
        ToolInfoIcon.PERSON    -> Icons.Default.Person
        ToolInfoIcon.CHUNK     -> Icons.Default.Extension
        ToolInfoIcon.ROCKET    -> Icons.Default.RocketLaunch
        ToolInfoIcon.MOUSE     -> Icons.Default.Mouse
        ToolInfoIcon.BOOK      -> Icons.Default.Book
        ToolInfoIcon.IMAGE     -> Icons.Default.Image
        ToolInfoIcon.DELETE    -> Icons.Default.Delete
        ToolInfoIcon.BRAIN     -> Icons.Default.Psychology
        ToolInfoIcon.LIGHTBULB -> Icons.Default.Lightbulb
    }
}
