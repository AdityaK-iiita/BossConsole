package ai.rever.boss.components.overlays

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.Window

/**
 * Where a heavyweight overlay window (popup, modal) should be placed, shared by
 * [HeavyweightPopup] and [HeavyweightModal].
 *
 * Both need the same thing — cover the parent window exactly, so a full-window scrim lines
 * up with it — and both used to resolve it inline with the same silent fallback. Sharing it
 * makes the fallback loud instead, which matters: the fallback is the path that produces a
 * visibly broken overlay, and it produced it with nothing in the log to say so.
 */
private val logger = BossLogger.forComponent("OverlayWindowBounds")

/**
 * The parent window's screen bounds as `[x, y, width, height]`, or null if they cannot be
 * read — **and a null is logged**, because it is not a benign condition.
 *
 * Keyed on [parent] rather than remembered forever. An overlay composable that stays in
 * composition across opens (or is re-shown after its parent moved between windows) would
 * otherwise keep the bounds it measured the first time, and place itself over the wrong
 * window.
 *
 * AWT reports these in logical units, which map 1:1 to dp in Compose Desktop, so callers
 * stay in dp and never touch the density factor.
 */
@Composable
internal fun rememberOverlayParentBounds(parent: Window?): IntArray? =
    remember(parent) {
        // Bound to a local so the checks below are a smart cast rather than repeated
        // null-safe calls on a captured parameter, which Kotlin will not narrow.
        val window = parent
        val reason =
            when {
                window == null -> "no LocalAwtWindow in scope"
                !window.isShowing -> "parent window is not showing yet"
                else -> null
            }
        if (reason != null || window == null) {
            // Loud on purpose. Falling back means the overlay covers the whole SCREEN instead
            // of the parent window, and the New Tab dialog draws a 40%-black scrim across
            // whatever it covers — so this is the difference between a normal dialog and a
            // grey wash over everything. It was previously silent, which is why an
            // intermittent report of exactly that had nothing to correlate against.
            logger.warn(
                LogCategory.UI,
                "Heavyweight overlay could not measure its parent window - falling back to full screen",
                mapOf("reason" to (reason ?: "parent became null")),
            )
            return@remember null
        }
        runCatching {
            val at = window.locationOnScreen
            intArrayOf(at.x, at.y, window.width, window.height)
        }.onFailure {
            logger.warn(
                LogCategory.UI,
                "Heavyweight overlay could not measure its parent window - falling back to full screen",
                mapOf("reason" to "locationOnScreen threw", "error" to it.toString()),
            )
        }.getOrNull()
    }

/**
 * Window state for an overlay covering [bounds], or the primary screen when they are null.
 *
 * The fallback is an EXPLICIT position and size, never `WindowPlacement.Maximized`, which is
 * what this used before. Maximizing an undecorated, transparent window routes through the
 * platform's own zoom path, and on macOS a transparent window taken through it can come up
 * opaque — turning the "could not measure the parent" fallback into a solid window over
 * everything rather than merely a mis-sized transparent one. An explicit rect establishes
 * transparency identically in both paths, so the fallback degrades in one way instead of two.
 *
 * One `rememberWindowState` call, not one per branch: branching would give the two cases
 * separate composition slots and reset the state if [bounds] ever resolved late.
 */
@Composable
internal fun rememberOverlayWindowState(bounds: IntArray?): WindowState {
    val screen =
        remember {
            runCatching {
                GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .defaultConfiguration
                    .bounds
            }.map { intArrayOf(it.x, it.y, it.width, it.height) }
                // Last resort only if AWT cannot describe a screen at all. A fixed rect beats
                // a zero-sized window, which would swallow the dismissing click and leave the
                // overlay unclosable.
                .getOrDefault(intArrayOf(0, 0, 1280, 800))
        }
    val rect = overlayRectOrScreen(bounds, screen)
    return rememberWindowState(
        position = WindowPosition(rect[0].dp, rect[1].dp),
        size = DpSize(rect[2].dp, rect[3].dp),
    )
}

/**
 * The rect an overlay window should occupy: the measured parent, else the screen.
 *
 * Trivial, and split out anyway so the choice is pinned by a test — composing a `Window` needs
 * a display, so this is the only part of the decision a unit test can reach, and it is the part
 * that regressed into `WindowPlacement.Maximized`.
 */
internal fun overlayRectOrScreen(
    bounds: IntArray?,
    screen: IntArray,
): IntArray = bounds ?: screen

/**
 * Re-assert that an overlay window is actually translucent, because `transparent = true`
 * intermittently does not take on macOS.
 *
 * Observed on a live macOS HARDWARE build: opening the New Tab dialog produced a flat mid-grey
 * over the whole window instead of the app dimmed behind the dialog. The dialog itself drew
 * correctly, which is what identifies the layer at fault — the backdrop, not the content.
 *
 * The colour is the evidence. The dialog scrim is `Color.Black.copy(alpha = 0.4f)`. Over the
 * app's own dark chrome that composites to nearly black; the observed backdrop was a uniform
 * mid-grey, which is what 40% black over an opaque LIGHT background gives (0.6 x 255 = #999,
 * or #8E over AWT's default #ECECEC). So the scrim was compositing over the overlay window's
 * own opaque background rather than over the app beneath it.
 *
 * Compose's `transparent = true` reaches AWT as a fully transparent window background, and on
 * macOS that has to be applied before the native window is ordered on screen. When it loses
 * that race the window stays opaque for its whole life — it is created once and never resized,
 * so nothing later triggers a correction. Hence: check after realization, and set it again.
 *
 * Deliberately a CHECK-then-set with a warning rather than an unconditional assignment. If the
 * race is not the cause, this stays silent and changes nothing, and the absence of the warning
 * is itself the signal that the diagnosis was wrong — which is the same reason the bounds
 * fallback above logs. Assigning unconditionally would hide that.
 */
@Composable
internal fun EnsureOverlayWindowTransparent(window: Window) {
    // SideEffect, not DisposableEffect with an empty onDispose: there is nothing to undo when the
    // window goes away, and an empty onDispose invites a reader to wonder what is missing.
    SideEffect {
        runCatching {
            if (window.background?.alpha != 0) {
                logger.warn(
                    LogCategory.UI,
                    "Overlay window came up opaque - re-asserting transparency",
                    mapOf("background" to window.background.toString()),
                )
                window.background = java.awt.Color(0, 0, 0, 0)
            }
        }.onFailure {
            // Never fatal: an opaque overlay is ugly, an exception here would take the dialog
            // down with it.
            logger.warn(
                LogCategory.UI,
                "Could not re-assert overlay window transparency",
                mapOf("error" to it.toString()),
            )
        }
    }
}
