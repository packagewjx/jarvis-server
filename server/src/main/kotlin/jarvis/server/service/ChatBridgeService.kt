package jarvis.server.service

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import jarvis.server.persistence.ChatEventsPage
import jarvis.server.persistence.ChatStore
import jarvis.server.persistence.InMemoryChatStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class ChatBridgeService(
    private val channelGateway: ChannelGateway,
    private val chatStore: ChatStore = InMemoryChatStore(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val logger = KotlinLogging.logger {}
    private val runs = ConcurrentHashMap<String, CachedRun>()

    suspend fun handleSession(session: WebSocketServerSession, userId: String) = supervisorScope {
        session.sendEnvelope(
            ChatEnvelope(
                event = "session.welcome",
                traceId = nextTraceId("welcome"),
                groupId = "",
                timestamp = now(),
                payload = json.encodeToJsonElement(
                    WelcomePayload.serializer(),
                    WelcomePayload(userId, now()),
                ),
            ),
        )

        for (frame in session.incoming) {
            if (frame !is Frame.Text) {
                continue
            }

            try {
                val envelope = json.decodeFromString<ChatEnvelope>(frame.readText())
                when (envelope.event) {
                    "ping" -> handlePing(session, envelope)
                    "message.send" -> launch { handleMessageSend(session, envelope, userId) }
                    else -> session.sendError(
                        groupId = envelope.groupId,
                        messageId = envelope.messageId,
                        clientMessageId = envelope.clientMessageId,
                        traceId = envelope.traceId,
                        code = "UNSUPPORTED_EVENT",
                        message = "Unsupported event: ${envelope.event}",
                    )
                }
            } catch (ex: SerializationException) {
                session.sendError(
                    groupId = "",
                    messageId = "",
                    clientMessageId = "",
                    traceId = nextTraceId("decode"),
                    code = "INVALID_ENVELOPE",
                    message = ex.message ?: "Unable to decode envelope",
                )
            } catch (ex: Exception) {
                logger.error(ex) { "Unexpected websocket failure" }
                session.sendError(
                    groupId = "",
                    messageId = "",
                    clientMessageId = "",
                    traceId = nextTraceId("internal"),
                    code = "INTERNAL_SERVER_ERROR",
                    message = ex.message ?: "Unexpected internal error",
                )
            }
        }
    }

    suspend fun syncGroupEvents(
        userId: String,
        groupId: String,
        afterEventId: Long,
        limit: Int,
    ): ChatEventsPage {
        return chatStore.listGroupEvents(
            userId = userId,
            groupId = groupId,
            afterEventId = afterEventId,
            limit = limit,
        )
    }

    suspend fun isUserInGroup(userId: String, groupId: String): Boolean {
        return chatStore.isUserInGroup(userId, groupId)
    }

    private suspend fun handlePing(session: WebSocketServerSession, envelope: ChatEnvelope) {
        session.sendEnvelope(
            envelope.copy(
                event = "pong",
                timestamp = now(),
                payload = null,
            ),
        )
    }

    private suspend fun handleMessageSend(session: WebSocketServerSession, envelope: ChatEnvelope, userId: String) {
        if (envelope.groupId.isBlank()) {
            session.sendError(
                groupId = envelope.groupId,
                messageId = envelope.messageId,
                clientMessageId = envelope.clientMessageId,
                traceId = envelope.traceId.ifBlank { nextTraceId("send") },
                code = "INVALID_GROUP_ID",
                message = "group_id is required",
            )
            return
        }
        if (envelope.clientMessageId.isBlank()) {
            session.sendError(
                groupId = envelope.groupId,
                messageId = envelope.messageId,
                clientMessageId = envelope.clientMessageId,
                traceId = envelope.traceId.ifBlank { nextTraceId("send") },
                code = "INVALID_CLIENT_MESSAGE_ID",
                message = "client_message_id is required",
            )
            return
        }
        if (!chatStore.isUserInGroup(userId, envelope.groupId)) {
            session.sendError(
                groupId = envelope.groupId,
                messageId = envelope.messageId,
                clientMessageId = envelope.clientMessageId,
                traceId = envelope.traceId.ifBlank { nextTraceId("send") },
                code = "FORBIDDEN_GROUP",
                message = "User is not a member of group ${envelope.groupId}",
            )
            return
        }

        val payload = json.decodeFromJsonElement(
            MessageSendPayload.serializer(),
            requireNotNull(envelope.payload),
        )

        val runKey = runKey(userId, envelope.groupId, envelope.clientMessageId)
        runs[runKey]?.let {
            replayCachedRun(session, it)
            return
        }

        val storedEvents = chatStore.findRunEvents(
            userId = userId,
            groupId = envelope.groupId,
            clientMessageId = envelope.clientMessageId,
        )
        if (storedEvents.isNotEmpty()) {
            storedEvents.forEach { session.sendEnvelope(it) }
            return
        }

        val userMessageId = nextId("msg_srv_user")
        val assistantMessageId = nextId("msg_srv_assistant")
        val run = CachedRun(
            requestId = nextId("req"),
            userMessageId = userMessageId,
            assistantMessageId = assistantMessageId,
        )
        val previous = runs.putIfAbsent(runKey, run)
        if (previous != null) {
            replayCachedRun(session, previous)
            return
        }

        try {
            chatStore.saveIncomingUserMessage(
                userId = userId,
                envelope = envelope,
                userMessageId = userMessageId,
                payload = payload,
            )

            val request = ChannelSendRequest(
                requestId = run.requestId,
                conversationId = openClawConversationId(envelope.groupId),
                clientMessageId = envelope.clientMessageId,
                assistantMessageId = assistantMessageId,
                traceId = envelope.traceId.ifBlank { nextTraceId("send") },
                userId = userId,
                payload = payload,
            )

            run.requestId = channelGateway.submit(request)

            persistAndSend(
                session = session,
                run = run,
                userId = userId,
                envelope = envelope.serverEvent(
                    event = "message.ack",
                    messageId = userMessageId,
                    payload = AckPayload(true),
                ),
            )

            persistAndSend(
                session = session,
                run = run,
                userId = userId,
                envelope = envelope.serverEvent(
                    event = "message.start",
                    messageId = assistantMessageId,
                    payload = RolePayload("assistant"),
                ),
            )
            run.assistantStarted = true

            channelGateway.streamEvents(run.requestId).collect { event ->
                forwardChannelEvent(session, envelope, run, event, userId)
            }
        } catch (ex: Exception) {
            logger.error(ex) { "Failed to bridge message.send" }
            val errorEnvelope = envelope.serverEvent(
                event = "message.error",
                messageId = run.errorMessageId(),
                payload = ErrorPayload("CHANNEL_BRIDGE_FAILED", ex.message ?: "Bridge request failed"),
            )
            runCatching {
                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = errorEnvelope,
                )
            }.getOrElse {
                logger.error(it) { "Failed to persist error event; falling back to direct send" }
                session.sendEnvelope(errorEnvelope)
            }
            run.completed = true
        } finally {
            if (run.completed) {
                runs.remove(runKey)
            }
        }
    }

    private suspend fun forwardChannelEvent(
        session: WebSocketServerSession,
        sourceEnvelope: ChatEnvelope,
        run: CachedRun,
        event: ChannelStreamEvent,
        userId: String,
    ) {
        when (event) {
            is ChannelStreamEvent.TextDelta -> {
                val seq = ++run.textSeq
                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = sourceEnvelope.serverEvent(
                        event = "message.delta",
                        messageId = run.assistantMessageId,
                        cardId = event.cardId,
                        seq = seq,
                        payload = DeltaPayload(event.delta),
                    ),
                )
            }

            is ChannelStreamEvent.ImageCard -> {
                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = sourceEnvelope.serverEvent(
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
                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = sourceEnvelope.serverEvent(
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
                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = sourceEnvelope.serverEvent(
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
                    persistAndSend(
                        session = session,
                        run = run,
                        userId = userId,
                        envelope = sourceEnvelope.serverEvent(
                            event = "audio.output.complete",
                            messageId = run.assistantMessageId,
                            payload = null,
                        ),
                    )
                }

                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = sourceEnvelope.serverEvent(
                        event = "message.complete",
                        messageId = run.assistantMessageId,
                        seq = run.textSeq + 1,
                        payload = CompletePayload(event.finishReason),
                    ),
                )
                run.completed = true
            }

            is ChannelStreamEvent.Error -> {
                persistAndSend(
                    session = session,
                    run = run,
                    userId = userId,
                    envelope = sourceEnvelope.serverEvent(
                        event = "message.error",
                        messageId = run.errorMessageId(),
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

    private suspend fun persistAndSend(
        session: WebSocketServerSession,
        run: CachedRun,
        userId: String,
        envelope: ChatEnvelope,
    ) {
        val persisted = chatStore.appendEvent(userId, envelope)
        run.events += persisted
        session.sendEnvelope(persisted)
    }

    private suspend fun WebSocketServerSession.sendEnvelope(envelope: ChatEnvelope) {
        send(Frame.Text(json.encodeToString(ChatEnvelope.serializer(), envelope)))
    }

    private suspend fun WebSocketServerSession.sendError(
        groupId: String,
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
                groupId = groupId,
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
            groupId = groupId,
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

    private fun runKey(userId: String, groupId: String, clientMessageId: String): String =
        "$userId::$groupId::$clientMessageId"

    private fun openClawConversationId(groupId: String): String = "grp_$groupId"

    private fun CachedRun.errorMessageId(): String =
        if (assistantStarted) assistantMessageId else userMessageId

    private fun nextId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    private fun nextTraceId(prefix: String): String = "${prefix}_${System.currentTimeMillis()}"

    private fun now(): Long = System.currentTimeMillis()
}
