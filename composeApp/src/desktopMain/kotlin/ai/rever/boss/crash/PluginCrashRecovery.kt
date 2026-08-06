package ai.rever.boss.crash

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Takes a crashed plugin out of the running app so the app can keep running.
 *
 * @return true when recovery took effect. False means the crash could not be
 *   contained after all, and the caller must fall back to terminating — see
 *   [CrashHandler.resolveCrash]. Returning true on a no-op would be the worst
 *   outcome available: dialog dismissed, crashing plugin still live.
 */
fun interface PluginCrashRecoveryHandler {
    fun recover(
        pluginId: String,
        error: Throwable,
    ): Boolean
}

/**
 * Process-wide seam between the crash handler and the plugin layer.
 *
 * [CrashHandler] lives below the plugin manager and cannot reach it (managers are
 * per-window and created inside `DefaultPlugin`), so the desktop layer installs a
 * handler once at startup — the same shape as
 * `DynamicPluginManager.pluginUiTeardown`. Null in tests and headless runs, which
 * is exactly what makes such a crash classify as [CrashDisposition.FatalHost]
 * rather than silently pretend to recover.
 */
object PluginCrashRecovery {
    private val logger = BossLogger.forComponent("PluginCrashRecovery")

    @Volatile
    var handler: PluginCrashRecoveryHandler? = null

    /** Whether a plugin crash can be recovered at all right now. */
    val isAvailable: Boolean
        get() = handler != null

    /**
     * Recover [pluginId], or report that we could not.
     *
     * Never throws: this runs on the way out of a crash dialog, where a second
     * failure has nowhere to go.
     */
    // Throwable: recovery reaches into the plugin layer, where a half-unloaded
    // classloader throws NoClassDefFoundError rather than an Exception - and a
    // failure to recover has to become a termination, not a second crash.
    @Suppress("TooGenericExceptionCaught")
    fun recover(
        pluginId: String,
        error: Throwable,
    ): Boolean {
        val active = handler ?: return false
        return try {
            active.recover(pluginId, error)
        } catch (t: Throwable) {
            logger.error(
                LogCategory.SYSTEM,
                "Plugin crash recovery failed",
                mapOf("pluginId" to pluginId),
                t,
            )
            false
        }
    }
}

/**
 * The plugin-layer operations recovery needs, as a seam.
 *
 * An interface rather than a `DynamicPluginManager`, because the manager is
 * per-window, built inside `DefaultPlugin`, and needs a live UI - none of which a
 * test of "does dismissing a plugin crash kill the app" should have to construct.
 * The desktop wiring in `PluginLoaderDelegateSetup` supplies the real ones.
 */
interface PluginRecoverySteps {
    /** Whether this id names a plugin anything can actually act on. */
    fun isKnown(pluginId: String): Boolean

    /**
     * Mark the plugin crashed, so every window's error boundary swaps to its
     * fallback and stops rendering plugin content. Must take effect before the
     * crash dialog goes away.
     */
    fun quarantine(
        pluginId: String,
        error: Throwable,
    )

    /** Close the plugin's open tabs across every window. */
    suspend fun closeTabs(pluginId: String)

    /** Disable it in every live manager; false if none accepted it. */
    suspend fun disable(pluginId: String): Boolean

    /** Record the disable, so a crash-on-load plugin does not return next launch. */
    fun persistDisabled(pluginId: String)

    /** Tell the user which plugin went away and how to get it back. */
    fun notifyDisabled(pluginId: String)
}

/**
 * The real recovery: quarantine now, unload in the background.
 *
 * ### Why the split between synchronous and background work
 *
 * The synchronous half is what makes the app *safe*: the plugin is marked crashed,
 * so every window's [ai.rever.boss.plugin.sandbox.ui.PluginErrorBoundary] swaps to
 * its fallback and stops rendering plugin content. That has to happen before this
 * returns, because the caller disposes the crash dialog immediately afterwards and
 * the user is looking at the app again.
 *
 * Unloading is the slow half — closing tabs across every window on the EDT,
 * stopping sandboxes, rewriting `installed.json` — and it is launched rather than
 * awaited. Blocking the event thread on it would freeze the app the user was just
 * promised would keep working, and a failure there is not a reason to kill the
 * session: the plugin is already quarantined and rendering nothing.
 */
class PluginCrashRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val steps: PluginRecoverySteps,
) : PluginCrashRecoveryHandler {
    private val logger = BossLogger.forComponent("PluginCrashRecovery")

    override fun recover(
        pluginId: String,
        error: Throwable,
    ): Boolean {
        // Both halves must hold before this can claim to have recovered: something
        // has to know the plugin, and it has to actually stop rendering. `&&` short
        // circuits, so an unknown plugin is never quarantined.
        val contained = isActionable(pluginId) && quarantineSafely(pluginId, error)
        if (contained) {
            logger.warn(
                LogCategory.SYSTEM,
                "Recovering from plugin crash - disabling the plugin, keeping the app",
                mapOf(
                    "pluginId" to pluginId,
                    "errorType" to error.javaClass.simpleName,
                ),
            )
            notifySafely(pluginId)
            scope.launch { unload(pluginId) }
        }
        return contained
    }

    /** The one honest reason to refuse: there is no such plugin to take out. */
    private fun isActionable(pluginId: String): Boolean {
        val known = steps.isKnown(pluginId)
        if (!known) {
            logger.warn(
                LogCategory.SYSTEM,
                "Crash attributed to a plugin nothing knows about - cannot recover",
                mapOf("pluginId" to pluginId),
            )
        }
        return known
    }

    /**
     * Quarantine, and report honestly if it did not happen.
     *
     * Failing here means the app was NOT made safe, so the caller has to terminate
     * rather than dismiss the dialog over a plugin that is still rendering.
     */
    @Suppress("TooGenericExceptionCaught") // The plugin layer throws Errors, not just Exceptions.
    private fun quarantineSafely(
        pluginId: String,
        error: Throwable,
    ): Boolean =
        try {
            steps.quarantine(pluginId, error)
            true
        } catch (t: Throwable) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to quarantine crashed plugin",
                mapOf("pluginId" to pluginId),
                t,
            )
            false
        }

    /** A failed toast must not turn a successful recovery into a termination. */
    @Suppress("TooGenericExceptionCaught")
    private fun notifySafely(pluginId: String) {
        try {
            steps.notifyDisabled(pluginId)
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not show the plugin-disabled notification",
                mapOf("pluginId" to pluginId),
                t,
            )
        }
    }

    /**
     * The background half. Each step is independent: a plugin whose tabs refuse
     * to close must still be disabled, and a disable that fails must still be
     * persisted, or the next launch loads the same crashing plugin.
     */
    private suspend fun unload(pluginId: String) {
        runCatching { steps.closeTabs(pluginId) }
            .onFailure { logFailure("close the crashed plugin's tabs", pluginId, it) }
        val disabled =
            runCatching { steps.disable(pluginId) }
                .onFailure { logFailure("disable the crashed plugin", pluginId, it) }
                .getOrDefault(false)
        runCatching { steps.persistDisabled(pluginId) }
            .onFailure { logFailure("persist the disabled state", pluginId, it) }
        logger.info(
            LogCategory.SYSTEM,
            "Crashed plugin unloaded",
            mapOf(
                "pluginId" to pluginId,
                "disabledInManagers" to disabled.toString(),
            ),
        )
    }

    private fun logFailure(
        what: String,
        pluginId: String,
        cause: Throwable,
    ) = logger.warn(
        LogCategory.SYSTEM,
        "Failed to $what",
        mapOf("pluginId" to pluginId),
        cause,
    )
}
