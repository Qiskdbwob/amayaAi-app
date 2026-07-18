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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
