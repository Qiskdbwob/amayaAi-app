package com.amaya.intelligence.ui.components.shared

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp


import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
@OptIn(ExperimentalMaterial3Api::class)
class StandardModalSheetState internal constructor(
    internal val sheetState: SheetState,
    private val scope: CoroutineScope,
    private val onDismissRequest: () -> Unit,
    private val dismissible: Boolean,
    private val dismissVelocityThreshold: Float
) {
    private var dismissing = false
    private var sheetHeight = 0f
    private var settleJob: Job? = null

    internal var dragOffset by mutableFloatStateOf(0f)
        private set

    fun dismiss(afterDismiss: (() -> Unit)? = null) {
        if (dismissing) return
        dismissing = true
        settleJob?.cancel()
        scope.launch {
            try {
                sheetState.hide()
            } finally {
                onDismissRequest()
                afterDismiss?.invoke()
            }
        }
    }

    internal fun finishDismiss() {
        if (dismissing) return
        dismissing = true
        settleJob?.cancel()
        onDismissRequest()
    }

    internal fun updateSheetHeight(height: Int) {
        sheetHeight = height.toFloat()
    }

    internal fun dragBy(delta: Float): Float {
        if (!dismissible || dismissing) return 0f
        settleJob?.cancel()
        val previousOffset = dragOffset
        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
        return dragOffset - previousOffset
    }

    internal fun settleDrag(velocity: Float) {
        if (dismissing || dragOffset == 0f) return
        val draggedFarEnough = sheetHeight > 0f &&
            dragOffset >= sheetHeight * StandardModalSheetDefaults.DragDismissFraction
        if (dismissible && (draggedFarEnough || velocity >= dismissVelocityThreshold)) {
            dismiss()
            return
        }

        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = 0f,
                initialVelocity = velocity,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ -> dragOffset = value }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberStandardModalSheetState(
    onDismissRequest: () -> Unit,
    dismissible: Boolean = true
): StandardModalSheetState {
    val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val density = LocalDensity.current
    val dismissVelocityThreshold = with(density) {
        StandardModalSheetDefaults.DragDismissVelocityThreshold.toPx()
    }
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { dismissible || it != SheetValue.Hidden }
    )
    val scope = rememberCoroutineScope()
    return remember(sheetState, scope, dismissible, dismissVelocityThreshold) {
        StandardModalSheetState(
            sheetState = sheetState,
            scope = scope,
            onDismissRequest = { currentOnDismissRequest.value() },
            dismissible = dismissible,
            dismissVelocityThreshold = dismissVelocityThreshold
        )
    }
}

private val BlockBottomSheetBodyDragConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

@Composable
internal fun Modifier.blockBottomSheetBodyDrag(): Modifier {
    val bodyDragState = rememberDraggableState { }
    return draggable(
        state = bodyDragState,
        orientation = Orientation.Vertical
    ).nestedScroll(BlockBottomSheetBodyDragConnection)
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun standardModalBottomSheetProperties(dismissible: Boolean = true) = ModalBottomSheetProperties(
    shouldDismissOnBackPress = dismissible
)


@OptIn(ExperimentalLayoutApi::class)
@Composable
fun imeAnimationProgress(): Float {
    val density = LocalDensity.current
    val current = WindowInsets.ime.getBottom(density)
    val source = WindowInsets.imeAnimationSource.getBottom(density)
    val target = WindowInsets.imeAnimationTarget.getBottom(density)
    val expandedHeight = maxOf(current, source, target)
    return if (expandedHeight == 0) 0f else current.toFloat() / expandedHeight
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun responsiveBottomSheetShape(sheetState: SheetState): Shape {
    val density = LocalDensity.current
    val statusBarHeight = WindowInsets.statusBars.getTop(density)
    val isFullWidth by remember(sheetState) {
        derivedStateOf {
            val offset = try { sheetState.requireOffset() } catch (e: Exception) { Float.MAX_VALUE }
            // Trigger when touching or very close to status bar (e.g. 1dp buffer)
            offset <= (statusBarHeight + 1f)
        }
    }

    val cornerSize by animateDpAsState(
        targetValue = if (isFullWidth) 0.dp else 28.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sheet_corner_animation"
    )
    return RoundedCornerShape(topStart = cornerSize, topEnd = cornerSize)
}
