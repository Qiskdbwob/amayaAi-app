package com.amaya.intelligence.ui.components.shared

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// ── Conversation skeleton ────────────────────────────────────────────────────
//
// Shown while a conversation's history is being read back. It has one job: hold
// the shape the real timeline is about to take, so that when the messages land
// nothing jumps. That means matching the real list's geometry exactly — same
// 18dp side padding, same 16dp arrangement spacing, top-anchored under the
// header — and using shapes that resemble what actually loads (a short
// right-side user bubble, a full-width assistant text stack, a bordered tool
// card) rather than abstract blocks.
//
// The motion is the same travelling sweep used by every other shimmering
// surface in the app (drawer titles, running tool headers, todo pills), not an
// alpha pulse — an alpha pulse on a container tone is close to invisible in
// dark mode.

/**
 * One travelling gleam across whatever this modifier wraps.
 *
 * [progress] is hoisted by the caller so a whole column of skeleton rows shares
 * a single wave instead of each row shimmering on its own clock.
 *
 * Drawn with [BlendMode.SrcAtop] so the gleam only lands where content already
 * is, and each element keeps its own alpha — a faint card border stays faint
 * while a solid bar stays solid.
 */
internal fun Modifier.skeletonShimmer(progress: Float, highlight: Color): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            val peakX = (progress * (w * 3f)) - w
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to highlight,
                    1f to Color.Transparent,
                    startX = peakX - (w * 0.5f),
                    endX = peakX + (w * 0.5f)
                ),
                blendMode = BlendMode.SrcAtop
            )
        }

/**
 * Placeholder timeline for a conversation that is still loading.
 *
 * @param contentPadding must mirror the real `LazyColumn`'s `contentPadding` so
 *        the crossfade into real content does not shift anything.
 */
@Composable
fun ConversationSkeleton(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp)
) {
    val isDark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.07f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.13f else 0.09f)

    val transition = rememberInfiniteTransition(label = "conversation_skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeleton_sweep"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .skeletonShimmer(progress, highlight),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SkeletonUserBubble(base, widthFraction = 0.52f)
        SkeletonAssistantText(base, widthFractions = listOf(1f, 0.92f, 0.64f))
        SkeletonToolCard(base)
        SkeletonAssistantText(base, widthFractions = listOf(0.96f, 1f, 0.78f, 0.4f))
        SkeletonUserBubble(base, widthFraction = 0.66f)
        SkeletonAssistantText(base, widthFractions = listOf(1f, 0.58f))
    }
}

/** Right-aligned user bubble — same corner profile as the real one. */
@Composable
private fun SkeletonUserBubble(base: Color, widthFraction: Float) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .height(44.dp)
                .clip(RoundedCornerShape(21.dp, 21.dp, 6.dp, 21.dp))
                .background(base)
        )
    }
}

/** Full-width assistant answer: a stack of text lines of uneven length. */
@Composable
private fun SkeletonAssistantText(base: Color, widthFractions: List<Float>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        widthFractions.forEach { fraction ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(base)
            )
        }
    }
}

/** Collapsed tool block — the outline plus its lead pill and header line. */
@Composable
private fun SkeletonToolCard(base: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(toolCardBorder(), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 20.dp)
                .clip(CircleShape)
                .background(base)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.54f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(base)
        )
    }
}
