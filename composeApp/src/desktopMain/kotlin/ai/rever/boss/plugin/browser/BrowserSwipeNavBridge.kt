package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.js.JsAccessible
import java.util.concurrent.atomic.AtomicLong

/** Which way a committed swipe was going. */
internal enum class SwipeNavDirection { BACK, FORWARD }

/**
 * Page-to-host bridge for the two-finger swipe gesture, published on `window.__bossSwipeNav`.
 *
 * ## Reachability
 *
 * Like every bridge here, this is callable by any JavaScript on the page and not only by the
 * script that was injected alongside it. That is fine, and deliberately so: the only thing it can
 * do is move the tab through its own session history, which the page can already do with
 * `history.back()`. There is no capability here a site did not have, so the design goal is
 * narrowness rather than unreachability - one method, one enum's worth of input, nothing returned.
 *
 * ## Threading
 *
 * [navigate] runs on a JxBrowser thread and must not block or throw into the page's JS thread.
 * It hands off to [onNavigate] and returns; the actual `goBack()`/`goForward()` happens elsewhere.
 * A synchronous round trip into `browser.navigation()` from here would park the renderer's JS
 * thread on the browser thread, which is the shape of a deadlock rather than a slow call.
 */
internal class BrowserSwipeNavBridge(
    private val onNavigate: (SwipeNavDirection) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val logger = BossLogger.forComponent("BrowserSwipeNavBridge")
    private val lastFailureLoggedAt = AtomicLong(0)

    @JsAccessible
    fun navigate(direction: String?) {
        try {
            val parsed = parseSwipeNavDirection(direction) ?: return
            onNavigate(parsed)
        } catch (e: LinkageError) {
            // A wiring break rather than bad input: this class or the api jar is not what the
            // caller was compiled against. Enumerated rather than caught as Throwable, matching
            // BrowserInteractionBridge - an OutOfMemoryError is not this boundary's to swallow.
            reportFailure(e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Never propagate into the page's JS thread: a throw here surfaces in the site's own
            // console and can break its JS. Error is deliberately NOT included, so a fatal process
            // condition still escapes.
            reportFailure(e)
        }
    }

    /**
     * Log at most one failure per minute.
     *
     * Silence was the wrong trade: a page nobody swipes on and a bridge that was never published
     * look identical from the outside, and the script swallows its own errors too - so a wiring
     * break would have no signal anywhere along the path. A hostile page can call this in a loop,
     * hence the limiter. The exception class only, never its message: page detail must not reach a
     * log line.
     */
    private fun reportFailure(error: Throwable) {
        val now = nowMs()
        val previous = lastFailureLoggedAt.get()
        if (previous != 0L && now - previous < FAILURE_LOG_INTERVAL_MS) return
        if (!lastFailureLoggedAt.compareAndSet(previous, now)) return
        logger.warn(
            LogCategory.BROWSER,
            "Swipe navigation bridge call failed",
            mapOf("error" to (error::class.simpleName ?: "Throwable")),
        )
    }

    private companion object {
        const val FAILURE_LOG_INTERVAL_MS = 60_000L
    }
}

/**
 * Read a direction out of whatever the page passed.
 *
 * Anything that is not exactly one of the two words the script sends is dropped rather than
 * guessed at. Pure, so the refusals are pinned by tests.
 */
internal fun parseSwipeNavDirection(raw: String?): SwipeNavDirection? =
    when (raw) {
        "back" -> SwipeNavDirection.BACK
        "forward" -> SwipeNavDirection.FORWARD
        else -> null
    }

/**
 * Whether a swipe arriving at [nowMs] should navigate, given when the last one did.
 *
 * **The rationale this originally shipped with no longer applies, and the value changed with
 * it.** `swipe-nav.js` used to call [navigate] the instant a gesture crossed the commit
 * distance, mid-swipe - so the tail of ONE continuous finger movement really could look like a
 * second gesture and re-fire. Since `swipe-nav.js`'s `decide()` (boss-plugin-fluck-browser#36)
 * now calls this at most once per gesture END, that case is structurally impossible: there is
 * nothing left in the page's own state machine that can produce two calls for one swipe.
 *
 * What is left to guard against is narrower - a genuine double-fire *bug* in this bridge or its
 * caller, not a real second gesture - and the value was still tuned for the old case. Two
 * distinct 90px swipes, timed as a deliberate long hold followed by a quick flick, can now land
 * as little as roughly `GESTURE_GAP_MS` (120ms, `swipe-nav.js`) plus the second gesture's own
 * duration apart - a fast trackpad flick clears the script's `MIN_EVENTS` in well under 100ms -
 * so the old 400ms window silently dropped a legitimate second swipe the user could see they had
 * just made. 100ms sits below `GESTURE_GAP_MS` itself (400ms did not), which is a hard floor on
 * every real gesture's END regardless of how short the gesture that produced it was - so this
 * can never reject a genuinely separate swipe - while still exceeding a same-tick or same-frame
 * double-dispatch, which is the actual failure shape a bridge-level bug would take.
 *
 * Pure, so the window is pinned by a test rather than by trying to swipe twice quickly by hand.
 */
internal fun shouldAcceptSwipeNav(
    nowMs: Long,
    lastNavigationMs: Long,
): Boolean = nowMs - lastNavigationMs > SWIPE_NAV_DEBOUNCE_MS

internal const val SWIPE_NAV_DEBOUNCE_MS = 100L
