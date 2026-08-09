package ai.rever.boss.components.workspaces

import ai.rever.boss.utils.SystemUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * The workspace applied on a fresh install, per platform.
 *
 * Windows opens browser-only on the BOSS home page; every other platform keeps
 * the terminal + browser Claude Code layout. Only the *default* differs - the
 * full workspace list stays the same everywhere, so a Windows user can still
 * pick any layout in Settings.
 */
fun defaultWorkspaceIdFor(isWindows: Boolean): String =
    if (isWindows) {
        PredefinedWorkspaces.BROWSER_ONLY_ID
    } else {
        PredefinedWorkspaces.CLAUDE_CODE_ID
    }

/** [defaultWorkspaceIdFor] resolved against the running platform. */
fun defaultWorkspaceIdForPlatform(): String = defaultWorkspaceIdFor(SystemUtils.isWindows)

/**
 * Settings for workspace behavior.
 */
@Serializable
data class WorkspaceSettings(
    /**
     * The ID of the default workspace to apply when a project is selected.
     * Use "none" to disable auto-applying workspace.
     */
    val defaultWorkspaceId: String = defaultWorkspaceIdForPlatform(),
    /**
     * Schema version of this file, used to apply one-time migrations to installs
     * that already have a settings file written by an older build.
     *
     * The default is deliberately 0, not [CURRENT_SETTINGS_VERSION]: a missing key
     * decodes to the default, and every file written before this field existed is
     * missing it. The manager stamps the current version when it writes.
     */
    val settingsVersion: Int = 0,
) {
    companion object {
        /**
         * Bump when a migration is added to [WorkspaceSettingsMigrations.migrate].
         * 1: Windows moves from the Claude Code default to browser-only.
         */
        const val CURRENT_SETTINGS_VERSION = 1
    }
}

/**
 * One-time migrations for [WorkspaceSettings] files written by older builds.
 * Kept in commonMain (and pure) so it is directly testable.
 */
object WorkspaceSettingsMigrations {
    /**
     * Returns the settings to use, or null when the file is already current.
     *
     * A non-null result does not mean the default moved: every pre-v1 file is
     * returned with the version stamped, whether or not anything else changed.
     * The caller distinguishes the two (see `DesktopWorkspaceSettingsManager`).
     *
     * The version 0 -> 1 step rewrites a Windows install sitting on the old
     * universal default. It cannot tell that apart from a Windows user who picked
     * Claude Code deliberately - `encodeDefaults = true` wrote the same value
     * either way and there was no version field to distinguish them - so such a
     * user is moved once, deliberately. Every *other* value, including "none", is
     * preserved, and the step runs at most once because the migrated file records
     * the new version.
     */
    fun migrate(
        loaded: WorkspaceSettings,
        isWindows: Boolean = SystemUtils.isWindows,
    ): WorkspaceSettings? {
        if (loaded.settingsVersion >= WorkspaceSettings.CURRENT_SETTINGS_VERSION) return null

        return if (isWindows && loaded.defaultWorkspaceId == PredefinedWorkspaces.CLAUDE_CODE_ID) {
            loaded.copy(
                defaultWorkspaceId = PredefinedWorkspaces.BROWSER_ONLY_ID,
                settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION,
            )
        } else {
            loaded.copy(settingsVersion = WorkspaceSettings.CURRENT_SETTINGS_VERSION)
        }
    }
}

/**
 * Manager for workspace settings.
 * Handles persistence and retrieval of workspace configuration.
 */
expect object WorkspaceSettingsManager {
    /**
     * Current workspace settings as a reactive flow.
     */
    val currentSettings: StateFlow<WorkspaceSettings>

    /**
     * Save current settings to persistent storage.
     */
    suspend fun saveSettings()

    /**
     * Update settings and persist.
     */
    suspend fun updateSettings(settings: WorkspaceSettings)

    /**
     * Update the default workspace ID.
     */
    suspend fun setDefaultWorkspaceId(workspaceId: String)

    /**
     * Get the default workspace to apply, or null if disabled.
     */
    fun getDefaultWorkspace(): LayoutWorkspace?
}
