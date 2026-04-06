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
    val email: String? = null,
    val phone: String? = null,
    val emailVerified: Boolean = false,
    val phoneVerified: Boolean = false,
)

data class UserSession(
    val sessionId: String,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val revokedAt: Long? = null,
    val userAgent: String? = null,
    val ip: String? = null,
)

data class VerificationChallenge(
    val challengeId: String,
    val userId: String,
    val channel: String,
    val target: String,
    val expiresAt: Long,
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

data class CreatedGroup(
    val groupId: String,
    val groupName: String,
    val joinedAt: Long,
    val joinCode: String,
)

data class ChatEventsPage(
    val items: List<ChatEnvelope>,
    val nextAfterEventId: Long,
    val hasMore: Boolean,
)

interface ChatStore {
    suspend fun createUser(username: String, passwordHash: String): UserProfile?

    suspend fun findUserCredentialByUsername(username: String): UserCredential?

    suspend fun findUserCredentialByUserId(userId: String): UserCredential?

    suspend fun findUserProfile(userId: String): UserProfile?

    suspend fun updateUserPassword(userId: String, passwordHash: String, updatedAt: Long): Boolean

    suspend fun createSession(
        userId: String,
        refreshTokenHash: String,
        refreshExpiresAt: Long,
        userAgent: String?,
        ip: String?,
    ): UserSession

    suspend fun rotateSessionRefresh(
        sessionId: String,
        refreshTokenHash: String,
        refreshExpiresAt: Long,
        updatedAt: Long,
    ): Boolean

    suspend fun findSession(sessionId: String): UserSession?

    suspend fun getSessionRefreshTokenHash(sessionId: String): String?

    suspend fun listSessions(userId: String): List<UserSession>

    suspend fun revokeSession(sessionId: String, revokedAt: Long): Boolean

    suspend fun revokeAllSessions(userId: String, revokedAt: Long, exceptSessionId: String? = null): Int

    suspend fun saveRevokedToken(jti: String, expiresAt: Long)

    suspend fun isTokenRevoked(jti: String): Boolean

    suspend fun createPasswordResetToken(userId: String, tokenHash: String, expiresAt: Long, createdAt: Long)

    suspend fun consumePasswordResetToken(tokenHash: String, now: Long): String?

    suspend fun createVerificationChallenge(
        userId: String,
        channel: String,
        target: String,
        codeHash: String,
        expiresAt: Long,
        createdAt: Long,
    ): VerificationChallenge

    suspend fun consumeVerificationChallenge(challengeId: String, codeHash: String, now: Long): VerificationChallenge?

    suspend fun markUserVerified(userId: String, channel: String, target: String, updatedAt: Long): Boolean

    suspend fun createGroupForUser(userId: String, groupName: String): CreatedGroup?

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
