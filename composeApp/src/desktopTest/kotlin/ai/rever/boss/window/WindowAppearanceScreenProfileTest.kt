package ai.rever.boss.window

import ai.rever.boss.layout.ChromeDensity
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowAppearanceScreenProfileTest {

    @Test
    fun `short screen gets compact density and hides bottom bar`() {
        val profile = defaultChromeScreenProfile(
            screenWidthDp = 1470,
            screenHeightDp = 899,
        )

        assertEquals(ChromeDensity.COMPACT, profile.density)
        assertEquals(false, profile.showBottomBar)
        assertEquals(true, profile.showLeftStrip)
        assertEquals(true, profile.showRightStrip)
    }

    @Test
    fun `900dp height stays comfortable`() {
        val profile = defaultChromeScreenProfile(
            screenWidthDp = 1470,
            screenHeightDp = 900,
        )

        assertEquals(ChromeDensity.COMFORTABLE, profile.density)
        assertEquals(true, profile.showBottomBar)
    }

    @Test
    fun `narrow screen hides both strips`() {
        val profile = defaultChromeScreenProfile(
            screenWidthDp = 1199,
            screenHeightDp = 1000,
        )

        assertEquals(false, profile.showLeftStrip)
        assertEquals(false, profile.showRightStrip)
        assertEquals(ChromeDensity.COMFORTABLE, profile.density)
        assertEquals(true, profile.showBottomBar)
    }

    @Test
    fun `1200dp width keeps both strips`() {
        val profile = defaultChromeScreenProfile(
            screenWidthDp = 1200,
            screenHeightDp = 1000,
        )

        assertEquals(true, profile.showLeftStrip)
        assertEquals(true, profile.showRightStrip)
    }

    @Test
    fun `large screen keeps all comfortable defaults`() {
        val profile = defaultChromeScreenProfile(
            screenWidthDp = 1920,
            screenHeightDp = 1080,
        )

        assertEquals(ChromeDensity.COMFORTABLE, profile.density)
        assertEquals(true, profile.showBottomBar)
        assertEquals(true, profile.showLeftStrip)
        assertEquals(true, profile.showRightStrip)
    }

    @Test
    fun `unknown dimensions keep comfortable defaults`() {
        val profile = defaultChromeScreenProfile(
            screenWidthDp = null,
            screenHeightDp = null,
        )

        assertEquals(ChromeDensity.COMFORTABLE, profile.density)
        assertEquals(true, profile.showBottomBar)
        assertEquals(true, profile.showLeftStrip)
        assertEquals(true, profile.showRightStrip)
    }
}
