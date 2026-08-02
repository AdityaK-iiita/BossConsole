package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.isUncontainable

/**
 * What the window exception handler should do with a throwable that escaped the
 * Compose render loop.
 *
 * Split out of `main.kt` so the decision can be tested. It used to live inline in
 * an anonymous `WindowExceptionHandler` inside `application {}`, where the one
 * thing worth asserting — that a dead JVM is never "contained" — could only be
 * argued for.
 */
enum class WindowExceptionRoute {
    /** Attributable to a plugin; the crash interceptor owns it. */
    PluginHandled,

    /** Contain: keep the window, recover the plugin panels, tell the user. */
    Contain,

    /** Hand to Compose's default handler, which disposes the window and ends the app. */
    Escalate,
}

/**
 * Decide how to route [throwable].
 *
 * Order matters and is the point of this function:
 *
 * 1. **Attributed** to a plugin — the interceptor already knows what to do.
 * 2. **Uncontainable** ([isUncontainable]) — escalate before anything else. The
 *    render boundary rethrows [OutOfMemoryError] rather than blaming a plugin for
 *    it, and that carve-out is worthless unless this agrees: containing here
 *    would log, toast, and repaint every window, which under heap exhaustion is
 *    more allocation, three times, before the circuit breaker gives up.
 * 3. **Too many failures too fast** — the scene is not recovering, so stop
 *    pretending. Note this consumes a slot in [policy], so it must be reached
 *    only for faults actually eligible for containment; that is why the
 *    uncontainable check sits above it rather than below.
 * 4. Otherwise contain.
 */
fun decideWindowExceptionRoute(
    throwable: Throwable,
    attributedPluginId: String?,
    policy: RenderCrashPolicy,
): WindowExceptionRoute =
    when {
        attributedPluginId != null -> WindowExceptionRoute.PluginHandled
        isUncontainable(throwable) -> WindowExceptionRoute.Escalate
        !policy.recordFailureAndShouldContain() -> WindowExceptionRoute.Escalate
        else -> WindowExceptionRoute.Contain
    }
