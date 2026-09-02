package ai.rever.boss.app

import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.components.buttons.ToolboxButton
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.window_panel.PanelColumn
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.SidebarItem
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the two claims [PanelFooterHostActions] makes that the floating cluster could not: that it
 * takes a row out of the panel's column rather than drawing over it, and that the row is the
 * PANEL's width rather than the window's.
 *
 * The second is not cosmetic bookkeeping - it was the first version of this placement. A band
 * across the whole content area also stops the cluster covering an open panel, and it looks like
 * what it is: a strip of dead chrome the width of the screen holding four icons.
 */
class QuickActionsPanelFooterTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * The panel column, mounted through the REAL [PanelColumn] rather than a copy of its layout.
     *
     * That is what makes the carve-out assertions below mean anything: a test that rebuilds the
     * structure it means to pin passes just as happily when the production wrapper loses its
     * `weight(1f)` or draws the footer above the content.
     */
    private fun mountPanelColumn(
        placement: FocusQuickActionsPlacement,
        width: Dp = PANEL_WIDTH,
        everyButton: Boolean = false,
    ) {
        rule.setContent {
            val toolbox: (@Composable (Panel, Modifier) -> Unit)? =
                if (!everyButton) {
                    null
                } else {
                    { hint, mod ->
                        ToolboxButton(item = toolboxItem(), onClick = {}, hintDirection = hint, modifier = mod)
                    }
                }
            val launcher: (@Composable (Panel, Modifier) -> Unit)? =
                if (!everyButton) {
                    null
                } else {
                    { hint, mod -> ToolLauncherButton(onClick = {}, hintDirection = hint, modifier = mod) }
                }
            // clipToBounds mirrors the panel, which is what turns an overflow into a vanished
            // button rather than one drawn outside its column.
            Box(
                modifier =
                    Modifier
                        .width(width)
                        .height(400.dp)
                        .clipToBounds()
                        .testTag(COLUMN_TAG),
            ) {
                PanelColumn(
                    footer = {
                        PanelFooterHostActions(
                            actions =
                                focusQuickActionsPanelFooter(
                                    placement = placement,
                                    onShowSettings = {},
                                    onShowSearch = {},
                                    onSignOut = {},
                                    toolbox = toolbox,
                                    toolLauncher = launcher,
                                ),
                        )
                    },
                ) {
                    // Stands in for SidePanel: the plugin's own content, which is what the
                    // cluster used to be drawn over.
                    Box(modifier = Modifier.fillMaxSize().testTag(PLUGIN_TAG))
                }
            }
        }
        rule.waitForIdle()
    }

    private fun toolboxItem() =
        SidebarItem(
            pluginContentId = PanelIds.PLUGIN_MANAGER,
            icon = Icons.Outlined.Extension,
            label = "Toolbox",
        )

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun `the row is carved out of the panel, not drawn over it`() {
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        val plugin = bounds(PLUGIN_TAG)
        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)

        assertTrue(row.height > 0f, "the row reserved no height at all")
        assertTrue(
            plugin.bottom <= row.top,
            "the plugin ends at ${plugin.bottom} but the row starts at ${row.top} - they overlap",
        )
        assertEquals(
            bounds(COLUMN_TAG).bottom,
            row.bottom,
            "the row belongs at the very bottom of the column",
        )
    }

    @Test
    fun `the row is the panel's width, not the window's`() {
        // The whole point of moving it here from a content-area band. Asserted against the
        // column it was given rather than a number, so a different panel width still pins it.
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        val column = bounds(COLUMN_TAG)
        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)

        assertEquals(column.left, row.left, "the row starts where its column does")
        assertEquals(column.right, row.right, "and ends where its column does")
    }

    @Test
    fun `every button sits inside the row it reserved`() {
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER)

        val row = bounds(PANEL_FOOTER_HOST_ACTIONS_TAG)
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }

        // The Toolbox slot is null here, so it draws no icon of its own - see
        // HostActionsContentTest, which mounts the real button.
        listOf("Sign Out", "Settings", "Search").forEach { label ->
            val icon = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertEquals(expected, icon.width, "$label is ${icon.width}px wide, expected $expected")
            assertTrue(
                icon.top >= row.top && icon.bottom <= row.bottom,
                "$label spans ${icon.top}..${icon.bottom}, outside the row's ${row.top}..${row.bottom}",
            )
            assertTrue(
                icon.left >= row.left && icon.right <= row.right,
                "$label spans ${icon.left}..${icon.right}, outside the row's ${row.left}..${row.right}",
            )
        }
    }

    @Test
    fun `every action survives a panel dragged to its floor`() {
        // BossResizablePanel floors a side panel at max(2% of the window, 20dp) - narrower than
        // ONE 32dp icon. The row wraps rather than clipping, and that claim is only worth making
        // if it holds at the narrowest width a drag can reach, carrying the widest content it
        // ever has: five buttons, which is a TOP tab bar with both icon strips switched off.
        mountPanelColumn(FocusQuickActionsPlacement.PANEL_FOOTER, width = FLOOR_WIDTH, everyButton = true)

        val column = bounds(COLUMN_TAG)
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }
        listOf("Sign Out", "Settings", "Toolbox", "Tools", "Search").forEach { label ->
            val icon = rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            // SIZE, not just bounds: a row that cannot fit its children hands the last one a 0x0
            // rect at the origin, which sits inside every bounds check ever written.
            assertEquals(expected, icon.height, "$label is ${icon.height}px tall, expected $expected")
            assertTrue(
                icon.top >= column.top && icon.bottom <= column.bottom,
                "$label spans ${icon.top}..${icon.bottom}, outside the column's ${column.top}..${column.bottom}",
            )
        }
    }

    @Test
    fun `the row paints its own background`() {
        // The one regression in this placement that no bounds assertion can see: the row is a
        // strip of column nothing else draws - SidePanel fills itself and stops where its content
        // does - and nothing is not the background. The raw native window surface shows through,
        // which is WHITE, and these are near-white icons.
        //
        // So this reads a PIXEL. Compose's semantics tree carries no colour, and every other test
        // in this file passes just as happily against an unpainted row.
        var expected: Color? = null
        rule.setContent {
            expected = BossTheme.colors.panel
            Box(modifier = Modifier.width(PANEL_WIDTH).height(400.dp).testTag(COLUMN_TAG)) {
                PanelColumn(
                    footer = {
                        PanelFooterHostActions(
                            actions =
                                focusQuickActionsPanelFooter(
                                    placement = FocusQuickActionsPlacement.PANEL_FOOTER,
                                    onShowSettings = {},
                                    onShowSearch = {},
                                    onSignOut = {},
                                ),
                        )
                    },
                ) {
                    Box(modifier = Modifier.fillMaxSize().testTag(PLUGIN_TAG))
                }
            }
        }
        rule.waitForIdle()

        val row = rule.onNodeWithTag(PANEL_FOOTER_HOST_ACTIONS_TAG).captureToImage().toPixelMap()
        // Two pixels in from the row's start edge, halfway down it. The buttons are centred in a
        // 250dp column and take about 140dp of it, so this lands on background and not on a glyph
        // - and the row is a flat fill, so there is nothing to antialias against.
        val painted = row[2, row.height / 2]

        assertEquals(
            expected,
            painted,
            "the row came back $painted, not the panel fill - an unpainted strip shows the white window surface",
        )
    }

    @Test
    fun `any other placement leaves the panel exactly as it was`() {
        // The zero-cost half of the design: a window with nothing open gets the overlay, and no
        // panel anywhere should quietly grow a row of chrome in the meantime.
        mountPanelColumn(FocusQuickActionsPlacement.FLOATING)

        rule.onAllNodesWithTag(PANEL_FOOTER_HOST_ACTIONS_TAG).assertCountEquals(0)
        assertEquals(
            bounds(COLUMN_TAG).height,
            bounds(PLUGIN_TAG).height,
            "the plugin must still fill the whole column",
        )
    }
}

