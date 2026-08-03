package ai.rever.boss.crash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Layout guarantees for [CrashReportDialog] at the real crash window's sizes.
 *
 * The dialog is hosted in a JFrame (`CrashHandler.showCrashDialogWindow`) whose *content pane* is
 * 550x700 preferred and 450x500 minimum. Those constants describe the box the dialog is laid out
 * in, not the decorated frame around it, so the sizes below are what the dialog really gets.
 *
 * A crash report is a lot of content for that box, and expanding "Technical
 * Details" adds ~250dp more — so a body laid out without a scroll region pushes "Report Issue" and
 * "Don't Send" below the bottom edge, where they cannot be clicked and the crash can neither be
 * reported nor dismissed. These tests pin the three properties that keep that from happening:
 * the footer is pinned, the body scrolls, and none of it costs anything when the content fits.
 *
 * The window is reproduced with `clipToBounds()`, because that is what makes off-window content
 * actually undisplayed — without clipping, overflow is merely painted outside the frame and
 * `assertIsDisplayed` would pass on a broken layout.
 */
class CrashReportDialogLayoutTest {
    private companion object {
        /**
         * How far the footer may sit below the content it follows. Correct is ~30dp (a 16dp spacer
         * plus the checkbox row's padding); a `weight(1f)` regression measures ~271dp. The
         * threshold sits between those, far from both — it is slack, not a tuned value.
         */
        val MAX_FOOTER_GAP = 80.dp

        /**
         * Worst-case window decoration, subtracted from `CrashHandler.FRAME_MIN_*` to get a content
         * box no platform's is smaller than. Measured 32px of title bar on macOS (0 at the sides);
         * Windows and common Linux WMs run to roughly 31-40 with side borders. Rounding up means
         * these tests exercise a box *tighter* than anything that ships, which is the safe
         * direction — the alternative, assuming the frame minimum is also the content minimum,
         * validates against headroom no user has.
         */
        val WORST_CASE_DECORATION_HEIGHT = 40.dp
        val WORST_CASE_DECORATION_WIDTH = 16.dp
    }

    @get:Rule
    val rule = createComposeRule()

    /** Deep enough that the stack trace pane reaches its 200dp cap. */
    private val deepCrashReport =
        CrashReport(
            signature = "test-signature",
            exceptionType = "java.lang.IllegalStateException",
            exceptionMessage = "Something went badly wrong while doing the thing",
            stackTrace =
                "java.lang.IllegalStateException: Something went badly wrong\n" +
                    (1..60).joinToString("\n") { i ->
                        "\tat ai.rever.boss.example.Frame$i.doWork(Frame$i.kt:$i)"
                    },
            systemInfo =
                SystemInfo(
                    osName = "Mac OS X",
                    osVersion = "15.0",
                    osArch = "aarch64",
                    javaVersion = "21",
                    javaVendor = "Test",
                    heapUsedMB = 256,
                    heapMaxMB = 4096,
                    nonHeapUsedMB = 128,
                    availableProcessors = 8,
                ),
            appInfo = AppInfo(version = "9.9.9", platform = "macos", isDebug = true),
            timestamp = 0L,
        )

    @Composable
    private fun Dialog(submitResult: CrashReportService.SubmitResult? = null) {
        CrashReportDialog(
            crashReport = deepCrashReport,
            onDismiss = {},
            onSubmit = { _, _ -> },
            onCleanAndRestart = {},
            initialSubmitResult = submitResult,
        )
    }

    private fun setDialogInWindow(
        width: Dp,
        height: Dp,
        submitResult: CrashReportService.SubmitResult? = null,
    ) {
        rule.setContent {
            Box(modifier = Modifier.size(width, height).clipToBounds()) {
                Dialog(submitResult)
            }
        }
    }

    /** Tighter than the smallest content pane that ships; see [WORST_CASE_DECORATION_HEIGHT]. */
    private fun setDialogAtMinimumWindowSize(submitResult: CrashReportService.SubmitResult? = null) =
        setDialogInWindow(
            CrashHandler.FRAME_MIN_WIDTH.dp - WORST_CASE_DECORATION_WIDTH,
            CrashHandler.FRAME_MIN_HEIGHT.dp - WORST_CASE_DECORATION_HEIGHT,
            submitResult,
        )

    @Test
    fun actionButtonsStayVisibleWhenTechnicalDetailsAreExpanded() {
        setDialogAtMinimumWindowSize()

        rule.onNodeWithText("Report Issue").assertIsDisplayed()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // The expanded stack trace must not push the footer off the window.
        rule.onNodeWithText("Report Issue").assertIsDisplayed()
        rule.onNodeWithText("Don't Send").assertIsDisplayed()
        rule.onNodeWithText("Clean Data & Restart").assertIsDisplayed()
    }

    @Test
    fun footerButtonsAreWhollyOnScreenNotMerelyIntersectingIt() {
        setDialogAtMinimumWindowSize()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // assertIsDisplayed() only requires a node's *clipped* bounds to be non-empty, so a button
        // hanging most of the way off an edge still satisfies it. Comparing clipped against
        // unclipped bounds is what actually rules that out — cheap, and it closes the one soft spot
        // in the assertions above.
        for (label in listOf("Clean Data & Restart", "Don't Send", "Report Issue")) {
            val node = rule.onNodeWithText(label)
            assertEquals(
                node.getUnclippedBoundsInRoot(),
                node.getBoundsInRoot(),
                "\"$label\" is partially outside the window",
            )
        }
    }

