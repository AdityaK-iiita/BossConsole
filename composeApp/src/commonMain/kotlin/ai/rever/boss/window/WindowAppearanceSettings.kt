package ai.rever.boss.window

import kotlinx.serialization.Serializable

/**
 * Settings for window appearance customization
 */
@Serializable
data class WindowAppearanceSettings(
    /**
     * Whether to show the Boss Console title bar
     * Default: true on macOS, false on Linux/Windows
     */
    val showTitleBar: Boolean = true,
    /**
     * Whether the action bar at the top of the window is on screen. Its height comes from
     * `ChromeDimens.topBarHeight`, so quoting a dp figure here would go stale.
     *
     * This and the three below are a *permanent* preference, and deliberately separate from focus
     * mode's per-edge `hide*` flags. Focus mode is a transient posture with hover-reveal strips to
     * get a bar back; these say "I never want this bar", and the only way back is the View menu.
     * The scaffold requires both to agree, so a bar shows when this is true and focus mode is not
     * currently clearing it.
     *
     * All four default to `true` on every platform, which is what makes them safe to add to an
     * existing settings file: the manager decodes with `ignoreUnknownKeys`, so an absent key reads
     * back as "shown" and nobody's chrome disappears on upgrade. That is why these need none of the
     * `FocusModeSettings.decodeWithDefaults` machinery, which exists only because *its* defaults
     * differ per platform.
     */
    val showTopBar: Boolean = true,
    /** Whether the status bar at the bottom of the window is on screen. See [showTopBar]. */
    val showBottomBar: Boolean = true,
    /** Whether the left icon strip is on screen. See [showTopBar]. */
    val showLeftStrip: Boolean = true,
    /** Whether the right icon strip is on screen. See [showTopBar]. */
    val showRightStrip: Boolean = true,
    /**
     * How tabs in the main (top) tab bar are sized.
     * Default: SHRINK_TO_FIT (Safari behaviour)
     */
    val tabWidthMode: TabWidthMode = TabWidthMode.SHRINK_TO_FIT,
    /**
     * Whether right-click menus are the operating system's own rather than BOSS-drawn.
     *
     * On macOS this renders a real NSMenu: system appearance and metrics, native keyboard
     * navigation and accessibility, and correct behaviour over the browser's native surface,
     * which a Compose popup is painted behind. Native menus cannot be themed, so turning this
     * off restores the BOSS-styled menus.
     *
     * Currently macOS-only and ignored elsewhere - see `shouldUseNativeMenus` for why Windows
     * and Linux stay on the drawn menus.
     */
    val useNativeContextMenus: Boolean = true,
)

/**
 * Sizing behaviour for top tabs in the main tab bar.
 */
@Serializable
enum class TabWidthMode {
    /**
     * Tabs shrink uniformly to fit the available bar width (Safari behaviour).
     * The row only scrolls once each tab has hit its favicon-sized floor.
     */
    SHRINK_TO_FIT,

    /**
     * Tabs take their content-driven width (clamped to 180–450 dp) and the
     * row scrolls as soon as they overflow the bar.
     */
    FIXED,
}
