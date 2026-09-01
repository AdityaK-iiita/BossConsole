package ai.rever.boss.components.window_panel

import ai.rever.boss.plugin.tab.diff.DiffTabInfo

/**
 * Whether [tab] is the same diff view as the scope being opened: same file
 * (via [TabPaths.normalize]), same side of the index, same refs.
 *
 * Pure over [DiffTabInfo] so the reuse semantics are pinnable without a UI:
 * a staged diff and a working-tree diff of one file are different views, and
 * each gets its own tab, as in VS Code.
 *
 * A blank [filePath] with NO refs matches nothing, deliberately:
 * [TabPaths.normalize] of "" is "", so an unguarded blank query would focus
 * the first diff tab with an empty file path, whatever its scope. A blank
 * path WITH a ref is the commit/range-diff scope and matches normally.
 */
internal fun diffTabMatches(
    tab: DiffTabInfo,
    filePath: String,
    staged: Boolean,
    fromRef: String?,
    toRef: String?,
): Boolean {
    if (filePath.isBlank() && fromRef == null && toRef == null) return false
    val wanted = TabPaths.normalize(filePath)
    return TabPaths.normalize(tab.filePath) == wanted &&
        tab.staged == staged &&
        tab.fromRef == fromRef &&
        tab.toRef == toRef
}
