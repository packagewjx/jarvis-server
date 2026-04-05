package jarvis.server

import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import jarvis.server.config.AppConfig
import jarvis.server.config.ChannelConfig
import jarvis.server.config.XfyunIatConfig
import jarvis.server.gateway.ChannelGateway
import jarvis.server.model.ChannelSendRequest
import jarvis.server.model.ChannelStreamEvent
import jarvis.server.model.ChatEnvelope
import jarvis.server.model.MessageCard
import jarvis.server.model.MessageSendPayload
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
        application {
            chatModule(createService(gateway))
        }

        val client = createClient {
            install(ClientWebSockets)
            defaultRequest {
                headers.append(HttpHeaders.Authorization, "Bearer test-token")
            }
        }
        val session = client.webSocketSession("/ws/chat")

        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                conversationId = "conv_1",
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
        application {
            chatModule(createService(gateway))
        }

        val client = createClient {
            install(ClientWebSockets)
            defaultRequest {
                headers.append(HttpHeaders.Authorization, "Bearer test-token")
            }
        }
        val session = client.webSocketSession("/ws/chat")

        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                conversationId = "conv_2",
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
        application {
            chatModule(createService(gateway))
        }

        val client = createClient {
            install(ClientWebSockets)
            defaultRequest {
                headers.append(HttpHeaders.Authorization, "Bearer test-token")
            }
        }
        val session = client.webSocketSession("/ws/chat")

        session.awaitEnvelope(json) // session.welcome
        session.sendEnvelope(
            json,
            messageSendEnvelope(
                conversationId = "conv_3",
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

    private fun createService(gateway: ChannelGateway): ChatBridgeService {
        return ChatBridgeService(
            config = AppConfig(
                host = "127.0.0.1",
                port = 8080,
                authToken = "test-token",
                userId = "test-user",
                channel = ChannelConfig(
                    baseUrl = "https://localhost:9443",
                    authToken = "channel-token",
                    connectTimeoutMs = 1000,
                    readTimeoutMs = 1000,
                    caCertPath = null,
                    hostnameVerification = true,
                ),
                iat = XfyunIatConfig(
                    appId = null,
                    apiKey = null,
                    apiSecret = null,
                    host = "iat.cn-huabei-1.xf-yun.com",
                    path = "/v1",
                    ttlSec = 120,
                    rateLimitPerMinute = 30,
                    defaultSampleRate = 16000,
                    defaultDomain = "slm",
                    defaultLanguage = "zh_cn",
                    defaultAccent = "mulacc",
                    audioEncoding = "lame",
                ),
            ),
            channelGateway = gateway,
            json = json,
        )
    }

    private fun Application.chatModule(chatBridgeService: ChatBridgeService) {
        install(ServerWebSockets)
        routing {
            webSocket("/ws/chat") {
                val header = call.request.headers[HttpHeaders.Authorization]
                if (!chatBridgeService.isAuthorized(header)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid bearer token"))
                    return@webSocket
                }
                chatBridgeService.handleSession(this)
            }
        }
    }

    private fun messageSendEnvelope(
        conversationId: String,
        clientMessageId: String,
    ): ChatEnvelope {
        return ChatEnvelope(
            event = "message.send",
            traceId = "trace_$clientMessageId",
            conversationId = conversationId,
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
