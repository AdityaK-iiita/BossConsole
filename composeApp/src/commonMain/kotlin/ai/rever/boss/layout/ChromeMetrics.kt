package ai.rever.boss.layout

import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the window's chrome costs a browser tab, and what is left for the page.
 *
 * @property vertical Total height taken by bars above and below the content, dividers included.
 * @property horizontal Total width taken by the icon strips.
 */
data class ChromeBudget(
    val vertical: Dp,
    val horizontal: Dp,
) {
    /**
     * The share of [windowHeight] left for page content, 0f..1f.
     *
     * Returns 0f for a non-positive [windowHeight] — a window can be measured mid-layout, and a
     * readout showing "0%" for one frame beats dividing by zero.
     */
    fun verticalFractionOf(windowHeight: Dp): Float =
        if (windowHeight <= 0.dp) 0f else ((windowHeight - vertical) / windowHeight).coerceIn(0f, 1f)

    /** The share of [windowWidth] left for page content, 0f..1f. See [verticalFractionOf]. */
    fun horizontalFractionOf(windowWidth: Dp): Float =
        if (windowWidth <= 0.dp) 0f else ((windowWidth - horizontal) / windowWidth).coerceIn(0f, 1f)
}

/**
 * The chrome budget for a browser tab in the main panel.
 *
 * Exists because "the browser feels cramped" is otherwise unfalsifiable: there was no way to say
 * from inside the app that the page gets 84.7% of the window, so any change here could only be
 * argued rather than shown. The unit test pins the shipped defaults, so adding or resizing a bar
 * without accounting for it fails a test rather than quietly costing a user another 30dp.
 *
 * Mirrors what `BossAppScaffold` actually draws, and is derived from the same two settings objects
 * it gates on, so the two cannot drift apart silently.
 */
object ChromeMetrics {
    /**
     * Chrome around a browser tab in the main panel, in its steady state.
     *
     * "Steady state" means no hover-reveal: a bar that focus mode is clearing counts as absent even
     * though sweeping the window edge brings it back temporarily. That is the honest number for
     * "how much room does the page get while I am reading it".
     *
     * Deliberately **excludes**:
     * - `BossPanelTopBar` (`panelTopBarHeight`) - that is a `SidePanel` header. A browser tab in the
     *   main panel never pays it, and counting it here overstated the cost of the main panel by
     *   28dp in the first draft of issue #239. Do not add it back.
     * - `UpdateBanner` - present only when an update is waiting, and deliberately drawn even in
     *   focus mode. Transient, so it does not belong in a steady-state budget.
     * - Split view. Each additional panel adds its own tab bar and its own border ring; this
     *   measures a single panel, which is the best case, so a real split is never *better* than
     *   this number says.
     *
     * [dimens] has no default on purpose. Once density is user-selectable, a defaulted
     * `Comfortable` would mean "silently assume the user picked comfortable", and a caller that
     * forgot the argument would get a plausible wrong answer with nothing to catch it.
     */
    fun mainPanelBudget(
        appearance: WindowAppearanceSettings,
        focusMode: FocusModeSettings,
        dimens: ChromeDimens,
    ): ChromeBudget {
        val divider = dimens.dividerThickness

        // Each bar carries its own divider: BossTitleBar and BossTopBar draw a trailing one,
        // BossBottomBar a leading one, and BossMainPanel draws one under the tab bar.
        //
        // The panel's border ring is charged first because it is the one piece of this that no
        // preference can switch off. BossMainPanel draws a border at panelBorderThickness and insets
        // its content by the same amount, on all four sides, whether or not the panel is active - so
        // it is twice the thickness off each axis and a browser tab never gets it back.
        var vertical = dimens.panelBorderThickness * 2

        // Not gated on focus mode: the title row answers only to the appearance preference, since
        // on macOS it is what keeps content clear of the traffic lights.
        if (appearance.showTitleBar) vertical += dimens.titleBarHeight + divider

        if (appearance.showTopBar && !focusMode.hides(FocusModeEdge.TOP)) {
            vertical += dimens.topBarHeight + divider
        }

        // The tab bar has no switch. It is the one piece of chrome a tabbed browser cannot drop.
        vertical += dimens.tabBarHeight + divider

        if (appearance.showBottomBar && !focusMode.hides(FocusModeEdge.BOTTOM)) {
            vertical += dimens.bottomBarHeight + divider
        }

        var horizontal = dimens.panelBorderThickness * 2

        // Each strip draws a VDivider down its inner edge, inside the same AnimatedVisibility that
        // gates the strip itself, so the hairline comes and goes with it exactly as the horizontal
        // dividers do with their bars.
        if (appearance.showLeftStrip && !focusMode.hides(FocusModeEdge.LEFT)) {
            horizontal += dimens.stripWidth + divider
        }
        if (appearance.showRightStrip && !focusMode.hides(FocusModeEdge.RIGHT)) {
            horizontal += dimens.stripWidth + divider
        }

        return ChromeBudget(vertical = vertical, horizontal = horizontal)
    }
}
