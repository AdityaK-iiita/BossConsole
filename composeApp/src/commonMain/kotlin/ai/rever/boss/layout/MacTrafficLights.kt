package ai.rever.boss.layout

import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height a macOS window must keep clear at its top-LEFT for the traffic lights.
 *
 * The window sets `apple.awt.fullWindowContent`, so the close / minimise / zoom buttons are drawn
 * over the content rather than in a strip above it. 28dp covers the buttons and the few points of
 * air macOS leaves around them.
 */
val TRAFFIC_LIGHT_HEIGHT: Dp = 28.dp

/**
 * Width of the same box. Not read by the layout - the column being inset is narrower than this
 * anyway - but stated because it is why a full-width row was the wrong shape: the lights occupy
 * one corner, not a band.
 */
val TRAFFIC_LIGHT_WIDTH: Dp = 78.dp

/**
 * Which piece of chrome has to keep clear of the traffic lights, if any.
 *
 * The lights occupy a BOX - about 78dp wide and 28dp tall in the window's top-left corner - not a
 * band across the top. The old answer was a 26dp full-width row (`BossTitleBar`, whose only
 * content is a centred title string), which reserves the whole width to protect one corner.
 *
 * So the clearance goes on whatever is actually under that box, and which chrome that is depends
 * on what is switched on. Two cases were missed on the first attempt and both were visible:
 *
 * - The **top bar** spans the full width at y=0, so when it is on it is what the lights land on -
 *   no column is. Its leftmost control sat under the green button.
 * - The lights are **wider than one column**. An icon strip is 40dp, so a 78dp box covers the
 *   whole strip and another 38dp of whatever is beside it - which is the vertical tab bar. Insetting
 *   only the strip left the second half of the box over the bar's Favorites header.
 */
enum class TrafficLightInset {
    /** No inset: not macOS, or the title row is on and already holds them. */
    NONE,

    /**
     * The top bar is under them. It needs a horizontal indent of [TRAFFIC_LIGHT_WIDTH], not a
     * vertical one - the bar is a row, and the lights sit at its start.
     */
    TOP_BAR,

    /**
     * The columns down the left edge are under them: the icon strip, the vertical tab bar, or
     * both. Every one of them that falls inside [TRAFFIC_LIGHT_WIDTH] takes a top inset, which in
     * practice is both when both are on, since a strip alone is narrower than the box.
     */
    LEFT_COLUMNS,

    /**
     * Nothing is up there but content, so there is no chrome to inset.
     *
     * The caller keeps the full-width title row for this case. Padding the content instead would
     * cost the same height across the whole width - no saving - and the content is where a
     * browser's native surface lives, which is not something to leave a hole in.
     */
    CONTENT,

    /**
     * The update banner is up, and it is drawn above every bar and column, so it is what the
     * lights land on.
     *
     * This replaces [TOP_BAR] and [LEFT_COLUMNS] for as long as the banner exists, and replacing
     * is the whole point: the clearance belongs to exactly ONE piece of chrome. Leaving the
     * columns their inset while the banner took its own opened an empty 28dp band under the
     * banner, above the tab bar's Favorites shelf - clearance for lights that were no longer
     * there.
     *
     * It does NOT replace [CONTENT] or [NONE]: both of those draw the title row, and the row is
     * ABOVE the banner, so the row still holds the lights and the banner needs nothing. Mapping
     * them here would drop a row the layout keeps permanently, shifting the whole window by its
     * height every time a banner appeared.
     */
    BANNER,
}

/**
 * Where the traffic-light clearance belongs for these settings.
 *
 * Pure, so the cases are a table rather than a conditional buried in the scaffold. Each wrong
 * answer is a visible defect - the buttons over a tab bar's Favorites header, over the top bar's
 * first control, or a 28dp gap above a column that needed none - and all of them are only visible
 * on macOS, which is not where most of this is developed.
 */
