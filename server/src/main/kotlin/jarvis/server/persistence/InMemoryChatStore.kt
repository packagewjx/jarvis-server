package jarvis.server.persistence

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import jarvis.server.model.ChatEnvelope
import jarvis.server.model.MessageSendPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class PasswordResetRecord(
    val tokenHash: String,
    val userId: String,
    val expiresAt: Long,
    val createdAt: Long,
    var usedAt: Long? = null,
)

private data class VerificationChallengeRecord(
    val challenge: VerificationChallenge,
    val codeHash: String,
    val createdAt: Long,
    var usedAt: Long? = null,
)

private data class StoredSession(
    val session: UserSession,
    val refreshTokenHash: String,
)

class InMemoryChatStore : ChatStore {
    private val mutex = Mutex()
    private val nextEventId = AtomicLong(1)
    private val usersById = linkedMapOf<String, UserProfile>()
    private val usersByName = linkedMapOf<String, String>()
    private val credentialsByUserId = linkedMapOf<String, String>()
    private val groupsById = linkedMapOf<String, GroupSummary>()
    private val memberships = linkedMapOf<String, MutableSet<String>>()
    private val inviteCodeToGroup = linkedMapOf<String, String>()
    private val eventsByGroup = linkedMapOf<String, MutableList<ChatEnvelope>>()
    private val eventsByRun = linkedMapOf<String, MutableList<ChatEnvelope>>()
    private val sessionsById = linkedMapOf<String, StoredSession>()
    private val revokedTokensByJti = linkedMapOf<String, Long>()
    private val resetTokens = linkedMapOf<String, PasswordResetRecord>()
    private val verificationById = linkedMapOf<String, VerificationChallengeRecord>()

    init {
        groupsById["g_default"] = GroupSummary(groupId = "g_default", name = "Default Group")
        inviteCodeToGroup["DEFAULT-GROUP"] = "g_default"
    }

    override suspend fun createUser(username: String, passwordHash: String): UserProfile? {
        return mutex.withLock {
            if (usersByName.containsKey(username)) {
                return@withLock null
            }
            val userId = "u_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            val profile = UserProfile(userId = userId, username = username)
            usersById[userId] = profile
            usersByName[username] = userId
            credentialsByUserId[userId] = passwordHash
            profile
        }
    }

    override suspend fun findUserCredentialByUsername(username: String): UserCredential? {
        return mutex.withLock {
            val userId = usersByName[username] ?: return@withLock null
            val profile = usersById[userId] ?: return@withLock null
            val passwordHash = credentialsByUserId[userId] ?: return@withLock null
            UserCredential(
                userId = profile.userId,
                username = profile.username,
                passwordHash = passwordHash,
            )
        }
    }

    override suspend fun findUserCredentialByUserId(userId: String): UserCredential? {
        return mutex.withLock {
            val profile = usersById[userId] ?: return@withLock null
            val passwordHash = credentialsByUserId[userId] ?: return@withLock null
            UserCredential(
                userId = profile.userId,
                username = profile.username,
                passwordHash = passwordHash,
            )
        }
    }

    override suspend fun findUserProfile(userId: String): UserProfile? {
        return mutex.withLock { usersById[userId] }
    }

    override suspend fun updateUserPassword(userId: String, passwordHash: String, updatedAt: Long): Boolean {
        return mutex.withLock {
            if (!usersById.containsKey(userId)) {
                return@withLock false
            }
            credentialsByUserId[userId] = passwordHash
            true
        }
    }

    override suspend fun createSession(
        userId: String,
        refreshTokenHash: String,
        refreshExpiresAt: Long,
        userAgent: String?,
        ip: String?,
    ): UserSession {
        return mutex.withLock {
            val now = System.currentTimeMillis()
            val session = UserSession(
                sessionId = "s_${UUID.randomUUID().toString().replace("-", "")}",
                userId = userId,
                createdAt = now,
                updatedAt = now,
                expiresAt = refreshExpiresAt,
                revokedAt = null,
                userAgent = userAgent,
                ip = ip,
            )
            sessionsById[session.sessionId] = StoredSession(session = session, refreshTokenHash = refreshTokenHash)
            session
        }
    }

