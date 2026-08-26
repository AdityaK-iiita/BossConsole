package ai.rever.boss.layout

import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins which column keeps clear of the macOS traffic lights.
 *
 * The window sets `apple.awt.fullWindowContent`, so the buttons are drawn over the content. The
 * title row used to hold them, at the cost of reserving the window's whole width to protect one
 * 78dp corner; now the clearance goes on whichever column is leftmost.
 *
 * Every case here is a visible defect if it answers wrongly - the buttons landing on a tab bar's
 * Favorites shelf, or a 28dp gap opening above a column that needed none - and all of them are
 * only visible on macOS, which is not where most of this is developed.
 */
class MacTrafficLightsTest {
    private val bare =
        WindowAppearanceSettings(
            showTitleBar = false,
            showTopBar = false,
            showLeftStrip = false,
            tabBarPosition = TabBarPosition.LEFT,
        )

    @Test
    fun `no inset off macOS`() {
        // Elsewhere the title row is an ordinary bar above the content, not an overlay on it.
        assertEquals(TrafficLightInset.NONE, macTrafficLightInset(bare, isMacOs = false))
    }

    @Test
    fun `no inset while the title row is on`() {
        // The row is exactly what holds them.
        assertEquals(
            TrafficLightInset.NONE,
            macTrafficLightInset(bare.copy(showTitleBar = true), isMacOs = true),
        )
    }

    @Test
    fun `the top bar takes it whenever it is on`() {
        // It spans the full width at y=0, so it is above every column and it is what the lights
        // are drawn over. Missing this put the green button on top of the bar's first control.
        val withTopBar = bare.copy(showTopBar = true)

        assertEquals(TrafficLightInset.TOP_BAR, macTrafficLightInset(withTopBar, isMacOs = true))
    }

    @Test
    fun `the top bar takes it even with both columns on`() {
        // The columns start below the bar, so the box cannot reach them.
        val everything = bare.copy(showTopBar = true, showLeftStrip = true)

        assertEquals(TrafficLightInset.TOP_BAR, macTrafficLightInset(everything, isMacOs = true))
    }

