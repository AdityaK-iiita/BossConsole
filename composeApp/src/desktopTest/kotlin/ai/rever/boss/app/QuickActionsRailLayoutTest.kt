package ai.rever.boss.app

import ai.rever.boss.components.buttons.TOOL_LAUNCHER_TAG
import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.layout.ChromeDimens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the host's actions fit the COLLAPSED rail, which is the narrowest thing that has ever
 * had to hold them.
 *
 * The rail is [ChromeDimens.MIN_STRIP_WIDTH] at the tightest density - 36dp for a 32dp button, so
 * there is 2dp either side and no room for a second column. The expanded bar's foot learned this
 * the hard way (see `VerticalBarHostActionsLayoutTest`): a `Row` that cannot fit its children does
 * not clip them, it hands the last one ZERO width, which renders as an action that is simply not
 * there. So these assertions check each icon's SIZE and not merely that it sits between the rail's
 * edges - a zero-width rect at the origin passes every bounds check ever written.
 */
class QuickActionsRailLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mountRail() {
        val actions =
            focusQuickActionsTabRail(
                placement = FocusQuickActionsPlacement.TAB_BAR_RAIL,
                onShowSettings = {},
                onShowSearch = {},
                onSignOut = {},
                toolLauncher = { hintDirection, modifier ->
                    ToolLauncherButton(onClick = {}, hintDirection = hintDirection, modifier = modifier)
                },
            )
        rule.setContent {
            // clipToBounds mirrors the rail, which is what turns an overflow into a vanished
            // button rather than one drawn outside its column.
            Column(
                modifier =
                    Modifier
                        .width(ChromeDimens.MIN_STRIP_WIDTH)
                        .clipToBounds()
                        .testTag(RAIL_TAG),
            ) {
                VerticalBarRailActions(actions)
            }
        }
        rule.waitForIdle()
    }

    private fun railBounds(): Rect = rule.onNodeWithTag(RAIL_TAG).fetchSemanticsNode().boundsInRoot

    private fun iconBounds(contentDescription: String): Rect =
        rule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot

    /** Present at its full size AND within the rail's edges. Either alone passes the bug. */
    private fun assertShown(contentDescription: String) {
        val rail = railBounds()
        val icon = iconBounds(contentDescription)
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }

        assertEquals(expected, icon.width, "$contentDescription is ${icon.width}px wide, expected $expected")
        assertEquals(expected, icon.height, "$contentDescription is ${icon.height}px tall, expected $expected")
        assertTrue(
            icon.left >= rail.left && icon.right <= rail.right,
            "$contentDescription spans ${icon.left}..${icon.right}, outside the rail's ${rail.left}..${rail.right}",
        )
    }

    @Test
    fun `every action fits the narrowest rail`() {
        mountRail()

        assertShown("Sign Out")
        assertShown("Settings")
        assertShown("Tools")
        assertShown("Search")

        // By tag as well as by label, for the same reason the bar's own test does it: a tag
        // nothing reads is a tag that can be renamed without a failure.
        val launcher = rule.onNodeWithTag(TOOL_LAUNCHER_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(
            with(rule.density) { SIDEBAR_ICON_SIZE.toPx() },
            launcher.width,
            "the launcher's own tag must find it at full size",
        )
    }

    @Test
    fun `they stack one per line, in the order the other hosts use`() {
        // A column, not a wrapping row: at 36dp there is room for exactly one, and the order is
        // Sign Out first so the destructive action is furthest from the window corner.
        mountRail()

        val signOut = iconBounds("Sign Out")
        val settings = iconBounds("Settings")
        val search = iconBounds("Search")

        assertTrue(signOut.bottom <= settings.top, "Sign Out ($signOut) must sit above Settings ($settings)")
        assertTrue(settings.bottom <= search.top, "Settings ($settings) must sit above Search ($search)")
    }

    @Test
    fun `every action survives a short rail`() {
        // The rail's SCARCE dimension once four or five stacked 32dp icons are added to it is
        // height, not width - and this column is the LAST thing in it, under a chevron, two
        // rules, the scrolling tab list and the "+". A Column that cannot fit its children gives
        // the last ones zero height, which is the same failure the bar's foot had on the other
        // axis: not a clipped icon, an absent one.
        //
        // The tab list stands in for BossTabRail's own weight(1f) scroll region, which is what is
        // supposed to yield first.
        rule.setContent {
            Column(
                modifier =
                    Modifier
                        .width(ChromeDimens.MIN_STRIP_WIDTH)
                        .height(SHORT_RAIL_HEIGHT)
                        .clipToBounds()
                        .testTag(RAIL_TAG),
            ) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f))
                VerticalBarRailActions(
                    focusQuickActionsTabRail(
                        placement = FocusQuickActionsPlacement.TAB_BAR_RAIL,
                        onShowSettings = {},
                        onShowSearch = {},
                        onSignOut = {},
                        toolbox = { hintDirection, modifier ->
                            ToolLauncherButton(onClick = {}, hintDirection = hintDirection, modifier = modifier)
                        },
                        toolLauncher = { hintDirection, modifier ->
                            ToolLauncherButton(onClick = {}, hintDirection = hintDirection, modifier = modifier)
                        },
                    ),
                )
            }
        }
        rule.waitForIdle()

        val rail = railBounds()
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }
        listOf("Sign Out", "Settings", "Search").forEach { label ->
            val icon = iconBounds(label)
            assertEquals(expected, icon.height, "$label is ${icon.height}px tall, expected $expected")
            assertTrue(icon.bottom <= rail.bottom, "$label ends at ${icon.bottom}, past the rail's ${rail.bottom}")
        }
    }

    @Test
    fun `an empty list draws nothing, rule included`() {
        // What lets the rail hand this slot every placement's list: a rail whose actions live in
        // the top bar has to be exactly the rail that existed before this.
        rule.setContent {
            Column(modifier = Modifier.width(ChromeDimens.MIN_STRIP_WIDTH).testTag(RAIL_TAG)) {
                VerticalBarRailActions(
                    focusQuickActionsTabRail(
                        placement = FocusQuickActionsPlacement.TAB_BAR_FOOTER,
                        onShowSettings = {},
                        onShowSearch = {},
                        onSignOut = {},
                    ),
                )
            }
        }
        rule.waitForIdle()

        rule.onAllNodesWithTag(VERTICAL_BAR_RAIL_ACTIONS_TAG).assertCountEquals(0)
        assertEquals(0f, railBounds().height, "the empty slot must take no height at all")
    }
}

private const val RAIL_TAG = "quick-actions-rail-layout-test-rail"

/**
 * A rail with less height than its content wants.
 *
 * Five 32dp icons with `space.xs` between them plus the rule is about 180dp, and the rail's own
 * chrome (chevron, two rules, the "+") is another 90dp - so this is short enough that something
 * has to give, and the scrolling tab list is what should.
 */
private val SHORT_RAIL_HEIGHT = 200.dp
