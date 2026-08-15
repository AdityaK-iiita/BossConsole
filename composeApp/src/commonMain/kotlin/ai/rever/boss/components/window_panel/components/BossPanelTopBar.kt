package ai.rever.boss.components.window_panel.components

import ai.rever.boss.components.buttons.BossActionButton
import ai.rever.boss.components.overlays.ContextMenu
import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.plugin.AvailablePluginUpdate
import ai.rever.boss.components.plugin.PluginBuildInfo
import ai.rever.boss.components.plugin.PluginBuildTag
import ai.rever.boss.components.plugin.registries.PanelMenuRegistryImpl
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Upgrade
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val UpdateBadgeColor: Color get() = BossThemeColors.SuccessColor

@Composable
fun BossPanelTopBar(
    title: String?,
    isHovered: Boolean,
    onReloadPlugin: (() -> Unit)? = null,
    onOpenAsTab: (() -> Unit)? = null,
    onCheckForUpdates: (() -> Unit)? = null,
    onOpenEvolver: (() -> Unit)? = null,
    onReportIssue: (() -> Unit)? = null,
    onUninstallPlugin: (() -> Unit)? = null,
    uninstallEnabled: Boolean = true,
    onMinimize: () -> Unit,
    updateAvailable: AvailablePluginUpdate? = null,
    onUpdateClick: (() -> Unit)? = null,
    buildInfo: PluginBuildInfo? = null,
    onBuildTagClick: (() -> Unit)? = null,
    panelId: PanelId? = null,
    windowId: String? = null,
    dragModifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    // Plugin-contributed menu items for this panel (PanelMenuRegistry). The
    // registry map and RBAC snapshot trigger a re-query, so items track
    // plugin lifecycle and role changes. Contributions change their item set
    // by re-registering (items() must stay cheap — see PanelMenuContribution).
    val contributions by PanelMenuRegistryImpl.contributions.collectAsState()
    val access by PanelMenuRegistryImpl.access.collectAsState()
    val pluginEntries =
        if (panelId != null) {
            remember(panelId, contributions, access) {
                PanelMenuRegistryImpl.itemsFor(panelId)
            }
        } else {
            emptyList()
        }

    // One menu definition, shared by the "…" kebab and the right-click context menu,
    // so both offer identical options. Plugin items render between the
    // built-ins and Minimize.
    val menuItems =
        buildList {
            // Which build is running, first and clickable, for a plugin that is not on the released
            // version. The version has to be the item's TEXT rather than a badge widget: with no
            // trailing icon this menu is native-representable, so on macOS it renders as a real
            // NSMenu, whose items carry a label, an enabled flag and a rasterised leading icon -
            // but nothing that is a widget, which is what a badge would need.
            //
            // Both rows are gated on the action existing, not merely on the build being tagged:
            // onBuildTagClick is null whenever the panel has no resolvable window (LocalWindowId
            // defaults to null), and a row named as an imperative that silently does nothing is a
            // worse failure than no row at all. The tag itself is inert in exactly that case.
            val installStoreVersion = onBuildTagClick
            val taggedBuild = buildInfo?.takeIf { it.isTagged }
            if (taggedBuild != null && installStoreVersion != null) {
                add(
                    ContextMenuItem(
                        text = "Version ${taggedBuild.displayVersion}",
                        icon = Icons.Outlined.Info,
                        onClick = installStoreVersion,
                    ),
                )
                // The way back to the released build, named as the action it is. The version row
                // above already carries it, but that row reads as a statement of fact, so the only
                // discoverable route was clicking the tag - and the tag is a 9sp pill that is the
                // first thing to run out of room once the panel narrows.
                add(
                    ContextMenuItem(
                        text = "Install Store Version",
                        icon = Icons.Outlined.CloudDownload,
                        onClick = installStoreVersion,
                    ),
                )
                add(ContextMenuItem(isDivider = true))
            }
            // "Reload Panel" is the user-facing name for what is really a reload of the owning
            // plugin: the jar is unloaded and re-read, and every window's slots for it are reset.
            // Named for the thing the user is pointing at, since this menu belongs to one panel.
            onReloadPlugin?.let { cb ->
                add(ContextMenuItem(text = "Reload Panel", icon = Icons.Outlined.Refresh, onClick = cb))
            }
            onCheckForUpdates?.let { cb -> add(ContextMenuItem(text = "Check for Updates", icon = Icons.Outlined.Upgrade, onClick = cb)) }
            onOpenEvolver?.let { cb -> add(ContextMenuItem(text = "Open Evolver", icon = Icons.Outlined.MonitorHeart, onClick = cb)) }
            onReportIssue?.let { cb -> add(ContextMenuItem(text = "Report Issue", icon = Icons.Outlined.BugReport, onClick = cb)) }
            onOpenAsTab?.let { cb -> add(ContextMenuItem(text = "Open as Tab", icon = Icons.Outlined.Tab, onClick = cb)) }
            // Shown for every plugin panel, disabled for the ones the manager refuses to unload
            // (system plugins), so the action's absence is never mistaken for the feature missing.
            onUninstallPlugin?.let { cb ->
                add(
                    ContextMenuItem(
                        text = "Uninstall Plugin",
                        icon = Icons.Outlined.DeleteOutline,
                        enabled = uninstallEnabled,
                        onClick = cb,
                    ),
                )
            }
            if (pluginEntries.isNotEmpty() && panelId != null) {
                add(ContextMenuItem(isDivider = true))
                for ((contribution, item) in pluginEntries) {
                    if (!item.enabled) continue
                    add(
                        ContextMenuItem(text = item.label, icon = item.icon, onClick = {
                            PanelMenuRegistryImpl.onItemClick(contribution, panelId, item.id, windowId)
                        }),
                    )
                }
            }
            add(ContextMenuItem(text = "Minimize", icon = Icons.Outlined.Remove, onClick = onMinimize))
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(BossTheme.colors.raised)
                .then(dragModifier)
                .contextMenu(items = menuItems),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(8.dp))

        // Title and tag are one group, and the group takes all the free space. This has to be a
        // nested Row rather than a weighted title beside a weighted spacer: two weights in one Row
        // split the free space 1:1, and because the title is fill = false, the half it did not use
        // was laid out AFTER the trailing controls - so Minimize and the kebab sat short of the
        // right edge by half the title's unused width, drifting further in the shorter the title.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title ?: "",
                color = BossThemeColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        // Give way rather than grow: an unbounded title in a narrow side panel would push
                        // the build tag - the entire signal - off the end of the row. fill = false so a
                        // short title still hugs its text and the tag sits next to it, not at the edge.
                        .weight(1f, fill = false),
            )

            // Next to the name, not out at the edge: the tag qualifies which build of this panel you
            // are looking at, so it belongs with the thing it qualifies. Not hover-gated - a panel
            // running unreleased code should say so whether or not the pointer is over it.
            if (buildInfo?.isTagged == true) {
                Spacer(modifier = Modifier.width(6.dp))
                PluginBuildTag(
                    info = buildInfo,
                    onClick = onBuildTagClick,
                )
            }
        }

        // "Update available" badge — always visible (not hover-gated) when a compatible update
        // exists for this plugin. Clicking it prompts to update.
        if (updateAvailable != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onUpdateClick?.invoke() }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Upgrade,
                    contentDescription = "Update available: v${updateAvailable.currentVersion} → v${updateAvailable.newVersion}",
                    tint = UpdateBadgeColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Update",
                    color = UpdateBadgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // State for dropdown menu (moved outside AnimatedVisibility to be accessible in condition)
        var showMenu by remember { mutableStateOf(false) }
        val buttonHeightRef = remember { intArrayOf(0) }

        AnimatedVisibility(
            visible = isHovered || showMenu, // Keep visible while menu is open
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(modifier = Modifier.padding(end = 2.dp)) {
                content?.invoke()

                // More button — opens the same menu as right-click
                Box(
                    modifier =
                        Modifier.onGloballyPositioned { coordinates ->
                            buttonHeightRef[0] = coordinates.size.height
                        },
                ) {
                    BossActionButton(
                        imageVector = Icons.Outlined.MoreVert,
                        text = "More",
                        color = BossThemeColors.TextPrimary,
                        onClick = { showMenu = true },
                    )

                    if (showMenu) {
                        ContextMenu(
                            items = menuItems,
                            offset = IntOffset(0, buttonHeightRef[0]),
                            onDismissRequest = { showMenu = false },
                        )
                    }
                }

                BossActionButton(
                    imageVector = Icons.Outlined.Remove,
                    text = "Minimize",
                    color = BossThemeColors.TextPrimary,
                    onClick = onMinimize,
                )
            }
        }
    }
}
