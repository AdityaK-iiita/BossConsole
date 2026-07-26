package ai.rever.boss.ipc

import ai.rever.boss.ipc.proto.*
import ai.rever.boss.ipc.services.EventBusServiceImpl
import com.google.protobuf.ByteString
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for EventBusService — publish/subscribe round-trip.
 */
class EventBusServiceTest {
    private companion object {
        const val POLL_MS = 25L
        const val SETTLE_MS = 100L
        const val BATCH_SIZE = 3
    }

    private var server: io.grpc.Server? = null
    private var channel: io.grpc.ManagedChannel? = null
    private var port: Int = 0
    private lateinit var eventBusService: EventBusServiceImpl

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        eventBusService = EventBusServiceImpl()
        server =
            ServerBuilder
                .forPort(port)
                .addService(eventBusService)
                .build()
                .start()
        channel =
            ManagedChannelBuilder
                .forAddress("localhost", port)
                .usePlaintext()
                .build()
    }

    @After
    fun tearDown() {
        channel?.shutdownNow()
        server?.shutdownNow()
    }

    /**
     * Publish until the subscriber has actually received something.
     *
     * These tests used to `delay(100)` and publish once. The service's `MutableSharedFlow` has **no
     * replay**, so a publish that lands before the subscription is registered is lost for good — and then
     * `subscriberJob.join()` waits out the enclosing 10s timeout. Latent on a fast machine, and it fails on
     * the Windows CI runner, which is the slowest leg. Republishing is safe because every caller asserts on
     * the *first* event it receives.
     */
    private suspend fun publishUntilDelivered(
        subscriber: Job,
        publish: suspend () -> Unit,
    ) {
        while (subscriber.isActive) {
            publish()
            delay(POLL_MS)
        }
    }

    /**
     * Wait until a subscription has been registered with the service.
     *
     * Weaker than [publishUntilDelivered] — the count increments when the RPC handler runs, which is still
     * ahead of the flow being collected — but it is what a test asserting an exact event count can use,
     * since republishing would change the count it asserts. Still strictly better than a fixed sleep.
     */
    private suspend fun awaitSubscriberRegistered() {
        while (eventBusService.activeSubscribers < 1) {
            delay(POLL_MS)
        }
        delay(SETTLE_MS)
    }

    @Test
    fun `publish event is received by subscriber`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                // Start subscriber
                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("test-subscriber-1")
                        .addEventTypes("TestEvent")
                        .build()

                var receivedEnvelope: EventEnvelope? = null
                val subscriberJob =
                    launch {
                        receivedEnvelope = stub.subscribe(subscribeRequest).first()
                    }

                // Give subscriber time to connect
                // Publish event
                val envelope =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("TestEvent")
                        .setPayload(ByteString.copyFromUtf8("hello from test"))
                        .setSourceProcess("test-publisher")
                        .setTimestamp(System.currentTimeMillis())
                        .build()

                var publishResponse = stub.publish(envelope)
                publishUntilDelivered(subscriberJob) { publishResponse = stub.publish(envelope) }

                subscriberJob.join()

                assertTrue(publishResponse.success)
                assertEquals("TestEvent", receivedEnvelope?.eventType)
                assertEquals("hello from test", receivedEnvelope?.payload?.toStringUtf8())
            }
        }

    @Test
    fun `subscriber with type filter only receives matching events`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                // Subscribe only to "FilteredEvent"
                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("filter-subscriber")
                        .addEventTypes("FilteredEvent")
                        .build()

                var receivedEnvelope: EventEnvelope? = null
                val subscriberJob =
                    launch {
                        receivedEnvelope = stub.subscribe(subscribeRequest).first()
                    }

                // Publish unmatched event first
                stub.publish(
                    EventEnvelope
                        .newBuilder()
                        .setEventType("OtherEvent")
                        .setPayload(ByteString.copyFromUtf8("should not receive"))
                        .setTimestamp(System.currentTimeMillis())
                        .build(),
                )

                // Publish matching event
                val matching =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("FilteredEvent")
                        .setPayload(ByteString.copyFromUtf8("should receive this"))
                        .setTimestamp(System.currentTimeMillis())
                        .build()
                publishUntilDelivered(subscriberJob) { stub.publish(matching) }

                subscriberJob.join()

                assertEquals("FilteredEvent", receivedEnvelope?.eventType)
                assertEquals("should receive this", receivedEnvelope?.payload?.toStringUtf8())
            }
        }

    @Test
    fun `publishBatch delivers all events`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                val received = mutableListOf<EventEnvelope>()
                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("batch-subscriber")
                        .addEventTypes("BatchEvent")
                        .build()

                val subscriberJob =
                    launch {
                        stub.subscribe(subscribeRequest).collect { envelope ->
                            received.add(envelope)
                            if (received.size == BATCH_SIZE) return@collect
                        }
                    }

                awaitSubscriberRegistered()

                val batchRequest =
                    PublishBatchRequest
                        .newBuilder()
                        .addAllEvents(
                            (1..3).map { i ->
                                EventEnvelope
                                    .newBuilder()
                                    .setEventType("BatchEvent")
                                    .setPayload(ByteString.copyFromUtf8("event-$i"))
                                    .setTimestamp(System.currentTimeMillis())
                                    .build()
                            },
                        ).build()

                stub.publishBatch(batchRequest)

                // Wait for the three, rather than sleeping long enough that they have probably arrived. The
                // enclosing withTimeout is the bound if they never do, so a real failure reports as a
                // timeout instead of an off-by-a-few count that reads like a delivery bug.
                while (received.size < BATCH_SIZE) {
                    delay(POLL_MS)
                }
                subscriberJob.cancel()

                assertEquals(BATCH_SIZE, received.size, "Should receive all 3 batch events")
            }
        }

    @Test
    fun `local publish via publishLocal reaches subscribers`() =
        runBlocking {
            withTimeout(10_000) {
                val stub = EventBusServiceGrpcKt.EventBusServiceCoroutineStub(channel!!)

                val subscribeRequest =
                    SubscribeRequest
                        .newBuilder()
                        .setSubscriberId("local-subscriber")
                        .addEventTypes("LocalEvent")
                        .build()

                var receivedEnvelope: EventEnvelope? = null
                val subscriberJob =
                    launch {
                        receivedEnvelope = stub.subscribe(subscribeRequest).first()
                    }

                val event =
                    EventEnvelope
                        .newBuilder()
                        .setEventType("LocalEvent")
                        .setPayload(ByteString.copyFromUtf8("local-payload"))
                        .setTimestamp(System.currentTimeMillis())
                        .build()
                publishUntilDelivered(subscriberJob) { eventBusService.publishLocal(event) }

                subscriberJob.join()
                assertEquals("LocalEvent", receivedEnvelope?.eventType)
                assertEquals("local-payload", receivedEnvelope?.payload?.toStringUtf8())
            }
        }
}
