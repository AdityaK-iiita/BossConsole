package ai.rever.boss.app

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceButton
import ai.rever.boss.components.workspaces.WorkspaceManager
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.Project
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder

/**
 * The project and workspace pickers, at the foot of the vertical tab bar.
 *
 * These live in the top bar. With the top bar switched off there is nowhere else for them, and
 * "which project am I in" stops being answerable from the window at all - so the vertical bar,
 * the one piece of window chrome still on screen in that configuration, takes them.
 *
 * Drawn ONLY when the top bar is off. Both at once would be the same two controls twice, and the
 * top bar is where they belong when it is there.
 *
 * They sit above the split map rather than below it. The map is the bar's last row by design -
 * it is a picture of the window, and a picture of the window belongs at the bottom of the thing
 * that lists what is in it.
 */
@Composable
internal fun VerticalBarWindowControls(
    /**
     * Whether the top bar - which owns these two controls - is off screen.
     *
     * Passed as what is DRAWN rather than what is preferred, so a top bar focus mode has cleared
     * counts: the pickers live nowhere else, and focus mode is exactly when a window is at its
     * barest.
     *
     * Deliberately the STANDING focus-mode state, not the reveal flag. Keyed on the reveal, these
     * would appear and disappear on every hover, and this footer sits directly above the split
     * map - so the map would jump up and down the bar each time. Two copies of a picker for the
     * seconds a bar is revealed is the better of the two.
     */
    topBarHidden: Boolean,
    project: Project,
    onOpenProject: () -> Unit,
    workspaceManager: WorkspaceManager,
    onApplyWorkspace: (LayoutWorkspace) -> Unit,
    getCurrentWorkspace: () -> LayoutWorkspace,
    onShowTopOfMind: () -> Unit,
) {
    if (!topBarHidden) return

    Divider(color = BossTheme.colors.line)
    Column(
        // Tight on purpose. These are two rows of a narrow bar, not a toolbar: the padding that
        // reads as breathing room across a 1500dp top bar reads as dead space down a 200dp one,
        // and there is a split map below them competing for the same inches.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        // Not zero. Two 24dp rows flush against each other put their click targets in direct
        // contact, and a click a pixel off the one you meant activates the other - which for
        // these two means opening the wrong thing entirely, a project dialog or a workspace menu.
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        BossActionButton(
            // A folder rather than the top bar's project LOGO tile. That tile is 28dp of solid
            // colour built to anchor a wide bar; down a 200dp column it is the loudest thing on
            // screen and it is decoration.
            leftIcon = FeatherIcons.Folder,
            // A project with no path is no project: the button then offers the action rather than
            // naming the empty one, which is what the top bar's copy does too.
            text = if (project.path.isEmpty()) "Open Project" else project.name,
            // The top bar's copy hangs a recent-projects menu off this button. Here it opens the
            // project dialog instead - the same one the File menu and the dashboard open - rather
            // than standing up a second recent-projects menu with its own remove and rename
            // dialogs behind it. One control, one window-level dialog.
            //
            // Null, not emptyList: a non-null list makes the button open a menu on click, and an
            // empty one would open an empty menu on top of the dialog.
            contextMenuItems = null,
            hintText = if (project.path.isEmpty()) "Open a project" else project.path,
            maxTextWidth = LABEL_MAX_WIDTH,
            compact = true,
            onClick = onOpenProject,
        )
        WorkspaceButton(
            onOpenWorkspace = onApplyWorkspace,
            workspaceManager = workspaceManager,
            getCurrentWorkspace = getCurrentWorkspace,
            onShowTopOfMind = onShowTopOfMind,
            compact = true,
        )
    }
}

/**
 * How wide a project or workspace name may get before it truncates.
 *
 * The bar is 200dp and these rows carry an icon and a chevron either side of the label, so
 * without a cap a long project name pushes the chevron off the end of its own button.
 */
private val LABEL_MAX_WIDTH = 130.dp

/** Air between the two rows: enough that a click near the boundary cannot land on the other. */
private val ROW_GAP = 4.dp