    @Test
    fun `the columns take it when the top bar is off`() {
        // `bare` already puts the tab bar on the LEFT, so these two differ by the strip only.
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(bare, isMacOs = true),
            "the vertical tab bar alone",
        )
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(bare.copy(showLeftStrip = true), isMacOs = true),
            "both, and both are inset, because one strip is narrower than the 78dp box",
        )
    }

    @Test
    fun `a strip with no bar beside it cannot hold the box`() {
        // The case the two assertions above were once BOTH written as, which made it look covered
        // while the answer went untested: a strip alone is one rail wide, and a rail is nowhere
        // near 78dp - so the corner cannot be protected by insetting it and the title row is what
        // holds the lights. Tabs across the top is the only way to have a strip and no bar.
        val stripOnly = bare.copy(showLeftStrip = true, tabBarPosition = TabBarPosition.TOP)
        assertEquals(TrafficLightInset.CONTENT, macTrafficLightInset(stripOnly, isMacOs = true))

        // True at the widest strip any preset draws, not just at the floor.
        assertEquals(
            TrafficLightInset.CONTENT,
            macTrafficLightInset(stripOnly, isMacOs = true, stripWidth = 44.dp),
            "even a Spacious 44dp strip is under 78dp",
        )
    }

    @Test
    fun `a collapsed bar is too narrow to protect the corner`() {
        // A rail is one strip width. On its own that is less than the light box, so the lights
        // spill onto whatever is beside it - a browser pane, which cannot be inset at all. The
        // title row comes back for that case rather than half-covering the corner.
        val collapsed = macTrafficLightInset(bare, isMacOs = true, barCollapsed = true)

        assertEquals(TrafficLightInset.CONTENT, collapsed)
    }

    @Test
    fun `a collapsed bar beside a strip is still too narrow`() {
        // Two rails come to 72dp against a 78dp box - six short, so the corner still cannot be
        // protected by insetting them and the title row takes it. Worth pinning as a NUMBER
        // rather than a feeling: it is the one case where the answer is not obvious by eye, and
        // if either width moves this flips.
        val withStrip = bare.copy(showLeftStrip = true)

        assertTrue(
            leftChromeWidth(withStrip, barCollapsed = true) < TRAFFIC_LIGHT_WIDTH,
            "two rails should not reach the light box",
        )
        assertEquals(
            TrafficLightInset.CONTENT,
            macTrafficLightInset(withStrip, isMacOs = true, barCollapsed = true),
        )
    }

    @Test
    fun `the width rule reads the collapsed bar as a rail`() {
        assertEquals(
            ChromeDimens.MIN_STRIP_WIDTH,
            leftChromeWidth(bare, barCollapsed = true),
            "a collapsed bar contributes a rail, not its configured width",
        )
        assertEquals(
            bare.tabBarVerticalWidth.dp,
            leftChromeWidth(bare, barCollapsed = false),
        )
    }

    @Test
    fun `the width rule measures with the density's strip, not the floor`() {
        // The floor is 36dp; Comfortable, which ships, draws 40dp and Spacious 44dp. Measuring
        // with the floor made a strip plus a collapsed rail come to 72dp and fall back to the
        // title row, where what is actually drawn is 80dp and fits the 78dp box with room over.
        val stripAndRail = bare.copy(showLeftStrip = true)
        assertEquals(
            72.dp,
            leftChromeWidth(stripAndRail, barCollapsed = true),
            "the floor, which is what the default measures",
        )
        assertEquals(
            80.dp,
            leftChromeWidth(stripAndRail, barCollapsed = true, stripWidth = 40.dp),
            "Comfortable, which is what ships",
        )

        // And the answer flips with it, which is the point of passing it in.
        assertEquals(
            TrafficLightInset.CONTENT,
            macTrafficLightInset(stripAndRail, isMacOs = true, barCollapsed = true),
        )
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(stripAndRail, isMacOs = true, barCollapsed = true, stripWidth = 40.dp),
        )
    }

    @Test
    fun `the box is wider than one column, which is why both are inset`() {
        // The reason LEFT_COLUMNS is one answer rather than "whichever column is leftmost". A
        // strip is 40dp and the lights are 78dp, so insetting only the strip leaves the second
        // half of the box over the tab bar beside it.
        assertTrue(
            TRAFFIC_LIGHT_WIDTH > 40.dp,
            "if a strip ever gets wider than the light box, this rule can be narrowed again",
        )
    }

    @Test
    fun `with no bar and nothing down the left the content is under them`() {
        // No top bar, no strip, tabs across the top: nothing to inset, so the caller keeps the
        // full-width row. Padding the content would cost the same height across the same width.
        val topTabs = bare.copy(tabBarPosition = TabBarPosition.TOP)

        assertEquals(TrafficLightInset.CONTENT, macTrafficLightInset(topTabs, isMacOs = true))
    }

    @Test
    fun `the banner takes the clearance off the top bar while it is up`() {
        // The banner is drawn above the bar, so the lights land on the banner. A bar that kept its
        // own indent would be reserving space for buttons that moved.
        val withBar = bare.copy(showTopBar = true)
        assertEquals(TrafficLightInset.TOP_BAR, macTrafficLightInset(withBar, isMacOs = true))
        assertEquals(
            TrafficLightInset.BANNER,
            macTrafficLightInset(withBar, isMacOs = true, bannerVisible = true),
        )
    }

    @Test
    fun `the banner takes the clearance off the columns while it is up`() {
        // This is the reported defect: the columns kept a 28dp top inset under a banner that had
        // already taken the lights, so an empty band opened above the tab bar's Favorites shelf.
        val defaults = WindowAppearanceSettings()
        assertEquals(TrafficLightInset.LEFT_COLUMNS, macTrafficLightInset(defaults, isMacOs = true))

        val underBanner = macTrafficLightInset(defaults, isMacOs = true, bannerVisible = true)
        assertEquals(TrafficLightInset.BANNER, underBanner)
        assertEquals(0.dp, underBanner.columnInset())
        assertEquals(TRAFFIC_LIGHT_WIDTH, underBanner.bannerStartInset())
    }

    @Test
    fun `a banner changes nothing where the title row holds the lights`() {
        // The row is drawn ABOVE the banner, so it goes on holding them and the banner needs no
        // indent. Mapping these to BANNER would also drop the row, moving the whole window.
        val titled = bare.copy(showTitleBar = true)
        assertEquals(
            TrafficLightInset.NONE,
            macTrafficLightInset(titled, isMacOs = true, bannerVisible = true),
        )

        // CONTENT keeps the row for the same reason, so a banner leaves it alone too.
        val topTabs = bare.copy(tabBarPosition = TabBarPosition.TOP)
        val content = macTrafficLightInset(topTabs, isMacOs = true, bannerVisible = true)
        assertEquals(TrafficLightInset.CONTENT, content)
        assertEquals(0.dp, content.bannerStartInset())
        assertTrue(content.needsTitleRow(showTitleBar = false))
    }

    @Test
    fun `no banner inset off macOS`() {
        // There are no lights to clear, so a banner is just a banner.
        assertEquals(
            TrafficLightInset.NONE,
            macTrafficLightInset(bare, isMacOs = false, bannerVisible = true),
        )
    }

    @Test
    fun `each answer produces exactly one kind of inset`() {
        // The two insets are different axes - a column takes height, the bar takes width - so a
        // case that produced both, or neither where one is needed, is a layout bug either way.
        assertEquals(TRAFFIC_LIGHT_HEIGHT, TrafficLightInset.LEFT_COLUMNS.columnInset())
        assertEquals(0.dp, TrafficLightInset.LEFT_COLUMNS.barStartInset())
        assertEquals(TRAFFIC_LIGHT_WIDTH, TrafficLightInset.TOP_BAR.barStartInset())
        assertEquals(0.dp, TrafficLightInset.TOP_BAR.columnInset())
        assertEquals(0.dp, TrafficLightInset.NONE.columnInset())
        assertEquals(0.dp, TrafficLightInset.NONE.barStartInset())

        // The banner takes a start indent and nothing else: this is the whole fix, since the band
        // under the banner was the column inset surviving alongside it.
        assertEquals(TRAFFIC_LIGHT_WIDTH, TrafficLightInset.BANNER.bannerStartInset())
        assertEquals(0.dp, TrafficLightInset.BANNER.columnInset())
        assertEquals(0.dp, TrafficLightInset.BANNER.barStartInset())

        // And no other answer indents the banner, so the indent can never be applied twice.
        val indenting = TrafficLightInset.entries.filter { it.bannerStartInset() > 0.dp }
        assertEquals(listOf(TrafficLightInset.BANNER), indenting)
    }

    @Test
    fun `the shipped defaults inset the left columns`() {
        // The default configuration on every platform: no title row, no top bar, no strips, tabs
        // down the left. This is the case the whole change is for.
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(WindowAppearanceSettings(), isMacOs = true),
        )
    }
}
