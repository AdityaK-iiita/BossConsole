package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.platform.CursorUtil.cursorForHorizontalResize
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.TabBarVerticalWidthRange
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * How wide the grab strip is.
 *
 * A hairline is not a pointer target, so the divider is widened rather than overlaid. 6dp is a
 * compromise the layout can absorb: it reads as a divider with a little air around it, and it
 * costs the content 5dp.
 *
 * Overlaying a wider invisible band was the first attempt and it was wrong twice over. Laid out
 * as a Row child it took 16dp of real width and painted nothing, so the unpainted native surface
 * showed through as a white column. Aligned over the CONTENT side instead - the way
 * BossResizablePanel does it inside a BoxWithConstraints - it would be dead under a browser pane,
 * because JxBrowser composites its surface ABOVE the Compose scene and the pointer never reaches
 * Compose there. Everything this touches has to be chrome the app itself paints.
 */
private val RESIZE_AREA = 6.dp

@Composable
internal fun VerticalTabBarResizeHandle(
    enabled: Boolean,
    currentWidth: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    if (!enabled) {
        VDivider()
        return
    }

    // rememberUpdatedState, because the gesture coroutine outlives the composition that started
    // it: a drag begun before a recomposition would otherwise go on reporting to the callbacks
    // captured when it started, and go on measuring from a width that has since moved.
    val latestWidth by rememberUpdatedState(currentWidth)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestCommit by rememberUpdatedState(onCommit)

    Row(
        modifier =
            Modifier
                .fillMaxHeight()
                .width(RESIZE_AREA)
                // Painted, not transparent. An unpainted strip is not "the background showing
                // through": nothing in the Compose scene covers it, so what shows is the raw
                // native window surface, which is white.
                .background(BossTheme.colors.panel),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .cursorForHorizontalResize()
                    // pointerInput(Unit), so the gesture is not restarted by the width changing
                    // under it - which it does on every frame of the drag this block reports.
                    .pointerInput(Unit) {
                        var startWidth = latestWidth
                        var accumulated = 0f
                        detectDragGestures(
                            onDragStart = {
                                startWidth = latestWidth
                                accumulated = 0f
                            },
                            onDragEnd = { latestCommit(clampBarWidth(startWidth + accumulated.toDp().value)) },
                            onDragCancel = { latestCommit(clampBarWidth(startWidth + accumulated.toDp().value)) },
                        ) { change, dragAmount ->
                            change.consume()
                            accumulated += dragAmount.x
                            latestPreview(clampBarWidth(startWidth + accumulated.toDp().value))
                        }
                    },
        )
        // The hairline stays where it always was, on the content side, so widening the grab area
        // did not move the line the eye reads as the boundary.
        VDivider()
    }
}

/** A width the bar can actually be. The one place the range is applied to a dragged value. */
internal fun clampBarWidth(dp: Float): Float = dp.coerceIn(TabBarVerticalWidthRange)
