package ai.rever.boss.components.plugin

/**
 * The JAR a reload should load: the one the plugin is running from, else the installer's recorded
 * one, else whatever is on disk under a new name, else null.
 *
 * **Every candidate is checked against the filesystem, and that is the whole point.** A reload is
 * usually triggered by an update that has already replaced the jar: the new file is version-named,
 * so it has a DIFFERENT name and the old one is deleted, which makes [loadedJarPath] reliably stale
 * in exactly the case reload matters most. Trusting it unloaded the plugin and then failed to load
 * it, leaving the plugin gone until the next restart, with only the load half logging anything.
 *
 * Order is deliberate:
 *
 *  1. [loadedJarPath] while it still exists, so an in-place hot reload (evolver copies a jar over
 *     the same path) behaves exactly as before and only the already-failing case changes.
 *  2. [persistedJarPath], the installer's record of what should be loaded now. Callers that cannot
 *     reach the persisted store pass null.
 *  3. [relocated] - re-resolve by pluginId from the directory. This exists because (2) is
 *     ORDERING-DEPENDENT: it only helps once the installer has recorded the new jar, so a reload
 *     landing between the download and that write would otherwise see two stale paths and
 *     reproduce the original symptom. `loadPersistedPlugins` re-resolves for the same reason.
 *
 * Returning null rather than guessing lets the caller keep the plugin running instead of unloading
 * it for a load that cannot work.
 *
 * Pure, with [exists] and [relocated] injected, so the decision is testable without a filesystem.
 */
internal fun resolveReloadJarPath(
    loadedJarPath: String?,
    persistedJarPath: String?,
    exists: (String) -> Boolean,
    relocated: () -> String?,
): String? =
    when {
        loadedJarPath != null && exists(loadedJarPath) -> loadedJarPath
        persistedJarPath != null && exists(persistedJarPath) -> persistedJarPath
        else -> relocated()
    }
