package ai.rever.boss.app

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Which open plugin panel's column takes the host's actions, or null when none is open.
 *
 * **Right first, then left, then bottom.** The right column ends where the floating cluster used
 * to sit, so a user who has learned that corner finds them within an inch of it; the left column
 * is the same shape one edge over; the bottom panel is last because its foot spans the whole
 * window, which is the look this placement exists to avoid.
 *
 * One function for both halves of the decision - whether these get a panel foot at all
 * ([FocusQuickActionsPlacement.PANEL_FOOTER] against [FocusQuickActionsPlacement.FLOATING]) and
 * which column draws it. Two expressions could disagree, and the way that fails is a row rendered
 * into a column nothing is composing: Sign Out on screen nowhere.
 *
 * Takes the three roots rather than a component, so the table is testable without a plugin host.
 * `isVisible(left)` and `isVisible(right)` each fold in their own top and bottom halves, so these
 * three cover all five panels.
 */
internal fun hostActionsPanelEdge(
    rightOpen: Boolean,
    leftOpen: Boolean,
    bottomOpen: Boolean,
): Panel? =
    when {
        rightOpen -> right
        leftOpen -> left
        bottomOpen -> bottom
        else -> null
    }

/**
 * The host's own actions as a row at the foot of an open plugin panel's column.
 *
 * The [FocusQuickActionsPlacement.PANEL_FOOTER] rendering, for a window in TOP tab-bar position -
 * no rail, no vertical bar, nothing else left to hold these - with a plugin panel open.
 *
 * **A row inside the panel, not a band across the content area.** The floating cluster it replaces
 * has no click-through on either path, so with a panel open it parks a dead region over the corner
 * of something the user just asked to see. A band spanning the whole window fixes that collision
 * and spends the window's entire width saying four icons; the panel's own foot is chrome at the
 * scale of the thing it belongs to, which is where [hostActionsPanelEdge] puts it.
 *
 * Laid out by [HostActionsFlowRow], the same row the bar's own foot uses, so the two cannot drift
 * in height or spacing. It wraps rather than clipping, which matters here: a panel is resizable
 * down to a floor of about 20dp.
 *
 * Renders nothing at all when [actions] is empty, rule included, so a panel in a window that keeps
 * these somewhere else is exactly the panel that existed before this.
 */
@Composable
internal fun PanelFooterHostActions(actions: List<@Composable () -> Unit>) {
    if (actions.isEmpty()) return

    // Full width, where the rail's rule is short and centred: this row spans its column, and the
    // line is what separates it from the plugin's own content above.
    Divider(color = BossTheme.colors.line)
    // PAINTED, before it is padded. This row is a strip of column that nothing else draws -
    // SidePanel fills itself and stops where its content does - and nothing is not the background:
    // the raw native window surface shows through, which is WHITE. It then puts near-white icons
    // on a white band, so the actions come out invisible rather than merely misplaced. Every other
    // host of these gets its background from the bar or Surface it sits in; this one has to paint.
    //
    // `colors.panel`, what SidePanel fills with, not `raised`: with the rule above doing the
    // separating, the row and the plugin read as one column rather than as a shelf stuck to the
    // bottom of it.
    HostActionsFlowRow(
        tag = PANEL_FOOTER_HOST_ACTIONS_TAG,
        modifier = Modifier.background(BossTheme.colors.panel),
        actions = actions,
    )
}

/** Test tag of the row - see `QuickActionsPanelFooterTest`. */
internal const val PANEL_FOOTER_HOST_ACTIONS_TAG = "panel-footer-host-actions"

/**
 * The actions as a row for the foot of a plugin panel's column.
 *
 * Same buttons, fifth layout. Hints point UP, because the row is the last thing in its column and
 * a hint below it would be off the bottom of the window - the same call the bar's own foot makes.
 * Icons are panel-chrome sized, matching the two bar hosts rather than the cluster's 28dp.
 *
 * Empty for every other placement, so the panel can call it unconditionally and render nothing.
 */
// One parameter per action plus the placement and the launcher slot - see focusQuickActionsFooter.
@Suppress("LongParameterList")
internal fun focusQuickActionsPanelFooter(
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    focusQuickActionsFor(
        owner = FocusQuickActionsPlacement.PANEL_FOOTER,
        hintDirection = top,
        placement = placement,
        onShowSettings = onShowSettings,
        onShowSearch = onShowSearch,
        onSignOut = onSignOut,
        toolbox = toolbox,
        toolLauncher = toolLauncher,
    )
