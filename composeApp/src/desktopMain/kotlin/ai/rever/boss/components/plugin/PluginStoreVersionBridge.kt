package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.KeyedDetachedJobs
import ai.rever.boss.plugin.PluginStoreSetup
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Desktop implementation of the store-version bridge.
 *
 * Goes to the REMOTE repository directly, never through [PluginStoreSetup.repositoryManager]. The
 * manager is local-first and its [ai.rever.boss.plugin.repository.LocalPluginRepository] synthesises
 * a row from the installed jar's own manifest, so asking it about an installed plugin answers with
 * the very local build we are trying to replace - and `downloadPlugin` resolves its source the same
 * way, so it would "download" the local file onto itself.
 *
 * The work itself is [StoreVersionInstaller]; this object is the wiring plus the detachment.
 */
actual object PluginStoreVersionBridge {
    private val logger = BossLogger.forComponent("PluginStoreVersionBridge")

    /**
     * Owner of detached swaps - deliberately never cancelled, exactly like the dependency
     * installer's. The prompt runs on a window's `rememberCoroutineScope`, so closing that window
     * mid-swap would otherwise cancel between the unload and the load and leave the plugin gone with
     * nothing in its place. Coalescing per plugin id also keeps two windows from racing one id.
     */
    private val SWAP_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val DETACHED_SWAPS = KeyedDetachedJobs<String, Result<String>>(SWAP_SCOPE)

    private val installer by lazy { StoreVersionInstaller(pluginDir = { PluginStoreSetup.getPluginDir() }) }

    actual suspend fun lookup(pluginId: String): StoreVersionLookup {
        val store =
            PluginStoreSetup.remoteRepository
                ?: return StoreVersionLookup.Unavailable(
                    "The plugin store is not available. Check your connection and try again.",
                )
        val result = runCatching { store.getPlugin(pluginId) }
        val info =
            result.getOrNull()?.getOrNull()
                ?: return if (result.isFailure) {
                    StoreVersionLookup.Unavailable(
                        "Could not reach the plugin store: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                    )
                } else {
                    // A successful lookup that found nothing is the ordinary case for a plugin that
                    // was built locally and never published, so it is reported as absence, not error.
                    StoreVersionLookup.NotPublished
                }
        val version = info.version.takeIf { it.isNotBlank() } ?: return StoreVersionLookup.NotPublished
        return StoreVersionLookup.Available(
            displayName = info.displayName,
            version = version,
            sourceUrl = info.downloadUrl.ifBlank { null },
        )
    }

    actual suspend fun installStoreVersion(
        pluginId: String,
        version: String,
        sourceUrl: String?,
        manager: DynamicPluginManager,
    ): Result<String> =
        DETACHED_SWAPS.run(
            key = pluginId,
            onDetachedFailure = { error ->
                // The window closed while this ran, so nothing is left to show the failure to.
                logger.error(LogCategory.SYSTEM, "Detached store-version install failed", error = error)
            },
        ) {
            val store =
                PluginStoreSetup.remoteRepository
                    ?: return@run Result.failure(
                        IllegalStateException(
                            "The plugin store is not available. Check your connection and try again.",
                        ),
                    )
            installer.install(
                store = store,
                request =
                    StoreVersionRequest(
                        pluginId = pluginId,
                        version = version,
                        sourceUrl = sourceUrl,
                        runningJarPath = manager.getPluginInfo(pluginId)?.jarPath,
                    ),
                unload = { id -> manager.uninstallPlugin(id, force = true).map { } },
                load = { path ->
                    manager.installPlugin(path, enabled = true).map { it.state == PluginState.LOADED }
                },
            )
        }
}
