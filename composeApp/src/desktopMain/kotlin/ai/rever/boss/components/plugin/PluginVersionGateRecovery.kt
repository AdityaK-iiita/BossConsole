package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.Version
import ai.rever.boss.plugin.loader.ApiClassLoader
import ai.rever.boss.updater.UpdateManager
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Resolves what can be offered for a [PluginVersionGate], and carries out the choice.
 *
 * [remediesFor] decides *what* to offer and is deliberately pure. This is the other half: the three
 * facts it needs, and the actions behind each button. Kept separate so the decision stays testable
 * without an updater, a store or a plugins directory - and so this file can be read as "what does
 * each button actually do", which is the question a reviewer will have.
 */
internal object PluginVersionGateRecovery {
    private val logger = BossLogger.forComponent("PluginVersionGateRecovery")

    /**
     * Whether [candidate] meets [required], by the loader's own rule.
     *
     * `Version.parse` plus `>=` is exactly what `DynamicPluginLoader.isBossVersionCompatible` does,
     * including failing OPEN on an unparseable version - which matters for consistency rather than
     * convenience: if the loader would let the plugin through, a remedy that assumed otherwise
     * would hide a button that works.
     */
    fun satisfies(
        required: String,
        candidate: String,
    ): Boolean {
        val requiredVersion = Version.parse(required)
        val candidateVersion = Version.parse(candidate)
        // Either side unparseable fails open, which is the loader's own behaviour.
        return requiredVersion == null || candidateVersion == null || candidateVersion >= requiredVersion
    }

    /**
     * The app version an update would install, or null when there is none.
     *
     * Read off the state the updater already holds rather than triggering a check. A dialog that
     * kicked off a network round trip before it could render would appear late or not at all, and
     * "no update available" is a perfectly good thing to say when the answer is not known yet -
     * reverting is still offered, and the periodic check will change the answer on its own.
     */
    fun hostUpdateVersion(): String? =
        (UpdateManager.instance.updateState.value as? UpdateState.UpdateAvailable)
            ?.updateInfo
            ?.latestVersion
            ?.toString()
            ?.takeIf { it.isNotBlank() }

    /**
     * The api-layer version the store publishes, or null when it cannot be asked.
     *
     * A live lookup, unlike the host update, because nothing polls the api plugin's version in the
     * background - and unlike an app update it costs one request rather than a download.
     */
    suspend fun apiUpdateVersion(): String? {
        // The remote repository directly, not `repositoryManager`. The manager merges local and
        // remote, and the LOCAL copy is the api jar already installed - so asking it would answer
        // with the version that is failing to satisfy the floor.
        val store = PluginStoreSetup.remoteRepository ?: return null
        // Both failure shapes: `getPlugin` returns `Result.failure` rather than throwing, so
        // runCatching alone would report success with a null inside it.
        val lookup = runCatching { store.getPlugin(ApiClassLoader.API_PLUGIN_ID) }.getOrElse { Result.failure(it) }
        lookup.exceptionOrNull()?.let { e ->
            logger.warn(LogCategory.SYSTEM, "Could not ask the store for the api version: ${e.message}")
        }
        return lookup.getOrNull()?.version?.takeIf { it.isNotBlank() }
    }

    /** The version kept aside for [pluginId], or null when nothing was kept. */
    fun revertVersion(pluginId: String): String? =
        runCatching {
            PluginRollbackStore.availableVersion(PluginStoreSetup.getPluginDir(), pluginId)
        }.getOrNull()

    /**
     * Carry out [remedy], returning what to tell the user on success.
     *
     * Each branch ends by clearing the gate, because leaving it recorded would put the dialog back
     * in front of the user for a problem they just fixed. The exception is a failure, which keeps
     * the gate so the dialog can stay open with the reason and the remaining options.
     */
    suspend fun apply(
        gate: PluginVersionGate,
        remedy: PluginVersionRemedy,
        manager: DynamicPluginManager,
    ): Result<String> =
        when (remedy) {
            is PluginVersionRemedy.UpdateHost -> updateHost(remedy)

            is PluginVersionRemedy.UpdateApi -> updateApi(gate, remedy, manager)

            is PluginVersionRemedy.RevertPlugin -> revert(gate, remedy, manager)

            // Not reachable from a button: the dialog renders this as a sentence. Handled rather
            // than thrown so a future caller cannot turn it into a crash.
            is PluginVersionRemedy.NothingAvailable -> Result.failure(IllegalStateException(remedy.reason))
        }

    /**
     * Download the app update and hand off to the existing install flow.
     *
     * Deliberately does NOT clear the gate. The plugin is still unloadable until the new app runs,
     * so clearing it would remove the only explanation of why the plugin is missing during the
     * window between downloading and restarting.
     */
    private suspend fun updateHost(remedy: PluginVersionRemedy.UpdateHost): Result<String> {
        val updater = UpdateManager.instance
        val info =
            (updater.updateState.value as? UpdateState.UpdateAvailable)?.updateInfo
                ?: return Result.failure(
                    IllegalStateException("The update to ${remedy.availableVersion} is no longer available."),
                )
        updater.downloadUpdateInBackground(info)
        return Result.success("Downloading BOSS ${remedy.availableVersion}. The plugin loads after the restart.")
    }

