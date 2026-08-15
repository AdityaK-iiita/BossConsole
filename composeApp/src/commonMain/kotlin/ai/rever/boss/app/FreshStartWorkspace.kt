package ai.rever.boss.app

import ai.rever.boss.components.window_panel.SplitViewState
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.WorkspaceSettingsManager
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.requiresProject
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.window.WindowProjectState

/**
 * Whether a window that restored nothing should apply [workspace] as its starting layout.
 *
 * Two conditions, and both are load-bearing:
 * - **No project selected.** With a project, the reactive apply in
 *   `BossAppStartupEffects` already owns this and would apply the same workspace a
 *   second time.
 * - **The workspace can stand without one.** `{projectPath}` silently falls back to
 *   `~/BossProjects` (see `DefaultWorkingDirectory`), so applying the Claude Code default
 *   here would open a terminal running `claude --dangerously-skip-permissions` in an
 *   empty projects folder on the first launch of a fresh install. Browser-only needs
 *   nothing, which is why the Windows default reaches first launch and the terminal-first
 *   defaults keep waiting for a project, on every platform, exactly as before.
 */
internal fun shouldApplyOnFreshStart(
    workspace: LayoutWorkspace?,
    hasProject: Boolean,
): Boolean = workspace != null && !hasProject && !workspace.requiresProject()

/**
 * Apply the configured default workspace to a first window that restored nothing - no
 * Last Session, no project - so a fresh install comes up on its default layout rather
 * than on an empty window.
 *
 * Returns the workspace applied, or null if [shouldApplyOnFreshStart] declined. Must be
 * called before `markHandlersReady`: [applyWorkspace] clears all panels, which would
 * destroy tabs a handler had already created.
 */
internal suspend fun applyDefaultWorkspaceOnFreshStart(
    splitViewState: SplitViewState,
    windowProjectState: WindowProjectState,
): LayoutWorkspace? {
    val selectedProject = windowProjectState.selectedProject.value
    val hasProject = selectedProject.path.isNotEmpty()
    val workspace = WorkspaceSettingsManager.getDefaultWorkspace()
    if (workspace == null || !shouldApplyOnFreshStart(workspace, hasProject)) return null

    // loadWorkspace FIRST, exactly as the Last Session path does and for the same reason:
    // it sets currentWorkspace, which is what makes the fresh-install fallback timeout
    // stand down. applyWorkspace can outlast that timeout (it waits for plugin tab types
    // to register, and the timeout defaults to 1000ms), and a timeout firing mid-apply
    // would clearAllPanels over the tabs this apply is still creating and mark handlers
    // ready early - the very failure the Last Session ordering comment guards against.
    workspaceManager.loadWorkspace(workspace)

    // restoreProject = false: the workspace carries no project and there is none to
    // restore, so nothing should touch the window's project selection here.
    applyWorkspace(workspace, splitViewState, windowProjectState, restoreProject = false)
    return workspace
}
