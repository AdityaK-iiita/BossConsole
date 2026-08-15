package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Removes a plugin: unload it, then delete what it left behind.
 *
 * Detached from the caller for the same reason installs are. The prompt runs on a window's
 * `rememberCoroutineScope`, and a cancellation landing between the unload and the cleanup leaves the
 * jar and its `installed.json` row on disk after the panels have already been torn down, so the
 * plugin comes back at the next launch and the user's "uninstall" reads as a lie. Coalescing per
 * plugin id also stops two windows racing the same removal.
 */
object PluginRemoval {
    private val logger = BossLogger.forComponent("PluginRemoval")

    private val REMOVAL_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val DETACHED_REMOVALS = KeyedDetachedJobs<String, Result<Unit>>(REMOVAL_SCOPE)

    suspend fun remove(
        pluginId: String,
        jarPath: String,
        manager: DynamicPluginManager,
    ): Result<Unit> =
        DETACHED_REMOVALS.run(
            key = pluginId,
            onDetachedFailure = { error ->
                logger.error(LogCategory.SYSTEM, "Detached plugin removal failed", error = error)
            },
        ) {
            val unloaded = manager.uninstallPlugin(pluginId, force = false)
            if (unloaded.isFailure) {
                return@run unloaded
            }
            // Only once the plugin is unloaded: deleting a jar out from under a live classloader is
            // how you get NoClassDefFoundError from code that is still running.
            PluginArtifactCleanup.remove(pluginId, jarPath)
            Result.success(Unit)
        }

    /**
     * Why [pluginId] cannot usefully be removed, or null when it can.
     *
     * The manifest gate (`systemPlugin || !canUnload`) covers the plugins the manager refuses to
     * unload. This covers a different case: a plugin whose jar also sits in the bundled directory is
     * re-copied by `copyBundledPluginsToPluginDir` on the next launch whenever no jar for its id is
     * in the plugins directory. Uninstalling one of those succeeds and then quietly undoes itself, so
     * it is better to say so than to let the plugin reappear.
     */
    fun removalVeto(
        pluginId: String,
        bundledDir: java.io.File,
        readManifestId: (String) -> String? = { path ->
            runCatching {
                ai.rever.boss.plugin.loader.PluginManifestReader
                    .readFromJar(path)
                    .pluginId
            }.getOrNull()
        },
    ): String? {
        val jars =
            runCatching {
                bundledDir.takeIf { it.isDirectory }?.listFiles { f -> f.isFile && f.extension == "jar" }
            }.getOrNull() ?: return null
        val bundled = jars.any { readManifestId(it.absolutePath) == pluginId }
        return if (bundled) "ships with BOSS and would be restored at the next launch" else null
    }
}
