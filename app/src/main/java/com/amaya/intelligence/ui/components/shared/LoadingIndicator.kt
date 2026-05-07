package com.amaya.intelligence.ui.components.shared

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Default Loading Indicator - Morphing shape style.
 * Shape yang berubah-ubah dengan pulse animation.
 * Tanpa bubble, langsung animasi di kiri.
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "morph_loading")
    
    val morphProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "morph"
    )
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // Direct placement - padding comes from parent container
    val morphPhase = (morphProgress * 3) % 1f
    val cornerRadius = when {
        morphPhase < 0.5f -> 0.5f - morphPhase
        else -> morphPhase - 0.5f
    } * 10
    
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
                .clip(RoundedCornerShape(cornerRadius.dp))
                .background(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(color, color.copy(alpha = 0.7f))
                    )
                )
        )
    }
}

/**
 * Pulsing Dots - simple and lightweight.
 * 3 dots yang fade in/out bergantian.
 * Tanpa bubble, langsung di kiri.
 */
@Composable
fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotCount: Int = 3,
    dotSize: Float = 8f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_dots")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(dotCount) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 150)
                ),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha))
            )
        }
    }
}

/**
 * Circular Spinner - classic loading spinner.
 * Tanpa bubble, langsung animasi di kiri.
 */
@Composable
fun CircularSpinnerIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Float = 3f,
    size: Float = 24f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "circular_spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(size.dp)
                .graphicsLayer { rotationZ = rotation },
            color = color,
            strokeWidth = strokeWidth.dp,
            trackColor = color.copy(alpha = 0.15f)
        )
    }
}

/**
 * Bouncing Dots - dots yang bounce up and down.
 * Tanpa bubble, langsung di kiri.
 */
@Composable
fun BouncingDotsIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    dotCount: Int = 3,
    dotSize: Float = 8f
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce_dots")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(dotCount) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 100)
                ),
                label = "bounce_$index"
            )
            Box(
                modifier = Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(color)
                    .graphicsLayer { 
                        translationY = -offsetY * 8f
                    }
            )
        }
    }
}

/**
 * Conversation skeleton for empty state loading.
 */
@Composable
fun ConversationSkeleton() {
    val transition = rememberInfiniteTransition(label = "conversation_skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        repeat(4) { index ->
            val alignEnd = index % 2 == 1
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = alpha),
                    modifier = Modifier
                        .fillMaxWidth(if (alignEnd) 0.62f else 0.78f)
                        .height(if (index == 2) 90.dp else 56.dp)
                ) {}
            }
        }
    }
}