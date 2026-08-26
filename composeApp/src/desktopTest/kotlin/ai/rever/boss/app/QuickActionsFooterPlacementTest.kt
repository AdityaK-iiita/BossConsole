package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the precedence between the three renderings of Settings / Search / Sign Out.
 *
 * The order is rail, then the tab bar's footer, then the floating cluster, and each step is a
 * choice rather than an accident. The floating cluster is a native always-on-top window with no
 * click-through, so it is the most intrusive of the three and goes last; the rail was already
 * preferred over it and stays preferred; the tab bar's foot is chrome the app draws anyway.
 *
 * Getting this backwards does not crash - it puts a dead click region over the content area in a
 * window that had a perfectly good place to put four icons.
 */
class QuickActionsFooterPlacementTest {
    private val focusOff = FocusModeSettings()

    private fun placement(
        rightStripHidden: Boolean,
        verticalTabBar: Boolean,
    ) = focusQuickActionsPlacement(
        settings = focusOff,
        topBarHidden = true,
        rightStripHidden = rightStripHidden,
        showTopBar = false,
        verticalTabBar = verticalTabBar,
    )

    @Test
    fun `the rail still wins when there is a rail`() {
        assertEquals(
            FocusQuickActionsPlacement.RIGHT_RAIL,
            placement(rightStripHidden = false, verticalTabBar = true),
            "the vertical tab bar displaces the floating cluster, not the rail",
        )
    }

    @Test
    fun `the tab bar's foot takes the floating cluster's place`() {
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            placement(rightStripHidden = true, verticalTabBar = true),
        )
    }

    @Test
    fun `a collapsed bar floats them, because its foot does not exist`() {
        // The caller passes verticalTabBar = false for a collapsed bar. Pinned here as the
        // contract rather than left to the scaffold, because the failure is silent: the four
        // actions simply render nowhere.
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            placement(rightStripHidden = true, verticalTabBar = false),
        )
    }

    @Test
    fun `a bar that is not on the left has no foot either`() {
        // Was a character-for-character copy of the test above, so the case its name described -
        // no vertical bar AT ALL, as against a collapsed one - went untested while looking covered.
        //
        // Asked through verticalBarHasFoot rather than by passing `false` again, because that is
        // the composition the scaffold performs and the only way the two cases differ.
        val tabsOnTop = verticalBarHasFoot(tabBarOnLeft = false, barCollapsed = false, drawerVisible = false)
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            placement(rightStripHidden = true, verticalTabBar = tabsOnTop),
        )

        // And the collapsed-bar case reaches the same answer by the other route.
        val collapsedOnLeft = verticalBarHasFoot(tabBarOnLeft = true, barCollapsed = true, drawerVisible = false)
        assertEquals(
            FocusQuickActionsPlacement.FLOATING,
            placement(rightStripHidden = true, verticalTabBar = collapsedOnLeft),
        )

        // The drawer is the third state: a collapsed bar with it open HAS a foot again.
        val drawerOpen = verticalBarHasFoot(tabBarOnLeft = true, barCollapsed = true, drawerVisible = true)
        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            placement(rightStripHidden = true, verticalTabBar = drawerOpen),
        )
    }

    @Test
    fun `the top bar being up still beats all three`() {
        assertEquals(
            FocusQuickActionsPlacement.NONE,
            focusQuickActionsPlacement(
                settings = focusOff,
                topBarHidden = false,
                rightStripHidden = true,
                showTopBar = true,
                verticalTabBar = true,
            ),
            "the top bar owns these three whenever it is on screen",
        )
    }

    @Test
    fun `the footer list is empty for every other placement`() {
        // What lets the bar call it unconditionally and render nothing.
        FocusQuickActionsPlacement.entries
            .filter { it != FocusQuickActionsPlacement.TAB_BAR_FOOTER }
            .forEach { placement ->
                assertTrue(
                    focusQuickActionsFooter(placement, {}, {}, {}, { _, _ -> }).isEmpty(),
                    "$placement should contribute no footer actions",
                )
            }
        assertEquals(
            FOCUS_QUICK_ACTION_COUNT,
            focusQuickActionsFooter(FocusQuickActionsPlacement.TAB_BAR_FOOTER, {}, {}, {}, { _, _ -> }).size,
        )
    }

    @Test
    fun `the launcher adds a fourth action without disturbing the reserve`() {
        // The rail's reserve is FOCUS_QUICK_ACTION_COUNT rows, and it stays at three because the
        // launcher can never join the rail flavour - see ToolLauncherPlacementTest.
        val withLauncher =
            focusQuickActionsFooter(
                FocusQuickActionsPlacement.TAB_BAR_FOOTER,
                {},
                {},
                {},
                toolbox = { _, _ -> },
                toolLauncher = { _, _ -> },
            )

        assertEquals(FOCUS_QUICK_ACTION_COUNT + 1, withLauncher.size)
        val rail = focusQuickActionsRail(FocusQuickActionsPlacement.RIGHT_RAIL, {}, {}, {}, { _, _ -> })
        assertEquals(FOCUS_QUICK_ACTION_COUNT, rail.size)
    }

    @Test
    fun `focus mode clearing the right edge also reaches the footer`() {
        // hides(RIGHT) is the other way there is no rail. It should land on the tab bar's foot
        // rather than the floating cluster, exactly as a switched-off strip does.
        val hidesRight = FocusModeSettings(enabled = true, hideRightSidebar = true)

        assertEquals(
            FocusQuickActionsPlacement.TAB_BAR_FOOTER,
            focusQuickActionsPlacement(
                settings = hidesRight,
                topBarHidden = true,
                rightStripHidden = false,
                showTopBar = false,
                verticalTabBar = true,
            ),
        )
    }
}

/**
 * Pins the three states of "does the vertical bar have a foot to put the host's actions in".
 *
 * Two of them look the same from the settings alone - a collapsed bar with the drawer open and one
 * with it shut differ only in a transient flag - and getting it wrong is silent either way: the
 * actions render twice, or nowhere.
 */
class VerticalBarFootTest {
    @Test
    fun `an expanded left bar has a foot`() {
        assertTrue(verticalBarHasFoot(tabBarOnLeft = true, barCollapsed = false, drawerVisible = false))
    }

    @Test
    fun `a collapsed bar has none, so they float`() {
        // It draws its rail and nothing else - there is no foot to render into.
        assertFalse(verticalBarHasFoot(tabBarOnLeft = true, barCollapsed = true, drawerVisible = false))
    }

    @Test
    fun `a collapsed bar with the drawer open has one again`() {
        // The drawer is a full bar, split map and all, for as long as it is up.
        assertTrue(verticalBarHasFoot(tabBarOnLeft = true, barCollapsed = true, drawerVisible = true))
    }

    @Test
    fun `a top tab bar never has one, drawer or not`() {
        // There is no vertical bar at all in TOP position, so no drawer can give it a foot.
        assertFalse(verticalBarHasFoot(tabBarOnLeft = false, barCollapsed = false, drawerVisible = true))
    }
}
