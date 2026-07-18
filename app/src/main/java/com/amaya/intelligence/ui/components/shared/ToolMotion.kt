package com.amaya.intelligence.ui.components.shared

import com.amaya.intelligence.domain.models.ToolInfoIcon
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
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
    val mountFadeIn = fadeIn(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing))
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
