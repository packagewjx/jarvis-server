package jarvis.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatEnvelope(
    val event: String,
    @SerialName("trace_id") val traceId: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("message_id") val messageId: String = "",
    @SerialName("client_message_id") val clientMessageId: String = "",
    @SerialName("card_id") val cardId: String = "",
    val seq: Long = 0,
    val timestamp: Long,
    @SerialName("event_id") val eventId: Long = 0,
    val payload: JsonElement? = null,
)

@Serializable
data class MessageSendPayload(
    val role: String,
    val cards: List<MessageCard>,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("input_mode") val inputMode: String = "text",
    val command: MessageCommandPayload? = null,
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
data class MessageCommandPayload(
    val name: String,
    val args: List<String> = emptyList(),
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
data class ChatEventsSyncResponse(
    val items: List<ChatEnvelope>,
    @SerialName("next_after_event_id") val nextAfterEventId: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class AuthRegisterRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthLoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class AuthRefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class AuthUserPayload(
    @SerialName("user_id") val userId: String,
    val username: String,
)

@Serializable
data class AuthTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("session_id") val sessionId: String,
    val user: AuthUserPayload? = null,
)

@Serializable
data class AuthLogoutRequest(
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("all_devices") val allDevices: Boolean = false,
)

@Serializable
data class AuthRevokeRequest(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("all_devices") val allDevices: Boolean = false,
)

@Serializable
data class GenericSuccessResponse(
    val success: Boolean = true,
)

@Serializable
data class UserMeResponse(
    val user: UserProfilePayload,
)

@Serializable
data class UserProfilePayload(
    @SerialName("user_id") val userId: String,
    val username: String,
    val email: String? = null,
    val phone: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean = false,
    @SerialName("phone_verified") val phoneVerified: Boolean = false,
)

@Serializable
data class UserSessionPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("revoked_at") val revokedAt: Long? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    val ip: String? = null,
)

@Serializable
data class UserSessionListResponse(
    val items: List<UserSessionPayload>,
)

@Serializable
data class PasswordChangeRequest(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class PasswordForgotRequest(
    val username: String,
)

@Serializable
data class PasswordForgotResponse(
    @SerialName("reset_token") val resetToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class PasswordResetRequest(
    @SerialName("reset_token") val resetToken: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class VerifySendRequest(
    val channel: String,
    val target: String,
)

@Serializable
data class VerifySendResponse(
    @SerialName("challenge_id") val challengeId: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("dev_code") val devCode: String? = null,
)

@Serializable
data class VerifyConfirmRequest(
    @SerialName("challenge_id") val challengeId: String,
    val code: String,
)

@Serializable
data class JoinGroupRequest(
    @SerialName("join_code") val joinCode: String,
)

@Serializable
data class GroupPayload(
    @SerialName("group_id") val groupId: String,
    val name: String,
)

@Serializable
data class GroupListResponse(
    val items: List<GroupPayload>,
)

@Serializable
data class GroupMembershipPayload(
    @SerialName("joined_at") val joinedAt: Long,
)

@Serializable
data class JoinGroupResponse(
    val group: GroupPayload,
    val membership: GroupMembershipPayload,
)

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
