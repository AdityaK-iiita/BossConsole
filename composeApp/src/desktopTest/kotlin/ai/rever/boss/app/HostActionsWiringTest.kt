package ai.rever.boss.app

import ai.rever.boss.components.window_panel.PanelColumn
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the wiring between the placement decision and the hosts that draw it.
 *
 * The placement table has its own tests and each layout has its own; this covers the seam between
 * them, where every failure is silent. Two hosts drawing at once is Sign Out twice; a host drawing
 * for a placement it does not own is Sign Out in a container nothing is composing.
 *
 * The first test exists because that second failure SHIPPED in this change's first draft: one slot
 * was handed to both the collapsed rail and the hover drawer on the reasoning that "a rail is
 * TAB_BAR_RAIL and a full bar is TAB_BAR_FOOTER, never both". `SplitViewPanel` composes the rail
 * and the drawer TOGETHER - the drawer is an overlay over the rail, not a replacement - so with
 * the drawer up the rail drew the full bar's wrapping row at 36dp wide, four lines of squeezed
 * icons, visible for the frame after dismissal.
 */
class HostActionsWiringTest {
    @get:Rule
    val rule = createComposeRule()

    private fun placementFor(
        drawerVisible: Boolean,
        barCollapsed: Boolean = true,
    ) = focusQuickActionsPlacement(
        settings = FocusModeSettings(),
        topBarHidden = true,
        rightStripHidden = true,
        showTopBar = false,
        verticalBar =
            verticalBarHost(
                tabBarOnLeft = true,
                barCollapsed = barCollapsed,
                drawerVisible = drawerVisible,
            ),
    )

    @Test
    fun `the hover drawer moves the actions out of the rail, never doubles them`() {
        val drawerVisible = mutableStateOf(false)
        rule.setContent {
            val placement = placementFor(drawerVisible = drawerVisible.value)
            // Both hosts composed together on purpose: that is the configuration the bug was in,
            // and asserting on the CONTENT rather than on which slot receives it keeps this
            // honest even if someone shares one slot between the branches again.
            Column(modifier = Modifier.width(BAR_WIDTH)) {
                VerticalBarRailActions(
                    focusQuickActionsTabRail(placement, {}, {}, {}, { _, _ -> }),
                )
                VerticalBarHostActions(
                    focusQuickActionsFooter(placement, {}, {}, {}, { _, _ -> }),
                )
            }
        }
        rule.waitForIdle()

        assertOnly(VERTICAL_BAR_RAIL_ACTIONS_TAG, "a collapsed rail with the drawer shut")

        drawerVisible.value = true
        rule.waitForIdle()
        assertOnly(VERTICAL_BAR_HOST_ACTIONS_TAG, "the drawer is a full bar, so its foot takes them")

        drawerVisible.value = false
        rule.waitForIdle()
        assertOnly(VERTICAL_BAR_RAIL_ACTIONS_TAG, "and dismissing it hands them back to the rail")
    }

    /** Exactly one of the two bar layouts on screen, and it is [expected]. */
    private fun assertOnly(
        expected: String,
        why: String,
    ) {
        val other =
            if (expected == VERTICAL_BAR_RAIL_ACTIONS_TAG) {
                VERTICAL_BAR_HOST_ACTIONS_TAG
            } else {
                VERTICAL_BAR_RAIL_ACTIONS_TAG
            }
        rule.onAllNodesWithTag(expected).assertCountEquals(1)
        rule.onAllNodesWithTag(other).assertCountEquals(0)
        // The reason rides in the failure message: a count assertion that fires says only
        // "expected 1, found 0", which does not say which of the three states was live.
        assertEquals(1, rule.onAllNodesWithTag(expected).fetchSemanticsNodes().size, why)
        assertEquals(0, rule.onAllNodesWithTag(other).fetchSemanticsNodes().size, why)
    }

