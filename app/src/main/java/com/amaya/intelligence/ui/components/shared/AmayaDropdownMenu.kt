package com.amaya.intelligence.ui.components.shared

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * App-wide dropdown menu.
 *
 * Material's own [androidx.compose.material3.DropdownMenu] only ever tries "below the anchor"
 * first and falls back to pinning the popup at the bottom of the window, which is why menus
 * anchored inside the composer — a few dp above the IME — used to land in unrelated places.
 * This one takes an explicit [AmayaMenuPlacement]/[AmayaMenuAlignment] and only flips when the
 * preferred side genuinely does not fit, so a menu opens where its button is.
 */
object AmayaMenuDefaults {
    /** Container shape, deliberately matching the app's sheet/card radius rather than M3's 4.dp. */
    val Shape: Shape = RoundedCornerShape(16.dp)
    val ItemShape: Shape = RoundedCornerShape(12.dp)

    val MinWidth: Dp = 200.dp
    val MaxWidth: Dp = 320.dp
    val MaxHeight: Dp = 320.dp

    /** Gap between the anchor and the menu container. */
    val AnchorGap: Dp = 8.dp

    /** Minimum breathing room to the window edges before the menu is clamped. */
    val WindowMargin: Dp = 12.dp

    val TonalElevation: Dp = 3.dp
    val ShadowElevation: Dp = 8.dp

    val ContainerPadding: PaddingValues = PaddingValues(vertical = 6.dp)

    /** Inset of each item inside the container, so the item highlight stays inside the radius. */
    val ItemInset: Dp = 6.dp

    val ItemContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

    @Composable
    fun containerColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

    @Composable
    fun border(): BorderStroke =
        BorderStroke(0.7.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
}

/** Horizontal edge of the anchor the menu lines up with. */
enum class AmayaMenuAlignment { Start, End, Center }

/**
 * Preferred vertical side. [Auto] prefers below and flips up when there is no room — use
 * [Above] for anchors that live in the bottom bar/composer and [Below] for top-bar anchors.
 */
enum class AmayaMenuPlacement { Auto, Above, Below }

@Composable
fun AmayaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignment: AmayaMenuAlignment = AmayaMenuAlignment.Start,
    placement: AmayaMenuPlacement = AmayaMenuPlacement.Auto,
    offset: DpOffset = DpOffset.Zero,
    anchorGap: Dp = AmayaMenuDefaults.AnchorGap,
    minWidth: Dp = AmayaMenuDefaults.MinWidth,
    maxWidth: Dp = AmayaMenuDefaults.MaxWidth,
    maxHeight: Dp = AmayaMenuDefaults.MaxHeight,
    /**
     * Non-focusable menus keep the soft keyboard up, which is what the composer menus want:
     * a focusable popup drops the IME and makes the composer jump while the menu is open.
     */
    focusable: Boolean = true,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit
) {
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded

    if (!expandedState.currentState && !expandedState.targetState) return

    val density = LocalDensity.current
    var transformOrigin by remember { mutableStateOf(TransformOrigin(0f, 1f)) }
    val positionProvider = remember(alignment, placement, anchorGap, offset, density) {
        AmayaMenuPositionProvider(
            alignment = alignment,
            placement = placement,
            anchorGapPx = with(density) { anchorGap.roundToPx() },
            marginPx = with(density) { AmayaMenuDefaults.WindowMargin.roundToPx() },
            offsetXPx = with(density) { offset.x.roundToPx() },
            offsetYPx = with(density) { offset.y.roundToPx() },
            onTransformOrigin = { transformOrigin = it }
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        AnimatedVisibility(
            visibleState = expandedState,
            enter = fadeIn(tween(90)) +
                scaleIn(tween(140), initialScale = 0.86f, transformOrigin = transformOrigin),
            exit = fadeOut(tween(80)) +
                scaleOut(tween(80), targetScale = 0.92f, transformOrigin = transformOrigin)
        ) {
            Surface(
                shape = AmayaMenuDefaults.Shape,
                color = AmayaMenuDefaults.containerColor(),
                border = AmayaMenuDefaults.border(),
                tonalElevation = AmayaMenuDefaults.TonalElevation,
                shadowElevation = AmayaMenuDefaults.ShadowElevation,
                modifier = modifier
                    .widthIn(min = minWidth, max = maxWidth)
                    .heightIn(max = maxHeight)
            ) {
                Column(
                    modifier = Modifier
                        .padding(AmayaMenuDefaults.ContainerPadding)
                        // IntrinsicSize.Max before verticalScroll: otherwise a scrolling menu
                        // measures its items at the container's min width and the labels wrap.
                        .width(IntrinsicSize.Max)
                        .verticalScroll(scrollState),
                    content = content
                )
            }
        }
    }
}

/**
 * Standard menu row: inset from the container edges, rounded highlight, optional leading icon.
 */
@Composable
fun AmayaDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false
) {
    val contentColor = when {
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let {
            {
                Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        trailingIcon = trailing,
        contentPadding = AmayaMenuDefaults.ItemContentPadding,
        colors = MenuDefaults.itemColors(
            textColor = contentColor,
            leadingIconColor = if (destructive) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = modifier
            .padding(horizontal = AmayaMenuDefaults.ItemInset, vertical = 1.dp)
            .clip(AmayaMenuDefaults.ItemShape)
            .background(
                if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                else Color.Transparent
            )
    )
}

private class AmayaMenuPositionProvider(
    private val alignment: AmayaMenuAlignment,
    private val placement: AmayaMenuPlacement,
    private val anchorGapPx: Int,
    private val marginPx: Int,
    private val offsetXPx: Int,
    private val offsetYPx: Int,
    private val onTransformOrigin: (TransformOrigin) -> Unit
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val startEdge = anchorBounds.left
        val endEdge = anchorBounds.right - popupContentSize.width
        val preferredX = when (alignment) {
            AmayaMenuAlignment.Start -> if (rtl) endEdge else startEdge
            AmayaMenuAlignment.End -> if (rtl) startEdge else endEdge
            AmayaMenuAlignment.Center ->
                anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        } + if (rtl) -offsetXPx else offsetXPx

        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = preferredX.coerceIn(marginPx, maxX)

        val below = anchorBounds.bottom + anchorGapPx + offsetYPx
        val above = anchorBounds.top - anchorGapPx - offsetYPx - popupContentSize.height
        val fitsBelow = below + popupContentSize.height <= windowSize.height - marginPx
        val fitsAbove = above >= marginPx
        val useAbove = when (placement) {
            AmayaMenuPlacement.Above -> fitsAbove || !fitsBelow
            AmayaMenuPlacement.Below -> !fitsBelow && fitsAbove
            AmayaMenuPlacement.Auto -> !fitsBelow && fitsAbove
        }

        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val y = (if (useAbove) above else below).coerceIn(marginPx, maxY)

        val originX = if (popupContentSize.width == 0) 0.5f else {
            ((anchorBounds.center.x - x).toFloat() / popupContentSize.width).coerceIn(0f, 1f)
        }
        onTransformOrigin(TransformOrigin(originX, if (useAbove) 1f else 0f))

        return IntOffset(x, y)
    }
}
