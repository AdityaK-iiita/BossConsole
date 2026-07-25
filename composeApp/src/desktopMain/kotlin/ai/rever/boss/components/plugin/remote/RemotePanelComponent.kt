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
 * Host-side panel component that renders a remote plugin's UI.
 *
 * Connects to the plugin process via gRPC [PluginUIService], collects
 * widget tree updates, and re-renders using [RemoteWidgetRenderer].
 *
 * UI events from user interactions are forwarded back to the plugin process
 * via the bidirectional stream.
 */
class RemotePanelComponent(
    val panelId: String,
    val displayName: String,
    private val processId: String,
    private val uiAddress: String,
) {
    private val logger = BossLogger.forComponent("RemotePanelComponent")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _widgetTree = mutableStateOf<WidgetTree?>(null)
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

    /** Whether the component is connected to the plugin process. */
    val connected: State<Boolean> get() = _connected

    /**
     * Compose content for this remote panel.
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
     * Call this when the panel is first displayed.
     */
    fun connect() {
        logger.info(
            LogCategory.UI,
            "Connecting to remote panel",
            mapOf("panelId" to panelId, "process" to processId, "address" to uiAddress),
        )
        scope.launch {
            connectToPluginProcess()
        }
    }

    /**
     * Connect to the plugin process and start streaming widget updates.
     * Uses the provided gRPC channel instead of creating one from the address.
     */
    fun connect(channel: ManagedChannel) {
        logger.info(
            LogCategory.UI,
            "Connecting to remote panel via channel",
            mapOf("panelId" to panelId, "process" to processId),
        )
        scope.launch {
            connectWithChannel(channel)
        }
    }

    /**
     * Update the displayed widget tree (called from IPC handler or direct test).
     */
    fun updateTree(tree: WidgetTree) {
        _widgetTree.value = tree
    }

    fun dispose() {
        scope.cancel()
        outgoingEvents.close()
        _connected.value = false
        logger.info(LogCategory.UI, "Remote panel disposed", mapOf("panelId" to panelId))
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
                mapOf("panelId" to panelId),
                error = e,
            )
            _connected.value = false
        }
    }

    private suspend fun connectWithChannel(channel: ManagedChannel) {
        val stub = PluginUIServiceGrpcKt.PluginUIServiceCoroutineStub(channel)

        try {
            _connected.value = true

            // StreamUI is bidirectional: kernel sends WidgetUpdates, plugin sends UIEvents.
            // We wrap outgoing UIEvents as the request stream and collect incoming UIEvents
            // from the response stream.
            val widgetUpdateStream =
                channelFlow {
                    // Forward UI events from kernel to plugin process as WidgetUpdate wrappers
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
                logger.debug(LogCategory.UI, "Received UI event from plugin", mapOf("surface" to uiEvent.surfaceId))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _connected.value = false
            logger.warn(
                LogCategory.UI,
                "Connection to plugin process lost",
                mapOf("panelId" to panelId, "error" to e.message),
            )
        }
    }

    /**
     * Queue one interaction for the plugin process.
     *
     * Not `suspend`, and deliberately called straight from the Compose callback: the send is a
     * non-blocking enqueue onto an unlimited channel, so interactions reach the stream in the order
     * the user made them. Handing each event to its own `scope.launch` let two keystrokes race.
     *
     * The `WidgetEvent` → proto `UIEvent` mapping lives in [UIEventMapper] (boss-ui-sdk): it is total
     * over the sealed event type, so no oneof case can be silently skipped — which is exactly how
     * dropdown selections used to cross the wire as events with no payload at all.
     */
    private fun sendUIEvent(
        nodeId: String,
        event: WidgetEvent,
    ) {
        // Payloads can contain what the user typed — log the shape, not the content.
        logger.debug(
            LogCategory.UI,
            "Panel UI event",
            mapOf("panelId" to panelId, "node" to nodeId, "type" to event::class.simpleName),
        )
        val queued = outgoingEvents.trySend(UIEventMapper.toProto(panelId, nodeId, event, System.currentTimeMillis()))
        if (queued.isFailure) {
            logger.debug(LogCategory.UI, "Dropped UI event: surface is closed", mapOf("panelId" to panelId))
        }
    }
}