    @Test
    fun `each new layout points its hints away from the window edge`() {
        // Off-screen tooltips are invisible to every bounds assertion in the suite: the buttons
        // are all present and correctly sized, and their hint opens past the window edge. Both
        // directions carry an explicit justification in their KDoc, so both are worth pinning.
        var railHint: Panel? = null
        var footHint: Panel? = null
        var panelHint: Panel? = null

        // Each list is composables, not values: only rendering them hands the probe its
        // direction, so the capture has to happen inside setContent.
        rule.setContent {
            listOf(
                focusQuickActionsTabRail(
                    FocusQuickActionsPlacement.TAB_BAR_RAIL,
                    {},
                    {},
                    {},
                    toolbox = { hint, _ -> railHint = hint },
                ),
                focusQuickActionsFooter(
                    FocusQuickActionsPlacement.TAB_BAR_FOOTER,
                    {},
                    {},
                    {},
                    toolbox = { hint, _ -> footHint = hint },
                ),
                focusQuickActionsPanelFooter(
                    FocusQuickActionsPlacement.PANEL_FOOTER,
                    {},
                    {},
                    {},
                    toolbox = { hint, _ -> panelHint = hint },
                ),
            ).forEach { actions -> actions.forEach { action -> action() } }
        }
        rule.waitForIdle()

        assertEquals(right, railHint, "the rail is on the start edge, so its hints point inward")
        assertEquals(top, footHint, "the bar's foot is its last row, so its hints point up")
        assertEquals(top, panelHint, "the panel's foot is its last row too")
    }

    @Test
    fun `the footer lands in exactly one panel column, the one the edge names`() {
        // `hostActionsPanelEdge` names a column and BossWindow gates on `panelFooterEdge == panel`
        // in each of the three. Panel is an open class whose subclasses carry a nullable child, so
        // this pins that the roots do not collide - and, since only the right column is a
        // candidate, that a left or bottom panel does NOT quietly grow a foot of its own.
        //
        // One setContent driven by a state, not one per case: `setContent` is documented as once
        // per test, and the drawer test above already has the shape.
        val rightOpen = mutableStateOf(true)
        rule.setContent {
            val edge = hostActionsPanelEdge(rightOpen = rightOpen.value)
            // The three columns BossWindow composes, each gating the shared footer the same way
            // it does. Left and bottom are held OPEN throughout: they are what would light up if
            // the table ever grew back to them by accident.
            Column {
                listOf(right to rightOpen.value, left to true, bottom to true)
                    .filter { (_, isOpen) -> isOpen }
                    .forEach { (column, _) ->
                        PanelColumn(footer = { if (edge == column) FooterProbe(column) }) {
                            Box(modifier = Modifier.fillMaxSize())
                        }
                    }
            }
        }
        rule.waitForIdle()

        assertEquals(right, hostActionsPanelEdge(rightOpen = true), "the premise: the right column")
        assertOnlyFooterIn(right)

        // Shut the right panel with the other two still open: the edge is null, so no column
        // draws a foot and the actions go back to the floating cluster.
        rightOpen.value = false
        rule.waitForIdle()

        assertNull(hostActionsPanelEdge(rightOpen = false), "the premise: no column without it")
        assertOnlyFooterIn(null)
    }

    /** A footer in [expected] and in neither of the other two columns; none at all when null. */
    private fun assertOnlyFooterIn(expected: Panel?) {
        listOf(right, left, bottom).forEach { column ->
            val found = rule.onAllNodesWithTag(probeTagFor(column)).fetchSemanticsNodes().size
            assertEquals(
                if (column == expected) 1 else 0,
                found,
                "footers in the ${column::class.simpleName} column, with $expected expected to hold it",
            )
        }
    }
}

@Composable
private fun FooterProbe(column: Panel) {
    // ONE tag. Two `testTag` calls on one modifier chain do not both stick - they write the same
    // semantics key, so the second wins and an assertion on the first passes by never matching.
    Box(modifier = Modifier.fillMaxSize().testTag(probeTagFor(column)))
}

private fun probeTagFor(column: Panel): String = "host-actions-footer-probe-${column::class.simpleName}"

private val BAR_WIDTH = 200.dp
