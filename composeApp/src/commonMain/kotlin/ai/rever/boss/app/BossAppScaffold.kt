package ai.rever.boss.app

import ai.rever.boss.components.bars.horizontal.BossBottomBar
import ai.rever.boss.components.bars.horizontal.BossTitleBar
import ai.rever.boss.components.bars.horizontal.BossTopBar
import ai.rever.boss.components.bars.isBarVisible
import ai.rever.boss.components.bars.vertical.BossLeftSideBar
import ai.rever.boss.components.bars.vertical.BossRightSideBar
import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.components.home.LocalPanelRegistry
import ai.rever.boss.components.home.LocalPluginStates
import ai.rever.boss.components.home.LocalRegistryAccess
import ai.rever.boss.components.home.LocalTabRegistry
import ai.rever.boss.components.overlays.DraggingItemOverlay
import ai.rever.boss.components.overlays.OverlayCorner
import ai.rever.boss.components.overlays.TabDraggingOverlay
import ai.rever.boss.components.plugin.LocalPanelPluginIdResolver
import ai.rever.boss.components.plugin.LocalPluginUninstallable
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalSplitViewState
import ai.rever.boss.components.plugin.panels.left_bottom.TopOfMind.LocalWorkspaceManager
import ai.rever.boss.components.plugin.providers.TopOfMindDataProvider
import ai.rever.boss.components.plugin.providers.WindowIdProviderImpl
import ai.rever.boss.components.plugin.providers.WindowProjectStateProviderImpl
import ai.rever.boss.components.plugin.registries.HomeToolAccess
import ai.rever.boss.components.window_panel.BossWindow
import ai.rever.boss.components.window_panel.components.main_window_panels.TabCycleOverlayHost
import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.components.workspaces.applyWorkspace
import ai.rever.boss.components.workspaces.extractCurrentWorkspace
import ai.rever.boss.components.workspaces.workspaceManager
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.handleTabDropResult
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.layout.ChromeBudgetReadout
import ai.rever.boss.layout.TrafficLightInset
import ai.rever.boss.layout.asDrawn
import ai.rever.boss.layout.bannerStartInset
import ai.rever.boss.layout.barStartInset
import ai.rever.boss.layout.columnInset
import ai.rever.boss.layout.macTrafficLightInset
import ai.rever.boss.layout.needsTitleRow
import ai.rever.boss.plugin.api.LocalBookmarkDataProvider
import ai.rever.boss.plugin.api.LocalProjectPath
import ai.rever.boss.plugin.api.LocalSplitViewOperations
import ai.rever.boss.plugin.api.LocalWindowIdProvider
import ai.rever.boss.plugin.api.LocalWindowProjectStateProvider
import ai.rever.boss.plugin.api.LocalWorkspaceDataProvider
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.sandbox.notification.PluginToastHost
import ai.rever.boss.plugin.sandbox.notification.PluginToastState
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.bookmarks.BookmarkAPIAccess
import ai.rever.boss.updater.UpdateAvailableDialog
import ai.rever.boss.updater.UpdateBanner
import ai.rever.boss.updater.UpdateState
import ai.rever.boss.updater.drawsBanner
import ai.rever.boss.updater.rememberUpdateDialogOwnership
import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.window.LocalWindowGitState
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.LocalWindowProjectState
import ai.rever.boss.window.LocalWindowRunnerState
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Hard upper bound on the toast overlay: its size before measurement, and the ceiling every later
 * measurement is taken against.
 *
 * A bound, not an estimate. The overlay measures its content against this rather than against its
 * own current size, so content that would exceed it is CLIPPED rather than merely starting small.
 * Width is `PluginToastHost`'s own `widthIn(max = 400.dp)` plus its 16.dp padding on each side;
 * height comfortably clears `PluginToastState`'s three-toast maximum. It is also the region the
 * overlay swallows clicks in until measurement lands, so it is kept no larger than it needs to be.
 */
private val TOAST_OVERLAY_INITIAL_SIZE = DpSize(432.dp, 600.dp)

