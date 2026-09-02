package ai.rever.boss.app

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.background
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Which open plugin panel's column takes the host's actions, or null when none of them does.
 *
 * **The right column, and only the right column.** The floating cluster this displaces sits in
 * the content area's bottom-RIGHT corner, so a right panel is the one an open panel actually
 * collides with. The other two were in an earlier revision of this and are deliberately out:
 *
 * - **Left.** There is no collision to fix. A left panel is the width of the window away from the
 *   cluster, so hosting these there would move Sign Out across the window to dodge an overlap
 *   that is not happening - and move it back, disposing and re-creating the overlay's native
 *   window, when the panel closes.
 * - **Bottom.** The collision is real, but the fix contradicts the reason this placement exists:
 *   a bottom panel spans the whole window, so its foot IS the full-width band this rejected in
 *   favour of chrome at the scale of the thing it belongs to. It is also the one column that
 *   cannot yield - `BossResizablePanel` floors a panel at `max(2% of the axis, 20.dp)`, and the
 *   scarce axis for a bottom panel is the one the row needs about 45dp of, so at its floor the
 *   plugin gets no height and `Modifier.size` shrinks the icons to fit rather than overflowing.
 *   A right panel's scarce axis is width, where the row wraps - which is pinned at 20dp.
 *
 * So a TOP-bar window with only a left or bottom panel open keeps the cluster it has today. That
 * is the behaviour this change found, not one it introduces.
 *
 * Still a `Panel?` rather than a Boolean: `BossWindow` gates three columns on
 * `panelFooterEdge == panel`, so the answer has to name one. One function for both halves of the
 * decision - whether these get a panel foot at all ([FocusQuickActionsPlacement.PANEL_FOOTER]
 * against [FocusQuickActionsPlacement.FLOATING]) and which column draws it. Two expressions could
 * disagree, and the way that fails is a row rendered into a column nothing is composing: Sign Out
 * on screen nowhere.
 *
 * Takes the root's visibility rather than the component, so the table is testable without a plugin
 * host. `isVisible(right)` folds in its own top and bottom halves, so one flag covers both right
 * panels.
 */
internal fun hostActionsPanelEdge(rightOpen: Boolean): Panel? = if (rightOpen) right else null

/**
 * The host's own actions as a row at the foot of the open right panel's column.
 *
 * The [FocusQuickActionsPlacement.PANEL_FOOTER] rendering, for a window in TOP tab-bar position -
 * no rail, no vertical bar, nothing else left to hold these - with the right panel open.
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
        actions = actions,
        modifier = Modifier.background(BossTheme.colors.panel),
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
