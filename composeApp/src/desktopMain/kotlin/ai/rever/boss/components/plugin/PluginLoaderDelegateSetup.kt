package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.crash.PluginCrashRecovery
import ai.rever.boss.crash.PluginCrashRecoveryCoordinator
import ai.rever.boss.crash.PluginRecoverySteps
import ai.rever.boss.plugin.PluginLoaderDelegateImpl
import ai.rever.boss.plugin.PluginPersistence
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop implementation of PluginLoaderDelegateSetup.
 *
 * Registers the PluginLoaderDelegateImpl so that dynamic plugins
 * (like plugin-manager) can interact with the plugin system.
 */
actual object PluginLoaderDelegateSetup {
    private val logger = BossLogger.forComponent("PluginLoaderDelegateSetup")

    /**
     * Register the PluginLoaderDelegate with the plugin context.
     *
     * @param context Plugin context for registration
     * @param dynamicPluginManager The dynamic plugin manager
     */
    actual fun register(
        context: PluginContext,
        dynamicPluginManager: DynamicPluginManager,
    ) {
        logger.info(LogCategory.SYSTEM, "Registering PluginLoaderDelegate for dynamic plugins")

        val delegate = PluginLoaderDelegateImpl(dynamicPluginManager)
        context.registerPluginAPI(delegate)

        // Give the API-layer hot swap a way to tear down plugin-hosting UI
        // before it closes any classloader (avoids NoClassDefFoundError from
        // Compose disposing a plugin's UI against a closed loader). Process-
        // wide + spans all windows, so set once; register() runs per window.
        if (DynamicPluginManager.pluginUiTeardown == null) {
            DynamicPluginManager.pluginUiTeardown = { delegate.teardownAllPluginTabs() }
        }
        // Per-plugin teardown for the shared uninstall path, so plugin-manager
        // updates and update notifications reload tab-hosting plugins cleanly.
        if (DynamicPluginManager.pluginTabsTeardown == null) {
            DynamicPluginManager.pluginTabsTeardown = { id -> delegate.teardownPluginTabs(id) }
        }
        // Panel counterpart, on the (re)register side: after a plugin's panel
        // factories are re-registered (reload/update/enable), reset its open
        // sidebar panel slots so they pick up the new build instead of keeping
        // the pre-reload component (#856).
        if (DynamicPluginManager.pluginPanelsRefresh == null) {
            DynamicPluginManager.pluginPanelsRefresh = { id, panelIds -> delegate.refreshPluginPanels(id, panelIds) }
        }
        // Lets the crash handler take a crashed plugin out instead of taking the
        // app down. Until this is wired, a plugin crash classifies as fatal and
        // terminates as it always did - which is the honest behaviour for a run
        // with no plugin layer (headless, or a crash before this point).
        if (PluginCrashRecovery.handler == null) {
            PluginCrashRecovery.handler = createCrashRecovery(delegate)
        }

        logger.debug(LogCategory.SYSTEM, "PluginLoaderDelegate registered successfully")
    }

    /**
     * Assemble the recovery steps from pieces that already exist.
     *
     * Nothing here is new machinery: quarantine is what the render-fault path
     * already uses, tab teardown is the same call an update/reload makes, and the
     * disable is the normal one, only applied to every window.
     *
     * [PluginCrashRegistry.recordRenderFault] rather than `recordCrash`: the
     * latter closes the plugin's tab and then *clears* the crash state, which
     * would put a plugin we are about to disable back on screen for a frame.
     * `notify = false` because the message below names the plugin and says how to
     * get it back, and the registry's generic one goes through `invokeLater` -
     * it would land second and overwrite the useful wording in a single-slot
     * status bar (the same collision `PluginRenderRecovery` documents).
     */
    private fun createCrashRecovery(delegate: PluginLoaderDelegateImpl) =
        PluginCrashRecoveryCoordinator(
            scope = recoveryScope,
            steps =
                object : PluginRecoverySteps {
                    override fun isKnown(pluginId: String) = DynamicPluginManager.isPluginKnown(pluginId)

                    override fun quarantine(
                        pluginId: String,
                        error: Throwable,
                    ) = PluginCrashRegistry.recordRenderFault(pluginId, error, notify = false)

                    override suspend fun closeTabs(pluginId: String) {
                        delegate.teardownPluginTabs(pluginId)
                    }

                    override suspend fun disable(pluginId: String) = DynamicPluginManager.disableEverywhere(pluginId)

                    override fun persistDisabled(pluginId: String) = persistCrashDisable(pluginId)

                    override fun notifyDisabled(pluginId: String) =
                        StatusMessageManager.showMessage(
                            "Plugin '$pluginId' crashed and was disabled. Re-enable it from Toolbox.",
                            durationMs = CRASH_NOTICE_MILLIS,
                        )
                },
        )

    /**
     * Record the crash-disable so the plugin does not come back and crash again on
     * the next launch.
     *
     * `setPluginEnabled` alone is not enough, and fails silently where it matters:
     * it updates an existing `installed.json` entry and does nothing at all when
     * there is none. A jar dropped into the plugins directory by hand has no entry
     * (the directory scan installs it without writing one), so a plugin that
     * crashes on load would be disabled, produce a crash dialog, and be back at the
     * next launch - the exact loop persisting is meant to break. Verified on a
     * real crash: the call ran and `installed.json` was unchanged.
     *
     * Adding the entry also stops the directory scan re-installing it, since the
     * persisted pass registers a disabled plugin's jar as tracked.
     */
    private fun persistCrashDisable(pluginId: String) {
        if (PluginPersistence.isInstalled(pluginId)) {
            PluginPersistence.setPluginEnabled(pluginId, false)
            return
        }
        val jarPath = DynamicPluginManager.jarPathOf(pluginId)
        if (jarPath == null) {
            logger.warn(
                LogCategory.SYSTEM,
                "Cannot persist the crash-disable - no installed entry and no known jar",
                mapOf("pluginId" to pluginId),
            )
            return
        }
        PluginPersistence.addInstalledPlugin(pluginId = pluginId, jarPath = jarPath, enabled = false)
    }

    /**
     * Owner of the background half of crash recovery (tab teardown, unload,
     * persistence). Process-lifetime and deliberately never cancelled: it is
     * started from a crash dialog whose window is already gone, so there is no
     * caller left whose cancellation should abort the unload. SupervisorJob so
     * one failed recovery does not poison the next.
     */
    private val recoveryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Long enough to read a sentence naming a plugin and where to re-enable it. */
    private const val CRASH_NOTICE_MILLIS = 12_000L
}