    /**
     * Install a newer api plugin, which is a hot swap rather than a restart.
     *
     * Routed through `installPlugin`, because that is where the api id is recognised and turned
     * into an unload-all / swap / reload-all - and the reload-all is what gives the refused plugin
     * its second chance without the user doing anything else.
     */
    private suspend fun updateApi(
        gate: PluginVersionGate,
        remedy: PluginVersionRemedy.UpdateApi,
        manager: DynamicPluginManager,
    ): Result<String> {
        val store =
            PluginStoreSetup.remoteRepository
                ?: return Result.failure(IllegalStateException("The plugin store is not available."))
        val installer = StoreVersionInstaller(pluginDir = { PluginStoreSetup.getPluginDir() })
        val result =
            installer.install(
                store = store,
                request =
                    StoreVersionRequest(
                        pluginId = ApiClassLoader.API_PLUGIN_ID,
                        version = remedy.availableVersion,
                        sourceUrl = null,
                        runningJarPath = manager.getPluginInfo(ApiClassLoader.API_PLUGIN_ID)?.jarPath,
                    ),
                unload = { id -> manager.uninstallPlugin(id, force = true).map { } },
                load = { path -> manager.installPlugin(path).map { true } },
            )
        return result.map {
            PluginVersionGateRegistry.clear(gate.pluginId)
            "Plugin API updated to ${remedy.availableVersion}."
        }
    }

    /**
     * Put the kept jar back and load it.
     *
     * The one remedy that resolves the problem outright rather than starting something: no
     * download, no restart, and the plugin is present again when it returns.
     */
    private suspend fun revert(
        gate: PluginVersionGate,
        remedy: PluginVersionRemedy.RevertPlugin,
        manager: DynamicPluginManager,
    ): Result<String> {
        val pluginDir = PluginStoreSetup.getPluginDir()
        // The refused plugin never loaded, so `getPluginInfo` is usually empty for it and the jar
        // to remove has to be found on disk. It matters: leaving the refused jar there means the
        // next launch scans it, refuses it again, and the plugin directory holds two versions of
        // one plugin id.
        val brokenJar =
            manager.getPluginInfo(gate.pluginId)?.jarPath
                ?: refusedJarFor(pluginDir, gate.pluginId)?.absolutePath
        val restored =
            PluginRollbackStore.restore(pluginDir, gate.pluginId, brokenJar)
                ?: return Result.failure(
                    IllegalStateException("Version ${remedy.toVersion} is no longer available to restore."),
                )
        return manager
            .installPlugin(restored.absolutePath)
            .map { info ->
                PluginVersionGateRegistry.clear(gate.pluginId)
                "${info.manifest.displayName} is back on version ${remedy.toVersion}."
            }.onFailure { e ->
                logger.error(
                    LogCategory.SYSTEM,
                    "Restored the previous plugin jar but it did not load",
                    mapOf("pluginId" to gate.pluginId, "version" to remedy.toVersion),
                    error = e,
                )
            }
    }
}

/**
 * The jar in [pluginDir] declaring [pluginId], read from each manifest rather than guessed.
 *
 * By manifest, not by filename. Jars arrive under at least three conventions - the host's
 * `<pluginId>-<version>.jar`, the Toolbox's `<plugin_id>_<version>.jar` and whatever a sideloaded
 * file is called - so a name match would miss exactly the case a user is stuck in. This is the
 * same identification `PluginJarReconciler` performs for the same reason.
 */
private fun refusedJarFor(
    pluginDir: java.io.File,
    pluginId: String,
): java.io.File? =
    pluginDir
        .listFiles { f: java.io.File -> f.isFile && f.name.endsWith(".jar") }
        ?.firstOrNull { jar ->
            runCatching {
                ai.rever.boss.plugin.loader.PluginManifestReader
                    .readFromJar(jar.absolutePath)
                    .pluginId
            }.getOrNull() == pluginId
        }

/**
 * The desktop half of [PluginVersionRemedyResolver], wired to the real updater, store and disk.
 *
 * A thin adapter over [PluginVersionGateRecovery] so the host composable in `commonMain` can be
 * mounted without knowing any of that exists.
 */
object DesktopPluginVersionRemedyResolver : PluginVersionRemedyResolver {
    override suspend fun resolve(gate: PluginVersionGate): List<PluginVersionRemedy> =
        remediesFor(
            gate = gate,
            hostUpdate = PluginVersionGateRecovery.hostUpdateVersion(),
            // Only asked for the gate it can fix. An api lookup for a host-version refusal is a
            // store request whose answer nothing would read.
            apiUpdate =
                when (gate) {
                    is PluginVersionGate.NeedsNewerApi -> PluginVersionGateRecovery.apiUpdateVersion()
                    is PluginVersionGate.NeedsNewerHost -> null
                },
            revertTo = PluginVersionGateRecovery.revertVersion(gate.pluginId),
            satisfies = PluginVersionGateRecovery::satisfies,
        )

    override suspend fun apply(
        gate: PluginVersionGate,
        remedy: PluginVersionRemedy,
        manager: DynamicPluginManager,
    ): Result<String> = PluginVersionGateRecovery.apply(gate, remedy, manager)
}
