package ai.rever.boss.app

import ai.rever.boss.components.events.PanelEventBus
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.utils.SystemUtils

/**
 * Whether selecting a project also opens the host's project panels (Codebase and
 * Run Configurations) on its own.
 *
 * Windows starts as a plain browser - see `defaultWorkspaceIdFor` - so nothing
 * but the chosen workspace comes up there: no sidebar panel opens by itself, and
 * no plugin panel either, since Codebase and Run Configurations are the only
 * panels the host ever opens without being asked. Every panel is still one
 * sidebar click, menu item, deep link or CLI command away; only the automatic
 * open is suppressed.
 */
internal object StartupPanelPolicy {
    /** Platform-explicit form, so both branches are reachable from a test on any host. */
    fun autoOpensProjectPanelsFor(isWindows: Boolean): Boolean = !isWindows

    /** [autoOpensProjectPanelsFor] resolved against the running platform. */
    val autoOpensProjectPanels: Boolean
        get() = autoOpensProjectPanelsFor(SystemUtils.isWindows)
}

/**
 * The single place the host opens its project panels from.
 *
 * Three startup effects need this - a pending project handed over by "Open in New
 * Window", a project already selected when the window composes, and a project
 * selected later - and they must agree about the policy above, so they all route
 * through here. `ProjectPanelOpenerTest` fails if a call to
 * [PanelEventBus.openPanel] reappears in `BossAppStartupEffects.kt`.
 */
internal suspend fun openProjectPanels(
    windowId: String,
    autoOpen: Boolean = StartupPanelPolicy.autoOpensProjectPanels,
) {
    if (!autoOpen) return
    PanelEventBus.openPanel(PanelIds.CODEBASE, sourceWindowId = windowId)
    PanelEventBus.openPanel(PanelIds.RUN_CONFIGURATIONS, sourceWindowId = windowId)
}