    override suspend fun rotateSessionRefresh(
        sessionId: String,
        refreshTokenHash: String,
        refreshExpiresAt: Long,
        updatedAt: Long,
    ): Boolean {
        return mutex.withLock {
            val current = sessionsById[sessionId] ?: return@withLock false
            if (current.session.revokedAt != null) {
                return@withLock false
            }
            val next = current.session.copy(updatedAt = updatedAt, expiresAt = refreshExpiresAt)
            sessionsById[sessionId] = StoredSession(next, refreshTokenHash)
            true
        }
    }

    override suspend fun findSession(sessionId: String): UserSession? {
        return mutex.withLock { sessionsById[sessionId]?.session }
    }

    override suspend fun getSessionRefreshTokenHash(sessionId: String): String? {
        return mutex.withLock { sessionsById[sessionId]?.refreshTokenHash }
    }

    override suspend fun listSessions(userId: String): List<UserSession> {
        return mutex.withLock {
            sessionsById.values
                .map { it.session }
                .filter { it.userId == userId }
                .sortedByDescending { it.updatedAt }
        }
    }

    override suspend fun revokeSession(sessionId: String, revokedAt: Long): Boolean {
        return mutex.withLock {
            val stored = sessionsById[sessionId] ?: return@withLock false
            if (stored.session.revokedAt != null) {
                return@withLock true
            }
            sessionsById[sessionId] = stored.copy(session = stored.session.copy(revokedAt = revokedAt, updatedAt = revokedAt))
            true
        }
    }

    override suspend fun revokeAllSessions(userId: String, revokedAt: Long, exceptSessionId: String?): Int {
        return mutex.withLock {
            var count = 0
            sessionsById.entries.forEach { (sessionId, stored) ->
                if (stored.session.userId != userId) {
                    return@forEach
                }
                if (exceptSessionId != null && exceptSessionId == sessionId) {
                    return@forEach
                }
                if (stored.session.revokedAt == null) {
                    sessionsById[sessionId] = stored.copy(
                        session = stored.session.copy(revokedAt = revokedAt, updatedAt = revokedAt),
                    )
                    count += 1
                }
            }
            count
        }
    }

    override suspend fun saveRevokedToken(jti: String, expiresAt: Long) {
        mutex.withLock {
            revokedTokensByJti[jti] = expiresAt
        }
    }

    override suspend fun isTokenRevoked(jti: String): Boolean {
        return mutex.withLock {
            val expiresAt = revokedTokensByJti[jti] ?: return@withLock false
            if (expiresAt <= System.currentTimeMillis()) {
                revokedTokensByJti.remove(jti)
                return@withLock false
            }
            true
        }
    }

    override suspend fun createPasswordResetToken(userId: String, tokenHash: String, expiresAt: Long, createdAt: Long) {
        mutex.withLock {
            resetTokens[tokenHash] = PasswordResetRecord(
                tokenHash = tokenHash,
                userId = userId,
                expiresAt = expiresAt,
                createdAt = createdAt,
            )
        }
    }

    override suspend fun consumePasswordResetToken(tokenHash: String, now: Long): String? {
        return mutex.withLock {
            val record = resetTokens[tokenHash] ?: return@withLock null
            if (record.usedAt != null || record.expiresAt <= now) {
                return@withLock null
            }
            record.usedAt = now
            record.userId
        }
    }

    override suspend fun createVerificationChallenge(
        userId: String,
        channel: String,
        target: String,
        codeHash: String,
        expiresAt: Long,
        createdAt: Long,
    ): VerificationChallenge {
        return mutex.withLock {
            val challenge = VerificationChallenge(
                challengeId = "vc_${UUID.randomUUID().toString().replace("-", "")}",
                userId = userId,
                channel = channel,
                target = target,
                expiresAt = expiresAt,
            )
            verificationById[challenge.challengeId] = VerificationChallengeRecord(
                challenge = challenge,
                codeHash = codeHash,
                createdAt = createdAt,
            )
            challenge
        }
    }

