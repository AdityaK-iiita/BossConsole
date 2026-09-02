package ai.rever.boss.plugin.browser

import ai.rever.boss.config.parseSwipeNavEnabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host half of the two-finger swipe gesture.
 *
 * The gesture itself is JavaScript and is covered by running it - `scripts/test/test-swipe-nav.js`,
 * which the build workflow executes. What is left here is everything the page cannot decide: which
 * platforms get the feature, what the bridge accepts from a page that is free to call it with
 * anything, and how close together two swipes may navigate.
 */
class BrowserSwipeNavTest {
    // --- Where the feature is on -------------------------------------------------------------

    @Test
    fun `on by default on macOS`() {
        assertTrue(swipeNavEnabled(isMac = true, enabled = true))
    }

    @Test
    fun `off everywhere else`() {
        assertFalse(swipeNavEnabled(isMac = false, enabled = true))
    }

    @Test
    fun `the setting turns it off`() {
        assertFalse(swipeNavEnabled(isMac = true, enabled = false))
    }

    /**
     * The key was documented as an env-var on/off switch before it had a Settings row, so someone
     * has one of these exported. Dropping a spelling would silently change what their shell says.
     */
    @Test
    fun `every documented spelling still parses`() {
        for (value in listOf("false", "0", "no", "off", "FALSE", " off ")) {
            assertEquals(false, parseSwipeNavEnabled(value), value)
        }
        for (value in listOf("true", "1", "yes", "on", "TRUE")) {
            assertEquals(true, parseSwipeNavEnabled(value), value)
        }
    }

    /**
     * A typo must not silently remove a gesture whose only route back is finding the same typo, so
     * an unparseable value is "no opinion" and the caller keeps its default - never off.
     */
    @Test
    fun `an unparseable value is no opinion, not off`() {
        assertNull(parseSwipeNavEnabled("maybe"))
        assertNull(parseSwipeNavEnabled(""))
        assertNull(parseSwipeNavEnabled(null))
    }

    // --- What the bridge accepts from a page -------------------------------------------------

    @Test
    fun `the two directions the script sends`() {
        assertEquals(SwipeNavDirection.BACK, parseSwipeNavDirection("back"))
        assertEquals(SwipeNavDirection.FORWARD, parseSwipeNavDirection("forward"))
    }

    @Test
    fun `anything else is dropped rather than guessed at`() {
        for (value in listOf(null, "", "BACK", " back", "backward", "-1", "back;forward")) {
            assertNull(parseSwipeNavDirection(value), value.toString())
        }
    }

    @Test
    fun `a page calling the bridge directly still reaches the handler`() {
        // Reachability is by design - the page can already call history.back() - so what is
        // pinned here is that the reachable surface is exactly one verb with two answers.
        val seen = mutableListOf<SwipeNavDirection>()
        val bridge = BrowserSwipeNavBridge(onNavigate = { seen += it })
        bridge.navigate("back")
        bridge.navigate("sideways")
        bridge.navigate(null)
        bridge.navigate("forward")
        assertEquals(listOf(SwipeNavDirection.BACK, SwipeNavDirection.FORWARD), seen)
    }

    @Test
    fun `a throwing handler never escapes into the page`() {
        val bridge = BrowserSwipeNavBridge(onNavigate = { error("handler blew up") })
        bridge.navigate("back")
    }

    // --- How often a swipe may navigate ------------------------------------------------------

    @Test
    fun `the first swipe of the session navigates`() {
        assertTrue(shouldAcceptSwipeNav(nowMs = 1_000, lastNavigationMs = 0))
    }

    @Test
    fun `a second swipe inside the window is refused`() {
        assertFalse(shouldAcceptSwipeNav(nowMs = 1_000 + SWIPE_NAV_DEBOUNCE_MS, lastNavigationMs = 1_000))
        assertFalse(shouldAcceptSwipeNav(nowMs = 1_000 + SWIPE_NAV_DEBOUNCE_MS / 2, lastNavigationMs = 1_000))
    }

    @Test
    fun `and accepted once the window has passed`() {
        assertTrue(shouldAcceptSwipeNav(nowMs = 1_001 + SWIPE_NAV_DEBOUNCE_MS, lastNavigationMs = 1_000))
    }

    /**
     * The window's job (see [shouldAcceptSwipeNav]'s KDoc for why) is catching a genuine
     * double-fire bug and not a real second gesture, so it is bounded at both ends: below
     * `swipe-nav.js`'s own `GESTURE_GAP_MS`, the minimum possible gap between two gesture ends,
     * and at or above one frame, which is the shape a bridge-level double-dispatch takes.
     *
     * The upper bound is read OUT of the script rather than restated here. The whole argument is
     * a cross-language coupling to a constant in another file, and hard-coded, lowering
     * `GESTURE_GAP_MS` would leave this green while quietly falsifying its own reasoning.
     */
    @Test
    fun `the window sits between a double-dispatch and the floor between two real gestures`() {
        val match = GESTURE_GAP_MS_IN_SCRIPT.find(BrowserSwipeNavScript.source)
        assertNotNull(match, "GESTURE_GAP_MS not found in swipe-nav.js")
        val gapMs = match.groupValues[1].toLong()
        assertTrue(
            SWIPE_NAV_DEBOUNCE_MS < gapMs,
            "$SWIPE_NAV_DEBOUNCE_MS must stay under the script's ${gapMs}ms gesture gap, or it can " +
                "reject a genuinely separate swipe",
        )
        assertTrue(
            SWIPE_NAV_DEBOUNCE_MS >= ONE_FRAME_MS,
            "$SWIPE_NAV_DEBOUNCE_MS must still cover a same-frame double-dispatch",
        )
    }

    // --- What the host pushes into the page --------------------------------------------------

    @Test
    fun `state is pushed as booleans on the property the script reads`() {
        assertEquals(
            "window.${BrowserSwipeNavScript.STATE_PROPERTY} = " +
                "{ enabled: true, back: true, forward: false };",
            BrowserSwipeNavScript.stateUpdate(enabled = true, canGoBack = true, canGoForward = false),
        )
    }

    /**
     * The resource has to be on the classpath, not just on disk. A missing one logs and returns
     * empty, which would leave every page silently gestureless - the exact failure this whole
     * feature is being built to remove.
     */
    @Test
    fun `the gesture script is packaged`() {
        val source = BrowserSwipeNavScript.source
        assertTrue(source.isNotEmpty(), "swipe-nav.js missing from the classpath")
        assertTrue(
            source.contains(BrowserSwipeNavScript.BRIDGE_PROPERTY),
            "the packaged script does not name ${BrowserSwipeNavScript.BRIDGE_PROPERTY}",
        )
        assertTrue(
            source.contains(BrowserSwipeNavScript.STATE_PROPERTY),
            "the packaged script does not name ${BrowserSwipeNavScript.STATE_PROPERTY}",
        )
    }

    private companion object {
        val GESTURE_GAP_MS_IN_SCRIPT = Regex("""var GESTURE_GAP_MS = (\d+)""")
        const val ONE_FRAME_MS = 16L
    }
}