/**
 * Plugin toasts, layered above the heavyweight browser surface.
 *
 * Drawn in the scaffold they land BEHIND that surface, so a toast raised while a browser tab is
 * showing is invisible. `OverlayCorner` rather than `OverlayHud` because a toast stays up for
 * seconds: a parent-sized HUD swallows every click beneath it, which is fine for a switcher held
 * for a moment and not for this.
 *
 * **Two guards, and both are load-bearing**, because the overlay window is always-on-top and a
 * non-focusable AWT window still eats mouse events (the JVM has no portable click-through). So
 * wherever it sits is a dead region, of this app and of whatever is in front of it.
 *
 *  - **Empty.** `PluginToastHost` composes its padded `Column` unconditionally and
 *    `DefaultPlugin.pluginToastState` is never null, so without this every loaded plugin would
 *    hold a window open for the whole session.
 *  - **Window focus.** Toast lifetime is NOT bounded by a timer: `ToastDuration.INDEFINITE` skips
 *    auto-dismissal entirely, it is part of the plugin IPC surface, and the host itself raises one
 *    (`BossPluginNotificationService.notifyPluginDisabled`, an ERROR toast with a "Re-enable"
 *    action - precisely the kind a user leaves sitting while they go elsewhere). Escaping the
 *    scene is only worth anything while the user is looking at this window, so an unfocused window
 *    draws toasts in place, exactly as before this overlay existed.
 *
 *    This guard used to carry a second justification: it also stopped `HeavyweightCorner`'s
 *    frame-clock loop, which would otherwise have run for as long as the toast. That loop is gone -
 *    the corner renderer is event-driven now - so the reason is gone with it. **The guard is not.**
 *    The dead click region over another application is on its own sufficient, and it is the reason
 *    that was always doing the work.
 *
 * Guarding on content matches what `TabCycleOverlayHost` and both drag ghosts already do.
 *
 * Extracted from the scaffold so both guards can be tested directly; see `ToastOverlayTest`.
 */
@Composable
internal fun BoxScope.ToastOverlay(toastState: PluginToastState) {
    val toasts by toastState.toasts.collectAsState()
    if (toasts.isEmpty()) return

    if (!LocalWindowInfo.current.isWindowFocused) {
        PluginToastHost(toastState = toastState, modifier = Modifier.align(Alignment.TopEnd))
        return
    }
    OverlayCorner(
        alignment = Alignment.TopEnd,
        initialSize = TOAST_OVERLAY_INITIAL_SIZE,
    ) {
        PluginToastHost(toastState = toastState)
    }
}

/**
 * Provides every CompositionLocal that plugins and host UI below BossApp read:
 * window identity, split view state/operations, workspace + bookmark data
 * providers, per-window project/runner/git state.
 */
