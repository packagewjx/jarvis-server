package jarvis.server

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
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
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

class ChatEventsSyncTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `sync endpoint returns persisted events with event ids`() = testApplication {
        val store = InMemoryChatStore()
        val user = requireNotNull(store.createUser("sync_user", "hash"))
        requireNotNull(store.joinGroupByInvite(user.userId, "DEFAULT-GROUP"))

        val service = ChatBridgeService(
            channelGateway = SyncFakeChannelGateway(),
            chatStore = store,
            json = json,
        )

        application {
            chatSyncModule(service, user.userId)
        }

        val client = createClient {
            install(ClientWebSockets)
        }

        val session = client.webSocketSession("/ws/chat")
        session.awaitEnvelope() // welcome
        session.send(
            Frame.Text(
                json.encodeToString(
                    ChatEnvelope.serializer(),
                    ChatEnvelope(
                        event = "message.send",
                        traceId = "trace_sync_1",
                        groupId = "g_default",
                        clientMessageId = "local_sync_1",
                        timestamp = System.currentTimeMillis(),
                        payload = json.encodeToJsonElement(
                            MessageSendPayload.serializer(),
                            MessageSendPayload(
                                role = "user",
                                cards = listOf(MessageCard(id = "card_1", cardType = "text", text = "hello")),
                                createdAt = System.currentTimeMillis(),
                            ),
                        ),
                    ),
                ),
            ),
        )

        repeat(4) {
            session.awaitEnvelope()
        }

        val response = client.get("/api/chat/groups/g_default/events/sync?after_event_id=0&limit=100")
        val body = response.bodyAsText()

        assertTrue(body.contains("\"items\""))
        assertTrue(body.contains("\"event_id\""))
        assertTrue(body.contains("message.complete"))

        session.close(CloseReason(CloseReason.Codes.NORMAL, "done"))
    }

    private fun Application.chatSyncModule(service: ChatBridgeService, userId: String) {
        install(ServerWebSockets)
        routing {
            webSocket("/ws/chat") {
                service.handleSession(this, userId)
            }

            get("/api/chat/groups/{groupId}/events/sync") {
                val groupId = call.parameters["groupId"] ?: ""
                val page = service.syncGroupEvents(userId, groupId, 0L, 100)
                call.respondText(
                    json.encodeToString(
                        jarvis.server.model.ChatEventsSyncResponse.serializer(),
                        jarvis.server.model.ChatEventsSyncResponse(
                            items = page.items,
                            nextAfterEventId = page.nextAfterEventId,
                            hasMore = page.hasMore,
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.awaitEnvelope(): ChatEnvelope {
        while (true) {
            val frame = withTimeout(5_000) { incoming.receive() }
            if (frame is Frame.Text) {
                return json.decodeFromString(ChatEnvelope.serializer(), frame.readText())
            }
        }
    }
}

private class SyncFakeChannelGateway : ChannelGateway {
    private val requests = linkedMapOf<String, ChannelSendRequest>()

    override suspend fun submit(request: ChannelSendRequest): String {
        requests[request.requestId] = request
        return request.requestId
    }

    override fun streamEvents(requestId: String): Flow<ChannelStreamEvent> = flow {
        val request = requests.getValue(requestId)
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
