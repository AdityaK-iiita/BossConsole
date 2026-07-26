package ai.rever.boss.components.plugin.remote

import ai.rever.boss.kernel.ui.RemoteUiSurfaceHost
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.ui.sdk.UIEventMapper
import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.*

/**
 * Host-side tab component that renders a remote plugin's tab UI.
 *
 * Same shape as [RemotePanelComponent] — a surface half that meets the plugin's stream at
 * [RemoteUiSurfaceRegistry] rather than dialling out to it — plus title and loading state.
 */
class RemoteTabComponent(
    val tabId: String,
    val displayName: String,
    private val processId: String,
    private val registry: RemoteUiSurfaceRegistry = RemoteUiSurfaceRegistry.shared,
) {
    private val logger = BossLogger.forComponent("RemoteTabComponent")

    private val _widgetTree = mutableStateOf<WidgetTree?>(null)
    private val _title = mutableStateOf(displayName)
    private val _isLoading = mutableStateOf(false)
    private val _connected = mutableStateOf(false)

    /** The transport's view of this tab — see [RemotePanelComponent] for why it is not the class itself. */
    private val surfaceHost =
        object : RemoteUiSurfaceHost {
            override fun onTreeUpdated(tree: WidgetTree) {
                updateTree(tree)
            }

            override fun onConnectionChanged(connected: Boolean) {
                _connected.value = connected
            }
        }

    val title: State<String> get() = _title
    val isLoading: State<Boolean> get() = _isLoading

    /** Whether a plugin process is currently streaming this tab's surface. */
    val connected: State<Boolean> get() = _connected

    /**
     * Compose content for this remote tab.
     */
    @Composable
    fun Content() {
        val tree by _widgetTree
        tree?.let { widgetTree ->
            RemoteWidgetRenderer(
                tree = widgetTree,
                onEvent = { nodeId, event -> sendUIEvent(nodeId, event) },
            )
        }
    }

    /** Bind this tab to its surface. Safe before the plugin exists — see [RemotePanelComponent.attach]. */
    fun attach() {
        logger.info(
            LogCategory.UI,
            "Attaching remote tab to its surface",
            mapOf("tabId" to tabId, "process" to processId),
        )
        registry.attach(tabId, surfaceHost)
    }

    /**
     * Update the displayed widget tree (called from the transport, or directly in tests).
     */
    fun updateTree(tree: WidgetTree) {
        _widgetTree.value = tree
    }

    fun updateTitle(title: String) {
        _title.value = title
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun dispose() {
        registry.detach(tabId, surfaceHost)
        _connected.value = false
        logger.info(LogCategory.UI, "Remote tab disposed", mapOf("tabId" to tabId))
    }

    // ---- Internal ----

    /**
     * Hand one interaction to the surface's outgoing queue — see [RemotePanelComponent] for why this is
     * an ordered non-suspending enqueue, and why [UIEventMapper] owns the proto mapping.
     */
    private fun sendUIEvent(
        nodeId: String,
        event: WidgetEvent,
    ) {
        // Payloads can contain what the user typed — log the shape, not the content.
        logger.debug(
            LogCategory.UI,
            "Tab UI event",
            mapOf("tabId" to tabId, "node" to nodeId, "type" to event::class.simpleName),
        )
        val proto = UIEventMapper.toProto(tabId, nodeId, event, System.currentTimeMillis())
        if (!registry.emit(tabId, proto)) {
            logger.debug(
                LogCategory.UI,
                "Dropped UI event: no plugin holds this surface",
                mapOf("tabId" to tabId),
            )
        }
    }
}