    override suspend fun consumeVerificationChallenge(challengeId: String, codeHash: String, now: Long): VerificationChallenge? {
        return mutex.withLock {
            val record = verificationById[challengeId] ?: return@withLock null
            if (record.usedAt != null || record.challenge.expiresAt <= now) {
                return@withLock null
            }
            if (record.codeHash != codeHash) {
                return@withLock null
            }
            record.usedAt = now
            record.challenge
        }
    }

    override suspend fun markUserVerified(userId: String, channel: String, target: String, updatedAt: Long): Boolean {
        return mutex.withLock {
            val profile = usersById[userId] ?: return@withLock false
            val next = when (channel.lowercase()) {
                "email" -> profile.copy(email = target, emailVerified = true)
                "phone" -> profile.copy(phone = target, phoneVerified = true)
                else -> return@withLock false
            }
            usersById[userId] = next
            true
        }
    }

    override suspend fun joinGroupByInvite(userId: String, joinCode: String): GroupMembership? {
        return mutex.withLock {
            val groupId = inviteCodeToGroup[joinCode] ?: return@withLock null
            val group = groupsById[groupId] ?: return@withLock null
            val joinedAt = System.currentTimeMillis()
            val memberGroups = memberships.getOrPut(userId) { linkedSetOf() }
            memberGroups += groupId
            GroupMembership(
                groupId = group.groupId,
                groupName = group.name,
                joinedAt = joinedAt,
            )
        }
    }

    override suspend fun listJoinedGroups(userId: String): List<GroupSummary> {
        return mutex.withLock {
            val groupIds = memberships[userId] ?: return@withLock emptyList()
            groupIds.mapNotNull { groupsById[it] }
        }
    }

    override suspend fun isUserInGroup(userId: String, groupId: String): Boolean {
        return mutex.withLock {
            memberships[userId]?.contains(groupId) == true
        }
    }

    override suspend fun saveIncomingUserMessage(
        userId: String,
        envelope: ChatEnvelope,
        userMessageId: String,
        payload: MessageSendPayload,
    ) {
        // No-op for tests and fallback mode.
    }

    override suspend fun findRunEvents(
        userId: String,
        groupId: String,
        clientMessageId: String,
    ): List<ChatEnvelope> {
        if (clientMessageId.isBlank()) {
            return emptyList()
        }
        return mutex.withLock {
            eventsByRun[runKey(userId, groupId, clientMessageId)]?.toList() ?: emptyList()
        }
    }

    override suspend fun appendEvent(userId: String, envelope: ChatEnvelope): ChatEnvelope {
        return mutex.withLock {
            val eventId = if (envelope.eventId > 0) envelope.eventId else nextEventId.getAndIncrement()
            val persisted = envelope.copy(eventId = eventId)
            val groupEvents = eventsByGroup.getOrPut(groupKey(envelope.groupId)) { mutableListOf() }
            groupEvents += persisted
            if (persisted.clientMessageId.isNotBlank()) {
                eventsByRun
                    .getOrPut(runKey(userId, persisted.groupId, persisted.clientMessageId)) { mutableListOf() }
                    .add(persisted)
            }
            persisted
        }
    }

    override suspend fun listGroupEvents(
        userId: String,
        groupId: String,
        afterEventId: Long,
        limit: Int,
    ): ChatEventsPage {
        return mutex.withLock {
            val normalizedLimit = normalizeLimit(limit)
            val events = eventsByGroup[groupKey(groupId)] ?: emptyList()
            val filtered = events.filter { it.eventId > afterEventId }.sortedBy { it.eventId }
            val sliced = filtered.take(normalizedLimit + 1)
            val hasMore = sliced.size > normalizedLimit
            val items = if (hasMore) sliced.take(normalizedLimit) else sliced
            ChatEventsPage(
                items = items,
                nextAfterEventId = items.lastOrNull()?.eventId ?: afterEventId,
                hasMore = hasMore,
            )
        }
    }

    override suspend fun cleanupExpired(retentionDays: Int): Int = 0

    private fun normalizeLimit(limit: Int): Int = limit.coerceIn(1, 500)

    private fun groupKey(groupId: String): String = groupId

    private fun runKey(userId: String, groupId: String, clientMessageId: String): String =
        "$userId::$groupId::$clientMessageId"
}