/**
 * The host's own actions at the very foot of the vertical tab bar, under the split map.
 *
 * Settings, Search, Sign Out and - when both icon strips are off - the tools launcher. This is
 * the [FocusQuickActionsPlacement.TAB_BAR_FOOTER] rendering, chosen over the floating cluster
 * whenever this bar is on screen: the cluster is a native always-on-top window with no
 * click-through, and the bar is chrome the app already draws.
 *
 * A Row, where the right rail lays the same actions out as a Column, because this bar is wide and
 * short of vertical room rather than the other way round.
 *
 * Renders nothing at all when [actions] is empty, padding included, so a bar whose actions live
 * somewhere else is exactly the bar that existed before this.
 */
@Composable
internal fun VerticalBarHostActions(actions: List<@Composable () -> Unit>) {
    if (actions.isEmpty()) return

    // A FlowRow, not a Row, because these do not fit on one line in a narrow bar.
    //
    // The bar goes down to TabBarVerticalWidthRange.start, 120dp. Four 32dp buttons with 4dp
    // between them and 8dp either side need 156dp, and three need exactly 120 - no margin at all.
    // A Row does not wrap, and what it did instead, measured at 120dp, was give its LAST child
    // zero width: Search came back as a 0x0 rect while the other three kept their full size. Not
    // a clipped icon - an absent one, on a width the user can reach by dragging.
    //
    // Wrapping is the fallback and not the shape: at any comfortable width these still lay out
    // side by side, which VerticalBarHostActionsLayoutTest pins along with the narrow case.
    FlowRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(VERTICAL_BAR_HOST_ACTIONS_TAG)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // No key: the list is fixed-order for a given placement, so positional identity is what a
        // key would give - the same call SidebarBottomActions makes about the same three actions.
        actions.forEach { action -> action() }
    }
}

/** Test tag of the footer row - see `VerticalBarHostActionsLayoutTest`. */
internal const val VERTICAL_BAR_HOST_ACTIONS_TAG = "vertical-bar-host-actions"

/**
 * The actions as a row for the foot of the vertical tab bar, under its split map.
 *
 * Same buttons, third layout. Hints point UP, because this row is the last thing in the bar and a
 * hint below it would be off the bottom of the window - the same call the rail makes pointing them
 * inward. Icons are rail-sized rather than the floating cluster's 28dp, since what they sit under
 * is the bar's own chrome.
 *
 * Empty for every other placement, so the bar can call it unconditionally and render nothing.
 */
// One parameter per action plus the placement and the launcher slot. Folding them into a holder
// would put the actions somewhere a caller has to build before it can name one, for no gain.
@Suppress("LongParameterList")
internal fun focusQuickActionsFooter(
    placement: FocusQuickActionsPlacement,
    onShowSettings: () -> Unit,
    onShowSearch: () -> Unit,
    onSignOut: () -> Unit,
    toolbox: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
    toolLauncher: (@Composable (hintDirection: Panel, modifier: Modifier) -> Unit)? = null,
): List<@Composable () -> Unit> =
    if (placement != FocusQuickActionsPlacement.TAB_BAR_FOOTER) {
        emptyList()
    } else {
        focusQuickActionButtons(
            hintDirection = top,
            modifier = Modifier.size(SIDEBAR_ICON_SIZE),
            onShowSettings = onShowSettings,
            toolbox = toolbox,
            onShowSearch = onShowSearch,
            onSignOut = onSignOut,
            toolLauncher = toolLauncher,
        )
    }

/**
 * Whether the vertical tab bar has a foot to put the host's actions in.
 *
 * Three states, not two. An EXPANDED left bar has one. A COLLAPSED one draws its rail and nothing
 * else, so it has none and the actions float. A collapsed bar whose hover drawer is OPEN has one
 * again for as long as the drawer is up, because the drawer is a full bar.
 *
 * Pure and named because it is the one input to [focusQuickActionsPlacement] that is not a
 * standing preference, and because the scaffold that reads it is at detekt's complexity ceiling.
 */
internal fun verticalBarHasFoot(
    tabBarOnLeft: Boolean,
    barCollapsed: Boolean,
    drawerVisible: Boolean,
): Boolean = tabBarOnLeft && (!barCollapsed || drawerVisible)
