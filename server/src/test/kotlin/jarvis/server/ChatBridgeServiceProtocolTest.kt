package jarvis.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import jarvis.server.gateway.ChannelGateway
import jarvis.server.model.ChannelSendRequest
import jarvis.server.model.ChannelStreamEvent
import jarvis.server.model.ChatEnvelope
import jarvis.server.model.MessageCard
import jarvis.server.model.MessageSendPayload
import jarvis.server.persistence.InMemoryChatStore
import jarvis.server.service.ChatBridgeService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class ChatBridgeServiceProtocolTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `ack uses user message id and assistant stream uses assistant message id`() = testApplication {
        val gateway = FakeChannelGateway(mode = FakeGatewayMode.SUCCESS)
        val store = InMemoryChatStore()
        val user = requireNotNull(store.createUser("tester1", "hash"))
        requireNotNull(store.joinGroupByInvite(user.userId, "DEFAULT-GROUP"))
        val service = ChatBridgeService(
            channelGateway = gateway,
            chatStore = store,
            json = json,
        )
        application {
            chatModule(service, user.userId)
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        val session = client.webSocketSession("/ws/chat")

        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                groupId = "g_default",
                clientMessageId = "local_1",
            ),
        )

        val ack = session.awaitEnvelope(json)
        val start = session.awaitEnvelope(json)
        val delta = session.awaitEnvelope(json)
        val complete = session.awaitEnvelope(json)

        assertEquals("message.ack", ack.event)
        assertTrue(ack.messageId.startsWith("msg_srv_user_"))
        assertEquals("local_1", ack.clientMessageId)

        assertEquals("message.start", start.event)
        assertTrue(start.messageId.startsWith("msg_srv_assistant_"))
        assertEquals("local_1", start.clientMessageId)
        assertNotEquals(ack.messageId, start.messageId)

        assertEquals("message.delta", delta.event)
        assertEquals(start.messageId, delta.messageId)
        assertEquals("local_1", delta.clientMessageId)

        assertEquals("message.complete", complete.event)
        assertEquals(start.messageId, complete.messageId)
        assertEquals("local_1", complete.clientMessageId)

        session.close()
    }

    @Test
    fun `message error uses user message id when send phase fails before assistant start`() = testApplication {
        val gateway = FakeChannelGateway(mode = FakeGatewayMode.SUBMIT_FAILURE)
        val store = InMemoryChatStore()
        val user = requireNotNull(store.createUser("tester2", "hash"))
        requireNotNull(store.joinGroupByInvite(user.userId, "DEFAULT-GROUP"))
        val service = ChatBridgeService(
            channelGateway = gateway,
            chatStore = store,
            json = json,
        )
        application {
            chatModule(service, user.userId)
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        val session = client.webSocketSession("/ws/chat")

        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                groupId = "g_default",
                clientMessageId = "local_2",
            ),
        )

        val error = session.awaitEnvelope(json)

        assertEquals("message.error", error.event)
        assertTrue(error.messageId.startsWith("msg_srv_user_"))
        assertEquals("local_2", error.clientMessageId)

        session.close()
    }

    @Test
    fun `message error uses assistant message id when generation fails after assistant start`() = testApplication {
        val gateway = FakeChannelGateway(mode = FakeGatewayMode.STREAM_FAILURE)
        val store = InMemoryChatStore()
        val user = requireNotNull(store.createUser("tester3", "hash"))
        requireNotNull(store.joinGroupByInvite(user.userId, "DEFAULT-GROUP"))
        val service = ChatBridgeService(
            channelGateway = gateway,
            chatStore = store,
            json = json,
        )
        application {
            chatModule(service, user.userId)
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        val session = client.webSocketSession("/ws/chat")

        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                groupId = "g_default",
                clientMessageId = "local_3",
            ),
        )

        val ack = session.awaitEnvelope(json)
        val start = session.awaitEnvelope(json)
        val error = session.awaitEnvelope(json)

        assertEquals("message.ack", ack.event)
        assertTrue(ack.messageId.startsWith("msg_srv_user_"))
        assertEquals("message.start", start.event)
        assertTrue(start.messageId.startsWith("msg_srv_assistant_"))

        assertEquals("message.error", error.event)
        assertEquals(start.messageId, error.messageId)
        assertNotEquals(ack.messageId, error.messageId)
        assertEquals("local_3", error.clientMessageId)

        session.close()
    }

    @Test
    fun `message send is rejected when user is not group member`() = testApplication {
        val gateway = FakeChannelGateway(mode = FakeGatewayMode.SUCCESS)
        val store = InMemoryChatStore()
        val user = requireNotNull(store.createUser("tester4", "hash"))
        val service = ChatBridgeService(
            channelGateway = gateway,
            chatStore = store,
            json = json,
        )
        application {
            chatModule(service, user.userId)
        }

        val client = createClient {
            install(ClientWebSockets)
        }
        val session = client.webSocketSession("/ws/chat")
        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                groupId = "g_default",
                clientMessageId = "local_4",
            ),
        )
        val error = session.awaitEnvelope(json)
        assertEquals("message.error", error.event)
        val payload = json.decodeFromJsonElement(
            jarvis.server.model.ErrorPayload.serializer(),
            requireNotNull(error.payload),
        )
        assertEquals("FORBIDDEN_GROUP", payload.code)
        session.close()
    }

    private fun Application.chatModule(chatBridgeService: ChatBridgeService, userId: String) {
        install(ServerWebSockets)
        routing {
            webSocket("/ws/chat") {
                chatBridgeService.handleSession(this, userId)
            }
        }
    }

    private fun messageSendEnvelope(
        groupId: String,
        clientMessageId: String,
    ): ChatEnvelope {
        return ChatEnvelope(
            event = "message.send",
            traceId = "trace_$clientMessageId",
            groupId = groupId,
            clientMessageId = clientMessageId,
            timestamp = System.currentTimeMillis(),
            payload = json.encodeToJsonElement(
                MessageSendPayload.serializer(),
                MessageSendPayload(
                    role = "user",
                    cards = listOf(
                        MessageCard(
                            id = "card_text_1",
                            cardType = "text",
                            text = "hello",
                        ),
                    ),
                    createdAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    private suspend fun DefaultClientWebSocketSession.sendEnvelope(json: Json, envelope: ChatEnvelope) {
        send(Frame.Text(json.encodeToString(ChatEnvelope.serializer(), envelope)))
    }

    private suspend fun DefaultClientWebSocketSession.awaitEnvelope(json: Json): ChatEnvelope {
        while (true) {
            val frame = withTimeout(5_000) { incoming.receive() }
            if (frame is Frame.Text) {
                return json.decodeFromString(ChatEnvelope.serializer(), frame.readText())
            }
        }
    }
}

private enum class FakeGatewayMode {
    SUCCESS,
    SUBMIT_FAILURE,
    STREAM_FAILURE,
}

private class FakeChannelGateway(
    private val mode: FakeGatewayMode,
) : ChannelGateway {
    private val requests = linkedMapOf<String, ChannelSendRequest>()

    override suspend fun submit(request: ChannelSendRequest): String {
        if (mode == FakeGatewayMode.SUBMIT_FAILURE) {
            error("submit failed")
        }
        requests[request.requestId] = request
        return request.requestId
    }

    override fun streamEvents(requestId: String): Flow<ChannelStreamEvent> = flow {
        if (mode == FakeGatewayMode.SUBMIT_FAILURE) {
            return@flow
        }

        val request = requests.getValue(requestId)
        if (mode == FakeGatewayMode.STREAM_FAILURE) {
            emit(
                ChannelStreamEvent.Error(
                    requestId = request.requestId,
                    conversationId = request.conversationId,
                    clientMessageId = request.clientMessageId,
                    assistantMessageId = request.assistantMessageId,
                    seq = 1,
                    code = "DOWNSTREAM_FAILURE",
                    message = "generation failed",
                ),
            )
            return@flow
        }

        emit(
            ChannelStreamEvent.TextDelta(
                requestId = request.requestId,
                conversationId = request.conversationId,
                clientMessageId = request.clientMessageId,
                assistantMessageId = request.assistantMessageId,
                seq = 1,
                delta = "hello",
            ),
        )
        emit(
            ChannelStreamEvent.Complete(
                requestId = request.requestId,
                conversationId = request.conversationId,
                clientMessageId = request.clientMessageId,
                assistantMessageId = request.assistantMessageId,
                seq = 2,
                finishReason = "stop",
            ),
        )
    }
}