@Composable
internal fun BossAppCompositionLocals(
    state: BossAppState,
    content: @Composable () -> Unit,
) {
    val selectedProject by state.windowProjectState.selectedProject.collectAsState()

    // Bookmark data provider is provided by the bookmarks plugin via registerPluginAPI()
    // Get it from BookmarkAPIAccess which queries the plugin system
    val bookmarkDataProvider = BookmarkAPIAccess.getProvider()

    // Create window provider implementations for plugins
    val windowIdProvider = WindowIdProviderImpl(state.windowId)
    val windowProjectStateProvider = WindowProjectStateProviderImpl(state.windowProjectState)

    // For the home screen's tool grid. Both are read reactively so a plugin loading, unloading,
    // or the user's role changing re-derives the grid without a relaunch.
    // The flow, not a collected value: collecting here would invalidate this scaffold on every
    // plugin state transition. `rememberHomeTools` collects it, so only the home screen recomposes.
    val pluginStates = state.currentDefaultPlugin?.dynamicPluginManager?.pluginStates
    val registryAccess by HomeToolAccess.access.collectAsState()

    CompositionLocalProvider(
        LocalWindowId provides state.windowId,
        LocalPanelPluginIdResolver provides { panelId ->
            state.currentDefaultPlugin
                ?.dynamicPluginManager
                ?.getRegistrationTracker()
                ?.getPluginIdForPanel(panelId)
        },
        LocalPluginUninstallable provides { pluginId ->
            state.currentDefaultPlugin
                ?.dynamicPluginManager
                ?.getPluginInfo(pluginId)
                ?.manifest
                ?.let { !it.systemPlugin && it.canUnload } ?: false
        },
        LocalSplitViewState provides state.splitViewState,
        LocalSplitViewOperations provides state.splitViewOperations,
        LocalWorkspaceManager provides workspaceManager,
        LocalWorkspaceDataProvider provides state.workspaceDataProvider,
        LocalBookmarkDataProvider provides bookmarkDataProvider,
        LocalProjectPath provides selectedProject.path,
        LocalWindowProjectState provides state.windowProjectState,
        LocalWindowRunnerState provides state.windowRunnerState,
        LocalWindowGitState provides state.windowGitState,
        LocalWindowIdProvider provides windowIdProvider,
        LocalWindowProjectStateProvider provides windowProjectStateProvider,
        // For the home screen's tool grid, which derives itself from what plugins registered.
        // Locals rather than parameters because it also renders inside a browser tab showing
        // about:blank, where the caller is a plugin with no access to host state - see
        // HomeRegistryLocals.
        LocalTabRegistry provides state.tabRegistry,
        LocalPanelRegistry provides state.panelRegistry,
        LocalPluginStates provides pluginStates,
        LocalRegistryAccess provides registryAccess,
    ) {
        // Initialize TopOfMind data provider for this window
        DisposableEffect(state.splitViewState, workspaceManager, state.windowId) {
            TopOfMindDataProvider.initialize(
                state.splitViewState,
                workspaceManager,
                state.windowId,
            )
            onDispose {
                TopOfMindDataProvider.clear()
            }
        }

        content()
    }
}

/**
 * The window chrome: title bar, update banner/dialog, top bar, sidebars, the
 * split-view main content, bottom bar, focus-mode hover strips, and the drag /
 * toast / tab-cycle overlays. Bars hide and hover-reveal in focus mode.
 */
