package ai.rever.boss.plugin.browser

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Which embedded browser a WINDOW-scoped action should act on.
 *
 * The View menu's Zoom In / Zoom Out / Actual Size / Reload emit only a window id
 * ([ai.rever.boss.window.MenuActionsHandler]), and the host cannot answer "which browser" from the
 * tab tree: the live fluck tab is a DYNAMIC plugin's component, so the `activeTab is
 * FluckTabComponent` test those handlers used to perform was one the host could never satisfy -
 * the built-in `FluckTabComponent` stopped being instantiated when `registerFluck()` was disabled
 * in favour of the plugin, and the plugin's class is unrelated to it. All four menu items were
 * therefore no-ops on every platform.
 *
 * The one place that knows a browser surface is on screen, in which window and in which panel is
 * `BrowserHandleImpl.Content()`, so that is where entries come from. Registering a handle rather
 * than a tab component also keeps this independent of the plugin API: any browser-backed plugin
 * tab is served without the plugin implementing anything.
 *
 * `java.util.concurrent` in a `commonMain` file is deliberate: composeApp declares a single
 * `jvm("desktop")` target, and the same primitives are already used by `AWTKeyboardInterceptor`
 * and `BrowserFindController`. The concurrency is real - see [register].
 */
object ActiveBrowserRegistry {
    /**
     * One composed browser surface.
     *
     * Value-equality is load-bearing: it is what lets [unregister] remove an entry only when it is
     * still the current one.
     */
    internal data class Entry(
        val handleId: String,
        val windowId: String,
        val inMainPanel: Boolean,
        val panelActive: Boolean,
        val sequence: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val handles = ConcurrentHashMap<String, BrowserHandle>()
    private val sequencer = AtomicLong(0)

    /**
     * Record that [handle]'s surface is composed in [windowId].
     *
     * Written from the Compose UI thread and read from the menu flow collectors, hence the
     * concurrent maps. [sequence] comes from an atomic counter so two panels re-registering within
     * the same frame still get distinct, increasing ranks - though [selectActiveHandleId]
     * deliberately does not depend on which of the two wins that race.
     *
     * @return a token to hand back to [unregister]; see the cross-window note there.
     */
    fun register(
        handle: BrowserHandle,
        windowId: String,
        inMainPanel: Boolean,
        panelActive: Boolean,
    ): Any {
        val entry =
            Entry(
                handleId = handle.id,
                windowId = windowId,
                inMainPanel = inMainPanel,
                panelActive = panelActive,
                sequence = sequencer.incrementAndGet(),
            )
        handles[handle.id] = handle
        entries[handle.id] = entry
        return entry
    }

    /**
     * Remove [handleId]'s registration, but only if [token] is still the current one.
     *
     * A tab moving between windows builds one composition and tears down the other in an order
     * this registry does not control - `BrowserHandleImpl` documents the same hazard for its
     * visibility counter. Both compositions carry the SAME handle id, so an unconditional remove
     * from the outgoing one would delete the incoming one's entry and leave the menu with nothing
     * to act on. The value-equality remove is the same trick `BrowserWindowOwnershipRegistry` uses.
     */
    fun unregister(
        handleId: String,
        token: Any?,
    ) {
        if (token is Entry && entries.remove(handleId, token)) {
            handles.remove(handleId)
        }
    }

    /** Unconditional removal, for handle disposal - the handle is gone, so no successor exists. */
    fun unregister(handleId: String) {
        entries.remove(handleId)
        handles.remove(handleId)
    }

    /** The browser a window-scoped action should act on in [windowId], or null if there is none. */
    fun activeIn(windowId: String): BrowserHandle? {
        val liveEntries = entries.values.filter { handles[it.handleId]?.isValid == true }
        val handleId = selectActiveHandleId(liveEntries, windowId) ?: return null
        return handles[handleId]?.takeIf { it.isValid }
    }
}

/**
 * Of the live browser surfaces composed in [windowId], which one owns a window-scoped action.
 *
 * Extracted as a pure function so the tie-break is unit-testable without a JxBrowser `Browser`.
 *
 * Ranked, most significant first:
 *  1. `inMainPanel` - `LocalIsPanelActive` DEFAULTS TO TRUE, so a browser rendered in a sidebar
 *     slot reports itself active too; only `LocalInMainWindowPanel` separates the two. This is the
 *     same reasoning `BossMainWindowPanel` gives where it provides both locals.
 *  2. `panelActive` - within the main content area, the split panel the user is in wins.
 *  3. `sequence` - otherwise the most recently shown surface wins.
 */
internal fun selectActiveHandleId(
    candidates: Collection<ActiveBrowserRegistry.Entry>,
    windowId: String,
): String? =
    candidates
        .filter { it.windowId == windowId }
        .maxWithOrNull(
            compareBy<ActiveBrowserRegistry.Entry>(
                { it.inMainPanel },
                { it.panelActive },
                { it.sequence },
            ),
        )?.handleId
