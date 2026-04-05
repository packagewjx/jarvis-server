package jarvis.server.persistence

import jarvis.server.model.ChatEnvelope
import jarvis.server.model.MessageSendPayload

data class UserCredential(
    val userId: String,
    val username: String,
    val passwordHash: String,
)

data class UserProfile(
    val userId: String,
    val username: String,
)

data class GroupSummary(
    val groupId: String,
    val name: String,
)

data class GroupMembership(
    val groupId: String,
    val groupName: String,
    val joinedAt: Long,
)

data class ChatEventsPage(
    val items: List<ChatEnvelope>,
    val nextAfterEventId: Long,
    val hasMore: Boolean,
)

interface ChatStore {
    suspend fun createUser(username: String, passwordHash: String): UserProfile?

    suspend fun findUserCredentialByUsername(username: String): UserCredential?

    suspend fun findUserProfile(userId: String): UserProfile?

    suspend fun joinGroupByInvite(userId: String, joinCode: String): GroupMembership?

    suspend fun listJoinedGroups(userId: String): List<GroupSummary>

    suspend fun isUserInGroup(userId: String, groupId: String): Boolean

    suspend fun saveIncomingUserMessage(
        userId: String,
        envelope: ChatEnvelope,
        userMessageId: String,
        payload: MessageSendPayload,
    )

    suspend fun findRunEvents(
        userId: String,
        groupId: String,
        clientMessageId: String,
    ): List<ChatEnvelope>

    suspend fun appendEvent(userId: String, envelope: ChatEnvelope): ChatEnvelope

    suspend fun listGroupEvents(
        userId: String,
        groupId: String,
        afterEventId: Long,
        limit: Int,
    ): ChatEventsPage

    suspend fun cleanupExpired(retentionDays: Int): Int
}