fun macTrafficLightInset(
    appearance: WindowAppearanceSettings,
    isMacOs: Boolean,
    /**
     * Whether the vertical bar is down to its slim rail, which changes what it can cover.
     *
     * A rail is one [ChromeDimens.stripWidth], so on its own it is narrower than the light box and
     * cannot protect the corner - the lights spill onto whatever is beside it, which is a browser
     * pane and cannot be inset at all. That case falls back to the title row.
     *
     * Take it from the MEASURED rail state (`SplitViewPanel.onBarRailedChange`), not from the
     * `tabBarCollapsed` preference: a bar also rails itself when the window is too narrow for a
     * full one, and a window that asked the preference read an auto-railed bar as a full column
     * and let the lights land on the pane beside it.
     */
    barCollapsed: Boolean = false,
    /**
     * Whether the update banner is currently drawing. See [TrafficLightInset.BANNER].
     *
     * Take it from [ai.rever.boss.updater.drawsBanner] rather than from "an update exists": most
     * of `UpdateState` draws no banner at all, and insetting for a banner that is not there is the
     * same empty band by another route.
     */
    bannerVisible: Boolean = false,
    /** One icon rail's width at the current density. See [leftChromeWidth]. */
    stripWidth: Dp = ChromeDimens.MIN_STRIP_WIDTH,
): TrafficLightInset {
    val base =
        when {
            // Not macOS: the lights are somebody else's problem, and on Windows and Linux the title
            // row is a normal bar above the content rather than an overlay on top of it.
            !isMacOs -> {
                TrafficLightInset.NONE
            }

            // The row is on and is exactly what it is for.
            appearance.showTitleBar -> {
                TrafficLightInset.NONE
            }

            // Asked BEFORE the columns: the top bar is above them, so when it is on, it is what is
            // under the lights and the columns start below the box entirely.
            appearance.showTopBar -> {
                TrafficLightInset.TOP_BAR
            }

            // Only when the columns are actually wide enough to hold the box. Insetting chrome that
            // is narrower than the lights protects part of the corner and leaves the rest over the
            // content, which is worse than not trying: it looks deliberate.
            leftChromeWidth(appearance, barCollapsed, stripWidth) >= TRAFFIC_LIGHT_WIDTH -> {
                TrafficLightInset.LEFT_COLUMNS
            }

            else -> {
                TrafficLightInset.CONTENT
            }
        }

    // The banner takes over from whatever chrome it is drawn above, and only from that chrome.
    val coverable = base == TrafficLightInset.TOP_BAR || base == TrafficLightInset.LEFT_COLUMNS
    return if (bannerVisible && coverable) TrafficLightInset.BANNER else base
}

/** The top inset for a left-hand column, which is the height of the box or nothing. */
fun TrafficLightInset.columnInset(): Dp = if (this == TrafficLightInset.LEFT_COLUMNS) TRAFFIC_LIGHT_HEIGHT else 0.dp

/**
 * The start indent for the update banner.
 *
 * Non-zero for exactly [TrafficLightInset.BANNER], which is the answer only when the banner is
 * both up and topmost. The banner is a row, so it takes a horizontal indent; the height it has to
 * keep is enforced where it is drawn.
 */
fun TrafficLightInset.bannerStartInset(): Dp = if (this == TrafficLightInset.BANNER) TRAFFIC_LIGHT_WIDTH else 0.dp

/** The start indent for the top bar, which is the width of the box or nothing. */
fun TrafficLightInset.barStartInset(): Dp = if (this == TrafficLightInset.TOP_BAR) TRAFFIC_LIGHT_WIDTH else 0.dp

/**
 * Whether the full-width title row still has to be drawn.
 *
 * True when the user asked for it, and true for [TrafficLightInset.CONTENT] - there is no chrome
 * to inset, so the row is what keeps the lights off the content.
 *
 * **The CONTENT answer is reached dynamically, and the row therefore appears and disappears.** Two
 * ways in, both of them a user gesture rather than a setting:
 *
 * - **Narrowing the window.** The default configuration is [TrafficLightInset.LEFT_COLUMNS]; drag
 *   past `TAB_BAR_AUTO_COLLAPSE_WIDTH` and the bar rails itself, the left chrome drops under
 *   [TRAFFIC_LIGHT_WIDTH], and a 26dp row appears at the top - moving the whole window's content
 *   down mid-drag. Widen again and it goes.
 * - **Focus mode**, when the top bar was what held them: clearing it leaves nothing up there, so
 *   focus mode ADDS a row, which is the opposite of what it is for.
 *
 * Both are the honest answer to "where do the lights go" and neither is what anyone wants to see.
 * Kept, rather than papered over, because the alternatives are worse: leaving the lights over a
 * browser pane makes them unreadable and unclickable-looking, and insetting a column narrower than
 * the box protects half the corner and looks deliberate. Closing it properly means a transparent
 * clearance region over the content rather than a row above it, which is a change to how the
 * window composes its top edge and does not belong to the change that found it.
 */
fun TrafficLightInset.needsTitleRow(showTitleBar: Boolean): Boolean = showTitleBar || this == TrafficLightInset.CONTENT

/**
 * How much chrome runs down the window's left edge, which is what decides whether the corner can
 * be protected by insetting columns at all.
 *
 * The icon strip is one [stripWidth]; the vertical tab bar is its configured width, or the same
 * rail width when collapsed. A bar in TOP position contributes nothing.
 *
 * [stripWidth] is passed in rather than read here, because it is the DENSITY's value and this is
 * pure: the shipped Comfortable preset is 40dp, not the 36dp floor. Measuring with the floor gave
 * a strip plus a rail as 72dp and fell back to the title row, where what is actually drawn is
 * 80dp and could have been inset. The default is the floor, which is the conservative direction:
 * it can only over-reserve, never leave the lights over a column too narrow to hold them.
 */
internal fun leftChromeWidth(
    appearance: WindowAppearanceSettings,
    barCollapsed: Boolean,
    stripWidth: Dp = ChromeDimens.MIN_STRIP_WIDTH,
): Dp {
    val strip = if (appearance.showLeftStrip) stripWidth else 0.dp
    val bar =
        when {
            appearance.tabBarPosition != TabBarPosition.LEFT -> 0.dp
            barCollapsed -> stripWidth
            else -> appearance.tabBarVerticalWidth.dp
        }
    return strip + bar
}
