package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.isUncontainable

/**
 * What the crash dialog's exits should actually do.
 *
 * Every exit from that dialog used to terminate the process — dismiss, Escape,
 * and even a *successful* report submission. That is the right answer for a
 * corrupt host and the wrong one for the common case: a dynamic plugin threw
 * from a callback the host invoked, and the fix is to drop that plugin, not the
 * user's windows, tabs and terminal sessions.
 */
sealed interface CrashDisposition {
    /**
     * Attributable to [pluginId] and recoverable by disabling it. The dialog's
     * primary action continues without the plugin instead of terminating.
     */
    data class RecoverablePlugin(
        val pluginId: String,
    ) : CrashDisposition

    /** The host itself is broken (or recovery is impossible); terminate as before. */
    data object FatalHost : CrashDisposition
}

/**
 * Classify a crash.
 *
 * The order is the argument:
 *
 * 1. **No plugin id** — nobody to disable. Host fault, terminate.
 * 2. **[isUncontainable]** — [OutOfMemoryError] and [StackOverflowError] are not
 *    a plugin's to give back. Disabling the plugin frees nothing at the moment
 *    the heap is already exhausted, and every step of recovery (closing tabs,
 *    unloading, rendering a toast) allocates. The render path carves these out
 *    for the same reason; see `decideWindowExceptionRoute`.
 * 3. **No recovery wired** ([recoveryAvailable] false) — headless, or a crash
 *    before the plugin layer exists. Pretending we disabled something would
 *    leave the crashing plugin live and the dialog dismissed.
 * 4. Otherwise recoverable.
 *
 * Deliberately pure so the matrix is testable without a plugin manager, a
 * window, or a JVM willing to be exited.
 */
fun classifyCrash(
    throwable: Throwable,
    pluginId: String?,
    recoveryAvailable: Boolean,
): CrashDisposition =
    when {
        pluginId.isNullOrBlank() -> CrashDisposition.FatalHost
        anyCauseIsUncontainable(throwable) -> CrashDisposition.FatalHost
        !recoveryAvailable -> CrashDisposition.FatalHost
        else -> CrashDisposition.RecoverablePlugin(pluginId)
    }

/** How far [anyCauseIsUncontainable] walks, matching `PluginExecutionBoundary`. */
private const val MAX_CAUSE_DEPTH = 12

/**
 * [isUncontainable] applied to the whole cause chain, not just the top.
 *
 * The flat check was not enough for the way these actually arrive. Attribution
 * deliberately walks up to twelve causes because wrapping is routine - an
 * `InvocationTargetException` from a reflective call, a `CompletionException` from
 * a future, Compose's own wrappers - so an `OutOfMemoryError` reaching here as
 * `InvocationTargetException(cause = OutOfMemoryError)` got tagged with a plugin
 * id, passed a top-level `is` check, and classified as recoverable. Recovery would
 * then allocate a status message, launch a coroutine and tear down tabs across
 * every window on an already-exhausted heap, which is the exact thing the carve-out
 * exists to prevent.
 *
 * Bounded and cycle-guarded for the same reason attribution is: a crash handler
 * that hangs is worse than one that misattributes.
 */
private fun anyCauseIsUncontainable(throwable: Throwable): Boolean {
    var current: Throwable? = throwable
    var depth = 0
    val seen = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
    while (current != null && depth < MAX_CAUSE_DEPTH && seen.none { it === current }) {
        if (isUncontainable(current)) return true
        seen.add(current)
        current = current.cause
        depth++
    }
    return false
}
