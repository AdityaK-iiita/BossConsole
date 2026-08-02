package ai.rever.boss.crash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers how an exception escaping the Compose render loop is routed.
 *
 * The case that matters most is the one that used to be only an argument in a
 * comment: [PluginRenderBoundary][ai.rever.boss.plugin.sandbox.ui.PluginRenderBoundary]
 * deliberately rethrows [OutOfMemoryError] rather than blaming a plugin for a
 * dead JVM — and that carve-out was worthless, because the rethrown error landed
 * here and got contained anyway, which logs, toasts and repaints every window.
 * Under heap exhaustion that is more allocation, three times over, before the
 * circuit breaker gives up.
 */
class WindowExceptionRouteTest {
    private fun policy() = RenderCrashPolicy(now = { 0L })

    @Test
    fun `an attributed plugin crash is left to the interceptor`() {
        val route =
            decideWindowExceptionRoute(
                IllegalStateException("boom"),
                attributedPluginId = "ai.rever.boss.plugin.dynamic.bookmarks",
                policy = policy(),
            )

        assertEquals(WindowExceptionRoute.PluginHandled, route)
    }

    @Test
    fun `an ordinary unattributed fault is contained`() {
        val route = decideWindowExceptionRoute(IllegalStateException("boom"), null, policy())

        assertEquals(WindowExceptionRoute.Contain, route)
    }

    @Test
    fun `an OutOfMemoryError escalates instead of being contained`() {
        val route = decideWindowExceptionRoute(OutOfMemoryError("Java heap space"), null, policy())

        assertEquals(
            WindowExceptionRoute.Escalate,
            route,
            "containing an OOM means repainting every window under heap exhaustion",
        )
    }

    @Test
    fun `a StackOverflowError escalates`() {
        val route = decideWindowExceptionRoute(StackOverflowError(), null, policy())

        assertEquals(WindowExceptionRoute.Escalate, route)
    }

    @Test
    fun `an uncontainable error does not consume a containment slot`() {
        // Order matters: if the uncontainable check sat below the policy call, an
        // OOM would burn budget meant for faults that can actually be contained.
        val policy = policy()
        repeat(5) { decideWindowExceptionRoute(OutOfMemoryError(), null, policy) }

        assertEquals(0, policy.recentFailureCount(), "escalated faults must not be recorded as containable ones")
        assertEquals(
            WindowExceptionRoute.Contain,
            decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
            "a real containable fault should still have its full budget",
        )
    }

    @Test
    fun `a sustained burst eventually escalates`() {
        val policy = policy()
        repeat(RenderCrashPolicy.DEFAULT_MAX_FAILURES) {
            assertEquals(
                WindowExceptionRoute.Contain,
                decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
            )
        }

        assertEquals(
            WindowExceptionRoute.Escalate,
            decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
        )
    }

    @Test
    fun `an attributed crash is never escalated, even when fatal`() {
        // The interceptor owns anything it can attribute; this branch should not
        // second-guess it.
        val route = decideWindowExceptionRoute(OutOfMemoryError(), "some.plugin", policy())

        assertEquals(WindowExceptionRoute.PluginHandled, route)
    }
}