@Composable
internal fun BossAppScaffold(
    state: BossAppState,
    reveal: FocusModeRevealState,
    focusModeSettings: FocusModeSettings,
    revealOffsetDp: Dp,
    appearance: WindowAppearanceSettings,
    onToggleMaximize: (() -> Unit)?,
) {
    val coroutineScope = state.coroutineScope
    val splitViewState = state.splitViewState
    val selectedProject by state.windowProjectState.selectedProject.collectAsState()

    // The content area's distance from the window's end and bottom edges, i.e. the right sidebar's
    // width plus the bottom bar's height, whatever they currently are. Measured rather than derived
    // from the reveal flags because both animate, and the quick actions have to follow them.
    //
    // Written from onGloballyPositioned, which BossActionButton deliberately avoids ("non-observable
    // holders: avoid triggering remeasure during the layout phase"). Safe here, and the difference
    // is worth stating: this value feeds only the overlay WINDOW's placement, never the layout of
    // the Box that reports it, so the write cannot feed back into its own measurement. It is passed
    // to the cluster as a lambda so the read lands in that composable's restart scope rather than
    // this one - see FocusModeQuickActions.
    var contentInset by remember { mutableStateOf(DpSize.Zero) }
    val density = LocalDensity.current.density

    // What is actually drawn, which is what these two rules are about - a bar focus mode is
    // clearing is not on screen however the preference reads. See asDrawn.
    val drawn = appearance.asDrawn(focusModeSettings)

    // Whether the hover-revealed bar is up, reported by SplitViewPanel. It decides where the
    // host's actions render while the bar is collapsed - see the placement below.
    var drawerVisible by remember { mutableStateOf(false) }

    // Whether the bar in the layout is the RAIL, reported by SplitViewPanel once it has measured.
    //
    // Seeded from the preference so the first frame has an answer, then corrected within a frame
    // by the thing that actually draws the bar. Asking the preference and stopping there is what
    // sent the host's actions to a foot that was not being drawn: a bar also rails itself when the
    // window is too narrow for a full one, and nothing in the settings says so.
    var barRailed by remember { mutableStateOf(appearance.tabBarCollapsed) }

    // Where Settings / Search / Sign Out go while focus mode holds the top bar that owns them.
    // One decision, two mutually exclusive renderings: the bottom of the right rail when that rail
    // is on screen, a floating corner cluster when it is not. Read once here so the two call sites
    // below cannot disagree about it and briefly show both.
    val quickActionsPlacement =
        focusQuickActionsPlacement(
            settings = focusModeSettings,
            topBarHidden = !appearance.showTopBar,
            rightStripHidden = !appearance.showRightStrip,
            showTopBar = reveal.showTopBar,
            // Not merely "the bar is on the left". A COLLAPSED bar draws its rail and nothing
            // else, so its foot does not exist and these four would render nowhere - they float
            // instead. Hovering the rail opens the drawer, which IS a foot, so they go back into
            // it for as long as it is up.
            //
            // The cost, stated because the neighbouring KDoc warns against exactly this: the
            // floating overlay is a native always-on-top window, so it is torn down and rebuilt on
            // each hover reveal rather than sitting there. Accepted deliberately - the alternative
            // is a cluster in the corner while a bar with room for it is open on the left.
            verticalTabBar =
                verticalBarHasFoot(
                    tabBarOnLeft = appearance.tabBarPosition == TabBarPosition.LEFT,
                    barCollapsed = barRailed,
                    drawerVisible = drawerVisible,
                ),
        )

    // Where the way into the plugins goes, when a strip that would normally hold their icons is
    // switched off. Decided here for the same reason the line above is: three call sites read it,
    // and two of them showing a launcher at once is worse than neither.
    val launcherPlacement =
        toolLauncherPlacement(
            leftStripHidden = !drawn.showLeftStrip,
            rightStripHidden = !drawn.showRightStrip,
        )

    // Non-null only in the HOST_ACTIONS case, so it can be handed to all three hosts of the
    // Settings / Search / Sign Out group unconditionally and render in whichever one is drawing.
    // Which column keeps clear of the macOS traffic lights, now that the title row no longer
    // exists to hold them. Decided once, read by the three places that could carry the inset.
    // Read above the decision below rather than beside the banner, because whether a banner is up
    // is an INPUT to that decision: while one is drawn it is the topmost chrome, so it takes the
    // clearance and nothing beneath it does.
    //
    // The BIT, not the state. UpdateState.Downloading carries a progress float that emits
    // continuously, and collecting the state itself here would invalidate this scaffold - parent
    // of the bars, the strips and the content - on every tick of a download, to answer a question
    // whose answer does not change. UpdateBanner still collects the full state for itself, inside
    // the Column, where a progress tick recomposes the banner and nothing else.
    val updateHandle = state.updateHandle
    val bannerVisible by remember(updateHandle) {
        updateHandle.updateState.map { it.drawsBanner() }.distinctUntilChanged()
    }.collectAsState(initial = false)

    val trafficLights =
        macTrafficLightInset(
            appearance = drawn,
            isMacOs = SystemUtils.isMacOS,
            barCollapsed = barRailed,
            bannerVisible = bannerVisible,
            // The density's width, not the 36dp floor: Comfortable draws 40dp rails, and
            // measuring the corner with the floor gave away a case that fits.
            stripWidth = BossChrome.dimens.stripWidth,
        )

    val openTools = { state.showToolLauncherDialog = true }

    // Takes its hint direction and size from whichever host draws it, rather than baking in one
    // set here: the top bar hints downwards, and the bar's foot and the floating cluster both sit
    // on a bottom edge and hint up. One baked-in `bottom` put the hint off the window in two of
    // the three, and a baked-in size made it the odd button out in the third.
    val hostToolLauncher: (@Composable (Panel, Modifier) -> Unit)? =
        if (launcherPlacement == ToolLauncherPlacement.HOST_ACTIONS) {
            { hintDirection, modifier ->
                ToolLauncherButton(
                    onClick = openTools,
                    hintDirection = hintDirection,
                    isSelected = state.showToolLauncherDialog,
                    modifier = modifier,
                )
            }
        } else {
            null
        }

    // Remembered, not rebuilt each pass, for the allocations and for a stable reserve - NOT to buy
    // a skip. `kotlin.collections.List` is unstable to Compose's stability inference, so taking one
    // as a parameter makes BossRightSideBar non-skippable whatever the argument identity: it was
    // skippable before this feature (no parameters, @Stable receiver) and is not now, and
    // computeSlotIconLimits re-runs with it. Making it skippable again would take an @Immutable
    // holder around the two parameters, which is not worth it for a 40dp rail. The lambdas capture
    // `state`, a single stable instance for the life of this window.
    val quickActionsRail =
        remember(quickActionsPlacement, state) {
            focusQuickActionsRail(
                placement = quickActionsPlacement,
                onShowSettings = { state.settingsWindow.open() },
                onShowSearch = { state.showGlobalSearchDialog = true },
                onSignOut = { state.showLogoutDialog = true },
            )
        }

    // Renders nothing; reports what the chrome costs the page when BOSS_CHROME_BUDGET is set.
    // Here rather than inside a bar so it still reports with every bar switched off.
    //
    // The metrics are read once here and handed to both the readout and the bars, so a provider
    // installed anywhere above this line reaches them together. Resolving them separately inside the
    // readout is what would let it report Comfortable while the bars drew Compact.
    val chromeDimens = BossChrome.dimens
    ChromeBudgetReadout(
        windowId = state.windowId,
        appearance = appearance,
        focusMode = focusModeSettings,
        dimens = chromeDimens,
    )

    with(state.draggablePanelComponent) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .focusRequester(state.focusRequester)
                    .focusable(),
        ) {
            // Use Box to allow overlaying the drag ghost
            Column(modifier = Modifier.fillMaxSize()) {
                // Title bar - conditionally shown based on settings
                // Default: hidden on Linux/Windows, shown on macOS
                // Also drawn when the traffic lights have nowhere else to go: with no left strip
                // and the tab bar across the top, the only thing under them is the content, and a
                // full-width reserve costs no more than padding the content would. See
                // TrafficLightInset.CONTENT.
                if (trafficLights.needsTitleRow(appearance.showTitleBar)) {
                    BossTitleBar(
                        onToggleMaximize = onToggleMaximize,
                    )
                }

                // Update banner - always visible (even in focus mode)
                val updateState by updateHandle.updateState.collectAsState()
                // Every action runs on the manager's scope, never this window's
                // rememberCoroutineScope(): that scope dies with the composition, so
                // closing the window mid-install used to cancel the install (leaving
                // UpdateState on Installing) and could drop a persisted dismissal.
                UpdateBanner(
                    updateState = updateState,
                    // Non-zero only when this banner is the chrome holding the lights, in which
                    // case the bars below it have already given up their own clearance.
                    startInset = trafficLights.bannerStartInset(),
                    onCheckForUpdates = {
                        // Manual retry: bypass per-version dismissal
                        updateHandle.checkForUpdatesInBackground(force = true)
                    },
                    onDownloadUpdate = { updateInfo ->
                        updateHandle.downloadUpdateInBackground(updateInfo)
                    },
                    onInstallUpdate = { downloadPath ->
                        updateHandle.installUpdateInBackground(downloadPath)
                    },
                    onDismiss = {
                        val currentState = updateState
                        if (currentState is UpdateState.UpdateAvailable) {
                            // Persist dismissal so this version doesn't re-prompt
                            updateHandle.dismissVersionInBackground(currentState.updateInfo.latestVersion)
                        } else {
                            updateHandle.resetState()
                        }
                    },
                )

                // Update dialog - dismissible prompt for a new app version,
                // rendered by exactly one window (ownership is reactive)
                val showUpdateDialog by updateHandle.showUpdateDialog.collectAsState()
                val isUpdateDialogOwner = rememberUpdateDialogOwnership(state.windowId)
                val updateStateForDialog = updateState
                if (showUpdateDialog && isUpdateDialogOwner && updateStateForDialog is UpdateState.UpdateAvailable) {
                    UpdateAvailableDialog(
                        updateInfo = updateStateForDialog.updateInfo,
                        onUpdateNow = {
                            updateHandle.dismissDialogOnly()
                            // Manager-owned scope: the dialog lives only in the owner
                            // window — closing it must not cancel the download
                            updateHandle.downloadUpdateInBackground(updateStateForDialog.updateInfo)
                        },
                        onLater = {
                            updateHandle.dismissVersionInBackground(updateStateForDialog.updateInfo.latestVersion)
                        },
                    )
                }

                // Tell the manager every workspace THIS window is running, so the workspace menu
                // can mark them all rather than only the one on screen. Read from the window's
                // own split state, which is where the live ones actually are - the manager's
                // currentWorkspace is one value shared by every window and knows about neither
                // the others' nor the ones running behind this one.
                val liveWorkspaceIds = splitViewState.liveWorkspaceIds
                DisposableEffect(state.windowId, liveWorkspaceIds) {
                    workspaceManager.setWindowWorkspaces(state.windowId, liveWorkspaceIds)
                    onDispose { workspaceManager.releaseWindow(state.windowId) }
                }

                // Switching workspaces, in one place: the top bar offers it and so does the
                // vertical tab bar's foot when the top bar is off. Two copies of "preserve, load,
                // apply" is two places for that order to drift, and the order is the whole of why
                // switching away and back does not lose a layout. See WorkspaceSwitch.kt for why
                // a switch is two decisions rather than one.
                val workspaceSwitch = rememberWorkspaceSwitch(state, splitViewState)
                val applyWorkspaceAndPreserve = workspaceSwitch.request
                WorkspaceSwitchPrompt(state, workspaceSwitch)

                // Top bar - hidden in focus mode with smooth expand/shrink animation, and switched
                // off outright by the appearance preference. Both have to agree for a bar to show:
                // focus mode is the transient posture, the preference is the standing choice.
                AnimatedVisibility(
                    visible = appearance.showTopBar && reveal.showTopBar,
                    enter =
                        expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(durationMillis = 250),
                        ),
                    exit =
                        shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(durationMillis = 250),
                        ),
                ) {
                    Box(
                        modifier = Modifier.hoverable(interactionSource = reveal.topBarInteractionSource),
                    ) {
                        BossTopBar(
                            workspaceManager = workspaceManager,
                            onApplyWorkspace = applyWorkspaceAndPreserve,
                            getCurrentWorkspace = {
                                extractCurrentWorkspace(splitViewState, selectedProject.path)
                            },
                            onShowTopOfMind = {
                                state.showTopOfMindDialog = true
                            },
                            onShowSettings = {
                                state.settingsWindow.open()
                            },
                            // Only non-null when neither icon strip is on screen, so the top bar
                            // grows a tools button exactly in the configuration where nothing
                            // else can hold one.
                            toolLauncher = hostToolLauncher,
                            // Clearance for the macOS traffic lights, which are drawn over this
                            // bar's start when there is no title row above it.
                            startInset = trafficLights.barStartInset(),
                            onShowSearch = {
                                state.showGlobalSearchDialog = true
                            },
                            onSignOut = {
                                state.showLogoutDialog = true
                            },
                            onNewProject = {
                                state.showNewProjectDialog = true
                            },
                            onCloneProject = {
                                state.showCloneProjectDialog = true
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                ) {
                    // Left sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = appearance.showLeftStrip && reveal.showLeftSidebar,
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.Start,
                                animationSpec = tween(durationMillis = 250),
                            ),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.Start,
                                animationSpec = tween(durationMillis = 250),
                            ),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .hoverable(interactionSource = reveal.leftSidebarInteractionSource)
                                    // Painted before padded - see WindowBarRow for what an
                                    // unpainted inset shows through to.
                                    .background(BossTheme.colors.raised)
                                    .padding(top = trafficLights.columnInset()),
                        ) {
                            BossLeftSideBar(
                                toolsOpen = state.showToolLauncherDialog,
                                onOpenTools =
                                    openTools.takeIf {
                                        launcherPlacement == ToolLauncherPlacement.LEFT_STRIP
                                    },
                            )
                        }
                    }

                    // Main content area - always visible (contains tabs)
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .reportContentInset(density) { contentInset = it },
                    ) {
                        BossWindow(
                            modifier = Modifier.fillMaxSize(),
                            tabsComponent = state.tabsComponent,
                            panelComponentStore = state.panelComponentStore,
                            splitViewState = splitViewState,
                            tabDragComponent = state.tabDragComponent,
                            onTabDropResult = { result ->
                                handleTabDropResult(result, splitViewState)
                            },
                            // Composed HERE rather than inside the bar: this is where the
                            // workspace manager and the window's project dialog already are, and
                            // a tab bar has no business knowing about either. It reaches the bar
                            // as a slot. See VerticalBarWindowControls.
                            // Settings / Search / Sign Out (and the launcher, when it joins them)
                            // at the very foot of the bar, under the split map - the placement
                            // that displaces the floating cluster wherever this bar is on screen.
                            // The tab bar is the leftmost column when no strip is on, so its top
                            // is what the lights would land on.
                            verticalBarTopInset = trafficLights.columnInset(),
                            onDrawerVisibleChange = { visible -> drawerVisible = visible },
                            onBarRailedChange = { railed -> barRailed = railed },
                            verticalBarBelowMap = {
                                VerticalBarHostActions(
                                    actions =
                                        focusQuickActionsFooter(
                                            placement = quickActionsPlacement,
                                            onShowSettings = { state.settingsWindow.open() },
                                            onShowSearch = { state.showGlobalSearchDialog = true },
                                            onSignOut = { state.showLogoutDialog = true },
                                            toolLauncher = hostToolLauncher,
                                        ),
                                )
                            },
                            verticalBarFooter = {
                                VerticalBarWindowControls(
                                    // `drawn`, not the preference: a top bar focus mode has
                                    // cleared is not on screen, and the project and workspace
                                    // pickers live nowhere else.
                                    topBarHidden = !drawn.showTopBar,
                                    project = selectedProject,
                                    onOpenProject = { state.showProjectDialog = true },
                                    workspaceManager = workspaceManager,
                                    onApplyWorkspace = applyWorkspaceAndPreserve,
                                    getCurrentWorkspace = {
                                        extractCurrentWorkspace(splitViewState, selectedProject.path)
                                    },
                                    onShowTopOfMind = { state.showTopOfMindDialog = true },
                                )
                            },
                        )

                        // Settings / Search / Sign Out, which the top bar otherwise owns outright.
                        // The FLOATING half of the placement: reached only when focus mode has
                        // cleared the right sidebar too, since with the rail up they go in it.
                        // Composed inside the content area so the lightweight path aligns where it
                        // draws; contentInset is what makes the heavyweight path agree.
                        //
                        // Deliberately NOT also gated on "is a dialog open". An earlier revision
                        // listed the two dialogs this cluster opens, which would have had to grow
                        // every time anyone added a dialog to BossAppDialogs and would have gone
                        // stale silently. It is not needed on either path: a lightweight BossDialog
                        // falls back to Compose's own Dialog, a real platform window ABOVE the
                        // composition, so an in-place cluster is underneath it and never over it;
                        // and a heavyweight modal takes window focus, which drops this to the same
                        // in-place path by the focus guard in FocusModeQuickActions.
                        //
                        // `hides(TOP)` is not redundant with `!showTopBar`, it is what keeps this
                        // off the launch path entirely. `FocusModeEdgeRevealState.shown` starts
                        // false and is only turned back on by a LaunchedEffect, so on the FIRST
                        // composition of every window `!showTopBar` is true whether or not focus
                        // mode is even enabled. Without this the heavyweight path would create and
                        // immediately dispose a native always-on-top window on every window open,
                        // flash it in the corner for users who never turn focus mode on, and call
                        // contentPaneBounds before the pane is reliably showing - which can burn
                        // the one-per-session warning flag that exists to make a REAL failure
                        // visible. Gating on the setting is also just the honest condition: the
                        // cluster exists because focus mode clears the top bar.
                        FocusModeQuickActions(
                            visible = quickActionsPlacement == FocusQuickActionsPlacement.FLOATING,
                            inset = { contentInset },
                            onShowSettings = { state.settingsWindow.open() },
                            onShowSearch = { state.showGlobalSearchDialog = true },
                            onSignOut = { state.showLogoutDialog = true },
                            toolLauncher = hostToolLauncher,
                        )
                    }

                    // Right sidebar - hidden in focus mode with smooth expand/shrink animation
                    AnimatedVisibility(
                        visible = appearance.showRightStrip && reveal.showRightSidebar,
                        enter =
                            expandHorizontally(
                                expandFrom = Alignment.End,
                                animationSpec = tween(durationMillis = 250),
                            ),
                        exit =
                            shrinkHorizontally(
                                shrinkTowards = Alignment.End,
                                animationSpec = tween(durationMillis = 250),
                            ),
                    ) {
                        Box(
                            modifier = Modifier.hoverable(interactionSource = reveal.rightSidebarInteractionSource),
                        ) {
                            // The RIGHT_RAIL half of the placement, and empty for the other two.
                            // The rail is where these belong whenever there is a rail: three more
                            // icons on a strip that is already icon chrome, instead of an overlay
                            // over live content.
                            //
                            // The reserve is deliberately NOT the rendered count: it is held for
                            // as long as focus mode owns the top bar, so hover-revealing that bar
                            // takes the three icons away without also handing their rows back to
                            // the plugin slots and reshuffling them. See focusQuickActionsRailRows.
                            BossRightSideBar(
                                toolsOpen = state.showToolLauncherDialog,
                                onOpenTools =
                                    openTools.takeIf {
                                        launcherPlacement == ToolLauncherPlacement.RIGHT_STRIP
                                    },
                                bottomActions = quickActionsRail,
                                bottomActionRows =
                                    focusQuickActionsRailRows(
                                        settings = focusModeSettings,
                                        topBarHidden = !appearance.showTopBar,
                                        rightStripHidden = !appearance.showRightStrip,
                                    ),
                            )
                        }
                    }
                }

                // Bottom bar - hidden in focus mode with smooth expand/shrink animation
                AnimatedVisibility(
                    visible = appearance.showBottomBar && reveal.showBottomBar,
                    enter =
                        expandVertically(
                            expandFrom = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 250),
                        ),
                    exit =
                        shrinkVertically(
                            shrinkTowards = Alignment.Bottom,
                            animationSpec = tween(durationMillis = 250),
                        ),
                ) {
                    Box(
                        modifier = Modifier.hoverable(interactionSource = reveal.bottomBarInteractionSource),
                    ) {
                        BossBottomBar(splitViewState.getActiveTabsComponent())
                    }
                }
            }

            // Hover reveal strips for focus mode - dynamic sizing to avoid blocking clicks
            FocusModeHoverStrips(
                state = reveal,
                settings = focusModeSettings,
                revealOffsetDp = revealOffsetDp,
                // No strip for a bar switched off in settings: hover cannot bring it back, so the
                // band would sit dead over live content and, at the top edge, hide the quick actions.
                barVisible = { edge -> appearance.isBarVisible(edge) },
            )

            // Draw the dragging item overlay (ghost) if an item is being dragged
            DraggingItemOverlay()

            // Draw the tab dragging overlay (ghost tab) if a tab is being dragged
            state.tabDragComponent.TabDraggingOverlay()

            // Plugin notification toasts — the render surface for every plugin's
            // PluginContext.notificationProvider.showToast().
            state.currentDefaultPlugin?.pluginToastState?.let { toastState ->
                ToastOverlay(toastState = toastState)
            }

            // MRU tab-switcher overlay (Ctrl+Tab in most-recently-used mode)
            TabCycleOverlayHost(
                data = state.tabCycleOverlay,
                alignment = Alignment.Center,
            )
        }
    }
}
