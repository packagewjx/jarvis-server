package jarvis.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatEnvelope(
    val event: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("message_id") val messageId: String = "",
    @SerialName("client_message_id") val clientMessageId: String = "",
    @SerialName("card_id") val cardId: String = "",
    val seq: Long = 0,
    val timestamp: Long,
    val payload: JsonElement? = null,
)

@Serializable
data class MessageSendPayload(
    val role: String,
    val cards: List<MessageCard>,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class MessageCard(
    val id: String,
    val cardType: String,
    val text: String = "",
    val imageUrl: String = "",
    val audioUrl: String = "",
    val audioMime: String = "",
    val durationMs: Long = 0,
    val extra: JsonElement? = null,
)

@Serializable
data class AckPayload(val accepted: Boolean)

@Serializable
data class RolePayload(val role: String)

@Serializable
data class DeltaPayload(val delta: String)

@Serializable
data class ReplaceCardPayload(
    @SerialName("card_type") val cardType: String,
    val url: String? = null,
    val caption: String? = null,
    val mime: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

@Serializable
data class AudioChunkPayload(
    val mime: String,
    @SerialName("duration_ms") val durationMs: Long,
    val base64: String,
)

@Serializable
data class CompletePayload(@SerialName("finish_reason") val finishReason: String)

@Serializable
data class ErrorPayload(val code: String, val message: String)

@Serializable
data class WelcomePayload(
    @SerialName("user_id") val userId: String,
    @SerialName("server_time") val serverTime: Long,
)

@Serializable
data class ChannelSendRequest(
    val requestId: String,
    val conversationId: String,
    val clientMessageId: String,
    val assistantMessageId: String,
    val traceId: String,
    val userId: String,
    val payload: MessageSendPayload,
)

@Serializable
data class ChannelSendAccepted(val requestId: String)

@Serializable
sealed interface ChannelStreamEvent {
    val requestId: String
    val conversationId: String
    val clientMessageId: String
    val assistantMessageId: String
    val seq: Long

    @Serializable
    @SerialName("text.delta")
    data class TextDelta(
        override val requestId: String,
        override val conversationId: String,
        override val clientMessageId: String,
        override val assistantMessageId: String,
        override val seq: Long,
        val delta: String,
        val cardId: String = "card_text_main",
    ) : ChannelStreamEvent

    @Serializable
    @SerialName("card.image")
    data class ImageCard(
        override val requestId: String,
        override val conversationId: String,
        override val clientMessageId: String,
        override val assistantMessageId: String,
        override val seq: Long,
        val cardId: String,
        val url: String,
        val caption: String? = null,
    ) : ChannelStreamEvent

    @Serializable
    @SerialName("card.audio")
    data class AudioCard(
        override val requestId: String,
        override val conversationId: String,
        override val clientMessageId: String,
        override val assistantMessageId: String,
        override val seq: Long,
        val cardId: String,
        val mime: String,
        val durationMs: Long = 0,
    ) : ChannelStreamEvent

    @Serializable
    @SerialName("audio.chunk")
    data class AudioChunk(
        override val requestId: String,
        override val conversationId: String,
        override val clientMessageId: String,
        override val assistantMessageId: String,
        override val seq: Long,
        val cardId: String,
        val mime: String,
        val base64: String,
        val durationMs: Long,
    ) : ChannelStreamEvent

    @Serializable
    @SerialName("message.complete")
    data class Complete(
        override val requestId: String,
        override val conversationId: String,
        override val clientMessageId: String,
        override val assistantMessageId: String,
        override val seq: Long,
        val finishReason: String = "stop",
    ) : ChannelStreamEvent

    @Serializable
    @SerialName("message.error")
    data class Error(
        override val requestId: String,
        override val conversationId: String,
        override val clientMessageId: String,
        override val assistantMessageId: String,
        override val seq: Long,
        val code: String,
        val message: String,
    ) : ChannelStreamEvent
}
