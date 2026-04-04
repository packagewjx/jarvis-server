package jarvis.server.service

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jarvis.server.config.AppConfig
import jarvis.server.gateway.ChannelGateway
import jarvis.server.model.AckPayload
import jarvis.server.model.AudioChunkPayload
import jarvis.server.model.CachedRun
import jarvis.server.model.ChannelSendRequest
import jarvis.server.model.ChannelStreamEvent
import jarvis.server.model.ChatEnvelope
import jarvis.server.model.CompletePayload
import jarvis.server.model.DeltaPayload
import jarvis.server.model.ErrorPayload
import jarvis.server.model.MessageSendPayload
import jarvis.server.model.ReplaceCardPayload
import jarvis.server.model.RolePayload
import jarvis.server.model.WelcomePayload
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ChatBridgeService(
    private val config: AppConfig,
    private val channelGateway: ChannelGateway,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val logger = KotlinLogging.logger {}
    private val runs = ConcurrentHashMap<String, CachedRun>()

    fun isAuthorized(header: String?): Boolean = header == "Bearer ${config.authToken}"

    suspend fun handleSession(session: WebSocketServerSession) = supervisorScope {
        session.sendEnvelope(
            ChatEnvelope(
                event = "session.welcome",
                traceId = nextTraceId("welcome"),
                conversationId = "",
                timestamp = now(),
                payload = json.encodeToJsonElement(
                    WelcomePayload.serializer(),
                    WelcomePayload(config.userId, now()),
                ),
            )
        )

        for (frame in session.incoming) {
            if (frame !is Frame.Text) {
                continue
            }

            try {
                val envelope = json.decodeFromString<ChatEnvelope>(frame.readText())
                when (envelope.event) {
                    "ping" -> handlePing(session, envelope)
                    "message.send" -> launch { handleMessageSend(session, envelope) }
                    else -> session.sendError(
                        conversationId = envelope.conversationId,
                        messageId = envelope.messageId,
                        clientMessageId = envelope.clientMessageId,
                        traceId = envelope.traceId,
                        code = "UNSUPPORTED_EVENT",
                        message = "Unsupported event: ${envelope.event}",
                    )
                }
            } catch (ex: SerializationException) {
                session.sendError(
                    conversationId = "",
                    messageId = "",
                    clientMessageId = "",
                    traceId = nextTraceId("decode"),
                    code = "INVALID_ENVELOPE",
                    message = ex.message ?: "Unable to decode envelope",
                )
            } catch (ex: Exception) {
                logger.error(ex) { "Unexpected websocket failure" }
                session.sendError(
                    conversationId = "",
                    messageId = "",
                    clientMessageId = "",
                    traceId = nextTraceId("internal"),
                    code = "INTERNAL_SERVER_ERROR",
                    message = ex.message ?: "Unexpected internal error",
                )
            }
        }
    }

    private suspend fun handlePing(session: WebSocketServerSession, envelope: ChatEnvelope) {
        session.sendEnvelope(
            envelope.copy(
                event = "pong",
                timestamp = now(),
                payload = null,
            )
        )
    }

    private suspend fun handleMessageSend(session: WebSocketServerSession, envelope: ChatEnvelope) {
        val payload = json.decodeFromJsonElement(
            MessageSendPayload.serializer(),
            requireNotNull(envelope.payload),
        )
        val runKey = runKey(envelope.conversationId, envelope.clientMessageId)
        runs[runKey]?.let {
            replayCachedRun(session, it)
            return
        }

        val assistantMessageId = nextId("msg_srv_assistant")
        val run = CachedRun(
            requestId = nextId("req"),
            assistantMessageId = assistantMessageId,
        )
        val previous = runs.putIfAbsent(runKey, run)
        if (previous != null) {
            replayCachedRun(session, previous)
            return
        }

        try {
            val request = ChannelSendRequest(
                requestId = run.requestId,
                conversationId = envelope.conversationId,
                clientMessageId = envelope.clientMessageId,
                assistantMessageId = assistantMessageId,
                traceId = envelope.traceId.ifBlank { nextTraceId("send") },
                userId = config.userId,
                payload = payload,
            )

            run.requestId = channelGateway.submit(request)

            cacheAndSend(
                session,
                run,
                envelope.serverEvent(
                    event = "message.ack",
                    messageId = assistantMessageId,
                    payload = AckPayload(true),
                ),
            )

            cacheAndSend(
                session,
                run,
                envelope.serverEvent(
                    event = "message.start",
                    messageId = assistantMessageId,
                    payload = RolePayload("assistant"),
                ),
            )

            channelGateway.streamEvents(run.requestId).collect { event ->
                forwardChannelEvent(session, envelope, run, event)
            }
        } catch (ex: Exception) {
            logger.error(ex) { "Failed to bridge message.send" }
            cacheAndSend(
                session,
                run,
                envelope.serverEvent(
                    event = "message.error",
                    messageId = assistantMessageId,
                    payload = ErrorPayload("CHANNEL_BRIDGE_FAILED", ex.message ?: "Bridge request failed"),
                ),
            )
            run.completed = true
        }
    }

    private suspend fun forwardChannelEvent(
        session: WebSocketServerSession,
        sourceEnvelope: ChatEnvelope,
        run: CachedRun,
        event: ChannelStreamEvent,
    ) {
        when (event) {
            is ChannelStreamEvent.TextDelta -> {
                val seq = ++run.textSeq
                cacheAndSend(
                    session,
                    run,
                    sourceEnvelope.serverEvent(
                        event = "message.delta",
                        messageId = run.assistantMessageId,
                        cardId = event.cardId,
                        seq = seq,
                        payload = DeltaPayload(event.delta),
                    ),
                )
            }

            is ChannelStreamEvent.ImageCard -> {
                cacheAndSend(
                    session,
                    run,
                    sourceEnvelope.serverEvent(
                        event = "card.replace",
                        messageId = run.assistantMessageId,
                        cardId = event.cardId,
                        payload = ReplaceCardPayload(
                            cardType = "image",
                            url = event.url,
                            caption = event.caption,
                        ),
                    ),
                )
            }

            is ChannelStreamEvent.AudioCard -> {
                run.audioStarted = true
                cacheAndSend(
                    session,
                    run,
                    sourceEnvelope.serverEvent(
                        event = "card.replace",
                        messageId = run.assistantMessageId,
                        cardId = event.cardId,
                        payload = ReplaceCardPayload(
                            cardType = "audio",
                            mime = event.mime,
                            durationMs = event.durationMs,
                        ),
                    ),
                )
            }

            is ChannelStreamEvent.AudioChunk -> {
                run.audioStarted = true
                val seq = run.audioSeqByCard.merge(event.cardId, 1L) { current, _ -> current + 1 } ?: 1L
                cacheAndSend(
                    session,
                    run,
                    sourceEnvelope.serverEvent(
                        event = "audio.output.chunk",
                        messageId = run.assistantMessageId,
                        cardId = event.cardId,
                        seq = seq,
                        payload = AudioChunkPayload(
                            mime = event.mime,
                            durationMs = event.durationMs,
                            base64 = event.base64,
                        ),
                    ),
                )
            }

            is ChannelStreamEvent.Complete -> {
                if (run.audioStarted) {
                    cacheAndSend(
                        session,
                        run,
                        sourceEnvelope.serverEvent(
                            event = "audio.output.complete",
                            messageId = run.assistantMessageId,
                            payload = null,
                        ),
                    )
                }

                cacheAndSend(
                    session,
                    run,
                    sourceEnvelope.serverEvent(
                        event = "message.complete",
                        messageId = run.assistantMessageId,
                        seq = run.textSeq + 1,
                        payload = CompletePayload(event.finishReason),
                    ),
                )
                run.completed = true
            }

            is ChannelStreamEvent.Error -> {
                cacheAndSend(
                    session,
                    run,
                    sourceEnvelope.serverEvent(
                        event = "message.error",
                        messageId = run.assistantMessageId,
                        payload = ErrorPayload(event.code, event.message),
                    ),
                )
                run.completed = true
            }
        }
    }

    private suspend fun replayCachedRun(session: WebSocketServerSession, run: CachedRun) {
        run.events.forEach { session.sendEnvelope(it) }
    }

    private suspend fun cacheAndSend(
        session: WebSocketServerSession,
        run: CachedRun,
        envelope: ChatEnvelope,
    ) {
        run.events += envelope
        session.sendEnvelope(envelope)
    }

    private suspend fun WebSocketServerSession.sendEnvelope(envelope: ChatEnvelope) {
        send(Frame.Text(json.encodeToString(ChatEnvelope.serializer(), envelope)))
    }

    private suspend fun WebSocketServerSession.sendError(
        conversationId: String,
        messageId: String,
        clientMessageId: String,
        traceId: String,
        code: String,
        message: String,
    ) {
        sendEnvelope(
            ChatEnvelope(
                event = "message.error",
                traceId = traceId,
                conversationId = conversationId,
                messageId = messageId,
                clientMessageId = clientMessageId,
                timestamp = now(),
                payload = json.encodeToJsonElement(
                    ErrorPayload.serializer(),
                    ErrorPayload(code, message),
                ),
            ),
        )
    }

    private fun ChatEnvelope.serverEvent(
        event: String,
        messageId: String,
        payload: Any?,
        cardId: String = "",
        seq: Long = 0,
    ): ChatEnvelope {
        return ChatEnvelope(
            event = event,
            traceId = traceId.ifBlank { nextTraceId(event) },
            conversationId = conversationId,
            messageId = messageId,
            clientMessageId = clientMessageId,
            cardId = cardId,
            seq = seq,
            timestamp = now(),
            payload = payload?.let(::encodePayload),
        )
    }

    private fun encodePayload(payload: Any): JsonElement {
        return when (payload) {
            is AckPayload -> json.encodeToJsonElement(AckPayload.serializer(), payload)
            is RolePayload -> json.encodeToJsonElement(RolePayload.serializer(), payload)
            is DeltaPayload -> json.encodeToJsonElement(DeltaPayload.serializer(), payload)
            is ReplaceCardPayload -> json.encodeToJsonElement(ReplaceCardPayload.serializer(), payload)
            is AudioChunkPayload -> json.encodeToJsonElement(AudioChunkPayload.serializer(), payload)
            is CompletePayload -> json.encodeToJsonElement(CompletePayload.serializer(), payload)
            is ErrorPayload -> json.encodeToJsonElement(ErrorPayload.serializer(), payload)
            is WelcomePayload -> json.encodeToJsonElement(WelcomePayload.serializer(), payload)
            else -> throw IllegalArgumentException("Unsupported payload type: ${payload::class.qualifiedName}")
        }
    }

    private fun runKey(conversationId: String, clientMessageId: String): String =
        "$conversationId::$clientMessageId"

    private fun nextId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    private fun nextTraceId(prefix: String): String = "${prefix}_${System.currentTimeMillis()}"

    private fun now(): Long = System.currentTimeMillis()
}
