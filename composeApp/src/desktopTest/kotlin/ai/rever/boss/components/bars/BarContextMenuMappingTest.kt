package ai.rever.boss.components.bars

import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the [ChromeBar] to settings-field mapping, which three separate surfaces read through:
 * each bar's right-click "Hide", the View menu's checkmarks, and the Settings toggles.
 *
 * The failure this rules out is silent and confusing rather than loud: a [withBarVisible] that
 * wrote the wrong field would hide a *different* bar than the one right-clicked, and an
 * [isBarVisible] that read the wrong one would leave the View menu's checkmark disagreeing with
 * what is actually on screen. Nothing crashes either way, and neither is visible to any test that
 * only looks at one bar - which is why these assert round-trips across all four at once.
 */
class BarContextMenuMappingTest {
    @Test
    fun `every bar starts visible`() {
        // The default that makes these fields safe to add to an existing settings file: the manager
        // decodes with ignoreUnknownKeys, so an absent key must mean "shown" or an upgrade would
        // silently strip a user's chrome.
        val defaults = WindowAppearanceSettings()

        ChromeBar.entries.forEach { bar ->
            assertTrue(defaults.isBarVisible(bar), "${bar.name} should default to visible")
        }
    }

    @Test
    fun `hiding a bar round-trips through the same field it reads`() {
        ChromeBar.entries.forEach { bar ->
            val hidden = WindowAppearanceSettings().withBarVisible(bar, visible = false)

            assertFalse(hidden.isBarVisible(bar), "${bar.name} should read back hidden")
            assertTrue(hidden.withBarVisible(bar, visible = true).isBarVisible(bar))
        }
    }

    @Test
    fun `hiding one bar leaves the other three alone`() {
        // The wrong-field bug: writing showBottomBar from ChromeBar.TOP passes a single-bar test
        // and is caught here.
        ChromeBar.entries.forEach { bar ->
            val hidden = WindowAppearanceSettings().withBarVisible(bar, visible = false)

            ChromeBar.entries.filter { it != bar }.forEach { other ->
                assertTrue(
                    hidden.isBarVisible(other),
                    "hiding ${bar.name} must not disturb ${other.name}",
                )
            }
        }
    }

    @Test
    fun `the title bar is not one of these and is left untouched`() {
        // showTitleBar is the pre-existing flag for a different strip (the 26dp OS-style title bar),
        // and it deliberately has no ChromeBar member - it is not right-clickable and its toggle
        // already lives in Settings. Hiding every bar here must not switch it off as a side effect.
        val allHidden =
            ChromeBar.entries.fold(WindowAppearanceSettings()) { acc, bar ->
                acc.withBarVisible(bar, visible = false)
            }

        assertTrue(allHidden.showTitleBar)
    }

    @Test
    fun `each bar has its own wording`() {
        // These strings are user-visible in three places and are what the View menu and the Hide
        // item are built from, so a duplicate would produce two identically labelled menu entries.
        val names = ChromeBar.entries.map { it.displayName() }

        assertEquals(names.size, names.distinct().size, "display names must be distinct: $names")
        // By codepoint, not by printing the character - the same dodge AGENTS.md uses where it
        // defines this rule, so a future doc-wide guard cannot flag the assertion that enforces it.
        assertTrue(names.none { it.contains('\u2014') }, "no em-dashes in user-visible strings")
    }
}