/**
 * Pins which column hosts the row, which is also the answer to "is there a panel foot at all".
 *
 * One function for both, because two expressions could disagree - and the way that fails is the
 * row rendered into a column nothing is composing: Sign Out on screen nowhere.
 *
 * The left and bottom columns are the interesting half now: both were candidates in an earlier
 * revision, and both were dropped on purpose - left because the cluster is nowhere near it, bottom
 * because its foot is the full-window band this placement exists to avoid AND because it is the
 * one column with no axis that can yield at its floor. A test rather than a comment, so growing
 * the table back is a decision someone makes rather than one that lands quietly.
 */
class HostActionsPanelEdgeTest {
    @Test
    fun `the right column takes them, being the one the cluster sat on`() {
        assertEquals(right, hostActionsPanelEdge(rightOpen = true))
    }

    @Test
    fun `a shut right panel means no column, which is what sends them back to the corner`() {
        // Whatever else is open. A left or bottom panel does not host these - see the KDoc.
        assertNull(hostActionsPanelEdge(rightOpen = false))
    }
}

private val PANEL_WIDTH = 250.dp

/** `BossResizablePanel`'s floor for a side panel - narrower than one 32dp icon. */
private val FLOOR_WIDTH = 20.dp
private const val COLUMN_TAG = "panel-footer-test-column"
private const val PLUGIN_TAG = "panel-footer-test-plugin"