    @Test
    fun bodyContentBelowTheFoldIsReachableByScrolling() {
        setDialogAtMinimumWindowSize()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        // Fails without a scrollable ancestor: the checkbox sits below the fold once the
        // details are expanded, so it is only reachable if the body actually scrolls.
        rule.onNodeWithText("Include recent activity logs").performScrollTo().assertIsDisplayed()

        // Scrolling the body must not have carried the footer away with it.
        rule.onNodeWithText("Report Issue").assertIsDisplayed()
    }

    @Test
    fun collapsedDialogFitsWithoutScrollingAtThePreferredWindowSize() {
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        // Note the absent performScrollTo: at the preferred size a collapsed report fits, and
        // must still fit — nothing may be pushed below the fold to pay for chrome it doesn't need.
        rule.onNodeWithText("Include recent activity logs").assertIsDisplayed()
        rule.onNodeWithText("What were you doing when this happened? (optional)").assertIsDisplayed()
        rule.onNodeWithText("Report Issue").assertIsDisplayed()
    }

    @Test
    fun collapsedFooterSitsBelowTheContentRatherThanAtTheWindowBottom() {
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        // `weight(1f, fill = false)` is what keeps the body's height cap from also being a floor.
        // Visibility assertions cannot see the difference — with a plain `weight(1f)` everything
        // is still displayed, just with the footer stranded at the bottom edge and ~230dp of dead
        // space above it. Only the geometry shows it, so this measures the geometry.
        val contentBottom =
            rule.onNodeWithText("Helps with debugging (logs are sanitized)").getBoundsInRoot().bottom
        val footerTop = rule.onNodeWithText("Report Issue").getBoundsInRoot().top

        val gap = footerTop - contentBottom
        assertTrue(
            gap < MAX_FOOTER_GAP,
            "Footer should follow the content when it fits, but sat ${gap.value}dp below it — " +
                "the body is claiming space it does not need (regression to plain weight(1f)?)",
        )
    }

    @Test
    fun theBodyScrollbarAppearsOnlyWhileTheBodyIsClipping() {
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        // Pins the *gating*: a thumb appears when, and only when, there is travel to offer.
        // Verified by control that this does NOT catch the `maxValue > 0` sentinel bug — by the
        // time waitForIdle() returns, measure has assigned maxValue and both forms agree. The
        // sentinel needs a paused clock; see theBodyScrollbarIsAbsentEvenOnTheFirstFrame.
        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertDoesNotExist()

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertExists()
    }

    @Test
    fun theBodyScrollbarIsAbsentEvenOnTheFirstFrame() {
        // The sentinel guard specifically. Every other assertion in this class runs after
        // waitForIdle(), by which point measure has assigned maxValue and the naive `maxValue > 0`
        // form behaves identically — so pausing the clock is the only way to observe the frame the
        // sentinel affects.
        rule.mainClock.autoAdvance = false
        setDialogInWindow(
            CrashHandler.CONTENT_PREFERRED_WIDTH.dp,
            CrashHandler.CONTENT_PREFERRED_HEIGHT.dp,
        )

        rule.onNodeWithTag(BODY_SCROLLBAR_TAG).assertDoesNotExist()
    }

    @Test
    fun aLongSubmitFailureCannotPushTheButtonsOffTheWindow() {
        // The one path that can squeeze the body from *below*: the submit-result card sits in the
        // pinned footer, and CrashReportService interpolates e.message into it, which a TLS or
        // proxy failure can make arbitrarily long. maxLines caps it; this is that cap's guard.
        setDialogAtMinimumWindowSize(
            CrashReportService.SubmitResult.Error("Failed to submit crash report: " + "boom ".repeat(1000)),
        )

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        for (label in listOf("Clean Data & Restart", "Don't Send", "Report Issue")) {
            val node = rule.onNodeWithText(label)
            assertEquals(
                node.getUnclippedBoundsInRoot(),
                node.getBoundsInRoot(),
                "\"$label\" left the window once a long failure message filled the footer",
            )
        }
    }

    @Test
    fun theSuccessFooterKeepsBothItsRowsOnScreen() {
        // Success is structurally different from Error: the result card *plus* a second row holding
        // "Close" — the only configuration with two button rows, and so the tallest footer the
        // pinned layout has to fit.
        setDialogAtMinimumWindowSize(
            CrashReportService.SubmitResult.Success(
                issueUrl = "https://github.com/risa-labs-inc/BossConsole/issues/1",
                isNewIssue = true,
            ),
        )

        rule.onNodeWithText("Technical Details").performClick()
        rule.waitForIdle()

        for (label in listOf("Clean Data & Restart", "Close")) {
            val node = rule.onNodeWithText(label)
            assertEquals(
                node.getUnclippedBoundsInRoot(),
                node.getBoundsInRoot(),
                "\"$label\" left the window with the success footer's second row present",
            )
        }
    }
}
