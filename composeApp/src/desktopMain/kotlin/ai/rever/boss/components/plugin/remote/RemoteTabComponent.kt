package ai.rever.boss.components.plugin.remote

import ai.rever.boss.ipc.proto.PluginUIServiceGrpcKt
import ai.rever.boss.ipc.proto.UIEvent
import ai.rever.boss.ui.sdk.UIEventMapper
import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.ui.sdk.WidgetTree
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.*
import io.grpc.ManagedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch

/**
 * Host-side tab component that renders a remote plugin's tab UI.
 *
 * Same pattern as [RemotePanelComponent] but for tab-type surfaces,
 * with additional title and loading state management.
 */
class RemoteTabComponent(
    val tabId: String,
    val displayName: String,
    private val processId: String,
    private val uiAddress: String,
) {
    private val logger = BossLogger.forComponent("RemoteTabComponent")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _widgetTree = mutableStateOf<WidgetTree?>(null)
    private val _title = mutableStateOf(displayName)
    private val _isLoading = mutableStateOf(false)
    private val _connected = mutableStateOf(false)

    /**
     * Outgoing UI events from kernel to plugin process.
     *
     * A queue, not a shared flow: `TextChange` carries the *whole* field value with
     * last-write-wins semantics, so two fast keystrokes that each got their own coroutine could
     * race to the stream and land reversed, silently reverting a character. An unlimited channel
     * written straight from the Compose callback keeps emission order = interaction order.
     */
    private val outgoingEvents = Channel<UIEvent>(Channel.UNLIMITED)

    val title: State<String> get() = _title
    val isLoading: State<Boolean> get() = _isLoading
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

    /**
     * Connect to the plugin process and start streaming widget updates.
     */
    fun connect() {
        logger.info(
            LogCategory.UI,
            "Connecting to remote tab",
            mapOf("tabId" to tabId, "process" to processId, "address" to uiAddress),
        )
        scope.launch {
            connectToPluginProcess()
        }
    }

    /**
     * Connect using a pre-existing gRPC channel.
     */
    fun connect(channel: ManagedChannel) {
        logger.info(
            LogCategory.UI,
            "Connecting to remote tab via channel",
            mapOf("tabId" to tabId, "process" to processId),
        )
        scope.launch {
            connectWithChannel(channel)
        }
    }

    /**
     * Update the displayed widget tree (called from IPC handler).
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
        scope.cancel()
        outgoingEvents.close()
        _connected.value = false
        logger.info(LogCategory.UI, "Remote tab disposed", mapOf("tabId" to tabId))
    }

    // ---- Internal ----

    private suspend fun connectToPluginProcess() {
        try {
            val client =
                ai.rever.boss.ipc
                    .BossIpcClient(uiAddress)
            connectWithChannel(client.channel)
        } catch (e: Exception) {
            logger.error(
                LogCategory.UI,
                "Failed to connect to plugin process",
                mapOf("tabId" to tabId),
                error = e,
            )
            _connected.value = false
        }
    }

    private suspend fun connectWithChannel(channel: ManagedChannel) {
        val stub = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(channel)

        try {
            _connected.value = true

            val widgetUpdateStream =
                channelFlow {
                    outgoingEvents.consumeAsFlow().collect { event ->
                        val update =
                            ai.rever.boss.ipc.proto.WidgetUpdate
                                .newBuilder()
                                .setSurfaceId(event.surfaceId)
                                .build()
                        send(update)
                    }
                }

            stub.streamUI(widgetUpdateStream).collect { uiEvent ->
                logger.debug(LogCategory.UI, "Received UI event from plugin tab", mapOf("surface" to uiEvent.surfaceId))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _connected.value = false
            logger.warn(
                LogCategory.UI,
                "Connection to plugin tab lost",
                mapOf("tabId" to tabId, "error" to e.message),
            )
        }
    }

    /**
     * Queue one interaction for the plugin process — see [RemotePanelComponent] for why this is an
     * ordered channel written from the callback rather than a coroutine per event, and why
     * [UIEventMapper] owns the proto mapping.
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
        val queued = outgoingEvents.trySend(UIEventMapper.toProto(tabId, nodeId, event, System.currentTimeMillis()))
        if (queued.isFailure) {
            logger.debug(LogCategory.UI, "Dropped UI event: surface is closed", mapOf("tabId" to tabId))
        }
    }
}
