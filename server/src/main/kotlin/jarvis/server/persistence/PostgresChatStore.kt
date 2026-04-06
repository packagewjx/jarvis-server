package jarvis.server.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import javax.sql.DataSource
import jarvis.server.model.AudioChunkPayload
import jarvis.server.model.ChatEnvelope
import jarvis.server.model.DeltaPayload
import jarvis.server.model.MessageSendPayload
import jarvis.server.model.ReplaceCardPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class PostgresChatStore(
    private val dataSource: DataSource,
    private val json: Json,
) : ChatStore {
    private val logger = KotlinLogging.logger {}

    override suspend fun createUser(username: String, passwordHash: String): UserProfile? = withContext(Dispatchers.IO) {
        val userId = nextUserId()
        val now = System.currentTimeMillis()
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO app_users (
                    user_id, username, password_hash, email, phone, email_verified, phone_verified, created_at, updated_at
                ) VALUES (?, ?, ?, NULL, NULL, FALSE, FALSE, ?, ?)
                ON CONFLICT (username) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, username)
                statement.setString(3, passwordHash)
                statement.setLong(4, now)
                statement.setLong(5, now)
                val inserted = statement.executeUpdate()
                if (inserted == 0) {
                    null
                } else {
                    UserProfile(userId = userId, username = username)
                }
            }
        }
    }

    override suspend fun findUserCredentialByUsername(username: String): UserCredential? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id, username, password_hash
                FROM app_users
                WHERE username = ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, username)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        UserCredential(
                            userId = rs.getString("user_id"),
                            username = rs.getString("username"),
                            passwordHash = rs.getString("password_hash"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun findUserCredentialByUserId(userId: String): UserCredential? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id, username, password_hash
                FROM app_users
                WHERE user_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        UserCredential(
                            userId = rs.getString("user_id"),
                            username = rs.getString("username"),
                            passwordHash = rs.getString("password_hash"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun findUserProfile(userId: String): UserProfile? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id, username, email, phone, email_verified, phone_verified
                FROM app_users
                WHERE user_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        UserProfile(
                            userId = rs.getString("user_id"),
                            username = rs.getString("username"),
                            email = rs.getString("email"),
                            phone = rs.getString("phone"),
                            emailVerified = rs.getBoolean("email_verified"),
                            phoneVerified = rs.getBoolean("phone_verified"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun updateUserPassword(userId: String, passwordHash: String, updatedAt: Long): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    UPDATE app_users
                    SET password_hash = ?, updated_at = ?
                    WHERE user_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, passwordHash)
                    statement.setLong(2, updatedAt)
                    statement.setString(3, userId)
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun createSession(
        userId: String,
        refreshTokenHash: String,
        refreshExpiresAt: Long,
        userAgent: String?,
        ip: String?,
    ): UserSession = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val sessionId = "s_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO auth_sessions (
                    session_id, user_id, refresh_token_hash, created_at, updated_at, expires_at, revoked_at, user_agent, ip
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, userId)
                statement.setString(3, refreshTokenHash)
                statement.setLong(4, now)
                statement.setLong(5, now)
                statement.setLong(6, refreshExpiresAt)
                statement.setString(7, userAgent)
                statement.setString(8, ip)
                statement.executeUpdate()
            }
        }
        UserSession(
            sessionId = sessionId,
            userId = userId,
            createdAt = now,
            updatedAt = now,
            expiresAt = refreshExpiresAt,
            revokedAt = null,
            userAgent = userAgent,
            ip = ip,
        )
    }

    override suspend fun rotateSessionRefresh(
        sessionId: String,
        refreshTokenHash: String,
        refreshExpiresAt: Long,
        updatedAt: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE auth_sessions
                SET refresh_token_hash = ?, expires_at = ?, updated_at = ?
                WHERE session_id = ? AND revoked_at IS NULL
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, refreshTokenHash)
                statement.setLong(2, refreshExpiresAt)
                statement.setLong(3, updatedAt)
                statement.setString(4, sessionId)
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun findSession(sessionId: String): UserSession? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT session_id, user_id, created_at, updated_at, expires_at, revoked_at, user_agent, ip
                FROM auth_sessions
                WHERE session_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) {
                        null
                    } else {
                        UserSession(
                            sessionId = rs.getString("session_id"),
                            userId = rs.getString("user_id"),
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            expiresAt = rs.getLong("expires_at"),
                            revokedAt = rs.getLong("revoked_at").let { if (rs.wasNull()) null else it },
                            userAgent = rs.getString("user_agent"),
                            ip = rs.getString("ip"),
                        )
                    }
                }
            }
        }
    }

    override suspend fun getSessionRefreshTokenHash(sessionId: String): String? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT refresh_token_hash
                FROM auth_sessions
                WHERE session_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString("refresh_token_hash")
                }
            }
        }
    }

    override suspend fun listSessions(userId: String): List<UserSession> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT session_id, user_id, created_at, updated_at, expires_at, revoked_at, user_agent, ip
                FROM auth_sessions
                WHERE user_id = ?
                ORDER BY updated_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rs ->
                    val sessions = mutableListOf<UserSession>()
                    while (rs.next()) {
                        sessions += UserSession(
                            sessionId = rs.getString("session_id"),
                            userId = rs.getString("user_id"),
                            createdAt = rs.getLong("created_at"),
                            updatedAt = rs.getLong("updated_at"),
                            expiresAt = rs.getLong("expires_at"),
                            revokedAt = rs.getLong("revoked_at").let { if (rs.wasNull()) null else it },
                            userAgent = rs.getString("user_agent"),
                            ip = rs.getString("ip"),
                        )
                    }
                    sessions
                }
            }
        }
    }

    override suspend fun revokeSession(sessionId: String, revokedAt: Long): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE auth_sessions
                SET revoked_at = COALESCE(revoked_at, ?), updated_at = ?
                WHERE session_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, revokedAt)
                statement.setLong(2, revokedAt)
                statement.setString(3, sessionId)
                statement.executeUpdate() > 0
            }
        }
    }

    override suspend fun revokeAllSessions(userId: String, revokedAt: Long, exceptSessionId: String?): Int =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                val sql = if (exceptSessionId.isNullOrBlank()) {
                    """
                    UPDATE auth_sessions
                    SET revoked_at = COALESCE(revoked_at, ?), updated_at = ?
                    WHERE user_id = ? AND revoked_at IS NULL
                    """.trimIndent()
                } else {
                    """
                    UPDATE auth_sessions
                    SET revoked_at = COALESCE(revoked_at, ?), updated_at = ?
                    WHERE user_id = ? AND revoked_at IS NULL AND session_id <> ?
                    """.trimIndent()
                }
                connection.prepareStatement(sql).use { statement ->
                    statement.setLong(1, revokedAt)
                    statement.setLong(2, revokedAt)
                    statement.setString(3, userId)
                    if (!exceptSessionId.isNullOrBlank()) {
                        statement.setString(4, exceptSessionId)
                    }
                    statement.executeUpdate()
                }
            }
        }

    override suspend fun saveRevokedToken(jti: String, expiresAt: Long) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO auth_revoked_tokens (jti, expires_at, created_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (jti) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, jti)
                    statement.setLong(2, expiresAt)
                    statement.setLong(3, System.currentTimeMillis())
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun isTokenRevoked(jti: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM auth_revoked_tokens
                WHERE jti = ? AND expires_at > ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, jti)
                statement.setLong(2, System.currentTimeMillis())
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override suspend fun createPasswordResetToken(userId: String, tokenHash: String, expiresAt: Long, createdAt: Long) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO auth_password_reset_tokens (token_hash, user_id, expires_at, created_at, used_at)
                    VALUES (?, ?, ?, ?, NULL)
                    ON CONFLICT (token_hash) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.setString(2, userId)
                    statement.setLong(3, expiresAt)
                    statement.setLong(4, createdAt)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun consumePasswordResetToken(tokenHash: String, now: Long): String? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val userId = connection.prepareStatement(
                    """
                    SELECT user_id
                    FROM auth_password_reset_tokens
                    WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?
                    LIMIT 1
                    FOR UPDATE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.setLong(2, now)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) null else rs.getString("user_id")
                    }
                } ?: run {
                    connection.rollback()
                    return@withContext null
                }
                connection.prepareStatement(
                    """
                    UPDATE auth_password_reset_tokens
                    SET used_at = ?
                    WHERE token_hash = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, now)
                    statement.setString(2, tokenHash)
                    statement.executeUpdate()
                }
                connection.commit()
                userId
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            }
        }
    }

    override suspend fun createVerificationChallenge(
        userId: String,
        channel: String,
        target: String,
        codeHash: String,
        expiresAt: Long,
        createdAt: Long,
    ): VerificationChallenge = withContext(Dispatchers.IO) {
        val challengeId = "vc_${UUID.randomUUID().toString().replace("-", "")}"
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO auth_verification_challenges (
                    challenge_id, user_id, channel, target, code_hash, expires_at, created_at, used_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, challengeId)
                statement.setString(2, userId)
                statement.setString(3, channel)
                statement.setString(4, target)
                statement.setString(5, codeHash)
                statement.setLong(6, expiresAt)
                statement.setLong(7, createdAt)
                statement.executeUpdate()
            }
        }
        VerificationChallenge(
            challengeId = challengeId,
            userId = userId,
            channel = channel,
            target = target,
            expiresAt = expiresAt,
        )
    }

    override suspend fun consumeVerificationChallenge(challengeId: String, codeHash: String, now: Long): VerificationChallenge? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val challenge = connection.prepareStatement(
                        """
                        SELECT challenge_id, user_id, channel, target, expires_at
                        FROM auth_verification_challenges
                        WHERE challenge_id = ? AND code_hash = ? AND used_at IS NULL AND expires_at > ?
                        LIMIT 1
                        FOR UPDATE
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, challengeId)
                        statement.setString(2, codeHash)
                        statement.setLong(3, now)
                        statement.executeQuery().use { rs ->
                            if (!rs.next()) {
                                null
                            } else {
                                VerificationChallenge(
                                    challengeId = rs.getString("challenge_id"),
                                    userId = rs.getString("user_id"),
                                    channel = rs.getString("channel"),
                                    target = rs.getString("target"),
                                    expiresAt = rs.getLong("expires_at"),
                                )
                            }
                        }
                    } ?: run {
                        connection.rollback()
                        return@withContext null
                    }
                    connection.prepareStatement(
                        """
                        UPDATE auth_verification_challenges
                        SET used_at = ?
                        WHERE challenge_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, now)
                        statement.setString(2, challengeId)
                        statement.executeUpdate()
                    }
                    connection.commit()
                    challenge
                } catch (ex: Exception) {
                    connection.rollback()
                    throw ex
                }
            }
        }

    override suspend fun markUserVerified(userId: String, channel: String, target: String, updatedAt: Long): Boolean =
        withContext(Dispatchers.IO) {
            val (field, verifyField) = when (channel.lowercase()) {
                "email" -> "email" to "email_verified"
                "phone" -> "phone" to "phone_verified"
                else -> return@withContext false
            }
            val sql = """
                UPDATE app_users
                SET $field = ?, $verifyField = TRUE, updated_at = ?
                WHERE user_id = ?
            """.trimIndent()
            dataSource.connection.use { connection ->
                connection.prepareStatement(sql).use { statement ->
                    statement.setString(1, target)
                    statement.setLong(2, updatedAt)
                    statement.setString(3, userId)
                    statement.executeUpdate() > 0
                }
            }
        }

    override suspend fun createGroupForUser(userId: String, groupName: String): CreatedGroup? = withContext(Dispatchers.IO) {
        val normalizedName = groupName.trim()
        if (normalizedName.isEmpty()) {
            return@withContext null
        }

        repeat(8) {
            val groupId = nextGroupId()
            val joinCode = nextJoinCode()
            val now = System.currentTimeMillis()
            val created = dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val insertedGroup = connection.prepareStatement(
                        """
                        INSERT INTO chat_groups (group_id, name, created_at, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (group_id) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, groupId)
                        statement.setString(2, normalizedName)
                        statement.setLong(3, now)
                        statement.setLong(4, now)
                        statement.executeUpdate()
                    }
                    if (insertedGroup == 0) {
                        connection.rollback()
                        return@use null
                    }

                    val insertedInvite = connection.prepareStatement(
                        """
                        INSERT INTO group_invites (invite_code, group_id, expires_at, disabled, created_at)
                        VALUES (?, ?, NULL, FALSE, ?)
                        ON CONFLICT (invite_code) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, joinCode)
                        statement.setString(2, groupId)
                        statement.setLong(3, now)
                        statement.executeUpdate()
                    }
                    if (insertedInvite == 0) {
                        connection.rollback()
                        return@use null
                    }

                    connection.prepareStatement(
                        """
                        INSERT INTO group_memberships (user_id, group_id, joined_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT (user_id, group_id) DO NOTHING
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, userId)
                        statement.setString(2, groupId)
                        statement.setLong(3, now)
                        statement.executeUpdate()
                    }

                    connection.commit()
                    CreatedGroup(
                        groupId = groupId,
                        groupName = normalizedName,
                        joinedAt = now,
                        joinCode = joinCode,
                    )
                } catch (ex: Exception) {
                    connection.rollback()
                    throw ex
                }
            }
            if (created != null) {
                return@withContext created
            }
        }
        null
    }

    override suspend fun joinGroupByInvite(userId: String, joinCode: String): GroupMembership? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val group = connection.prepareStatement(
                    """
                    SELECT g.group_id, g.name
                    FROM group_invites i
                    INNER JOIN chat_groups g ON g.group_id = i.group_id
                    WHERE i.invite_code = ?
                      AND i.disabled = FALSE
                      AND (i.expires_at IS NULL OR i.expires_at > ?)
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, joinCode)
                    statement.setLong(2, now)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            GroupSummary(
                                groupId = rs.getString("group_id"),
                                name = rs.getString("name"),
                            )
                        }
                    }
                } ?: run {
                    connection.rollback()
                    return@withContext null
                }

                connection.prepareStatement(
                    """
                    INSERT INTO group_memberships (user_id, group_id, joined_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (user_id, group_id) DO NOTHING
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setString(2, group.groupId)
                    statement.setLong(3, now)
                    statement.executeUpdate()
                }

                val joinedAt = connection.prepareStatement(
                    """
                    SELECT joined_at
                    FROM group_memberships
                    WHERE user_id = ? AND group_id = ?
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setString(2, group.groupId)
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) now else rs.getLong("joined_at")
                    }
                }
                connection.commit()
                GroupMembership(
                    groupId = group.groupId,
                    groupName = group.name,
                    joinedAt = joinedAt,
                )
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            }
        }
    }

    override suspend fun listJoinedGroups(userId: String): List<GroupSummary> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT g.group_id, g.name
                FROM group_memberships m
                INNER JOIN chat_groups g ON g.group_id = m.group_id
                WHERE m.user_id = ?
                ORDER BY m.joined_at DESC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rs ->
                    val groups = mutableListOf<GroupSummary>()
                    while (rs.next()) {
                        groups += GroupSummary(
                            groupId = rs.getString("group_id"),
                            name = rs.getString("name"),
                        )
                    }
                    groups
                }
            }
        }
    }

    override suspend fun isUserInGroup(userId: String, groupId: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM group_memberships
                WHERE user_id = ? AND group_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, groupId)
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    override suspend fun saveIncomingUserMessage(
        userId: String,
        envelope: ChatEnvelope,
        userMessageId: String,
        payload: MessageSendPayload,
    ) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                upsertGroupConversation(connection, envelope.groupId, envelope.timestamp)
                upsertMessage(
                    connection = connection,
                    groupId = envelope.groupId,
                    messageId = userMessageId,
                    role = payload.role,
                    senderUserId = userId,
                    clientMessageId = envelope.clientMessageId,
                    status = "received",
                    createdAt = payload.createdAt,
                    updatedAt = envelope.timestamp,
                )
                payload.cards.forEach { card ->
                    upsertCard(
                        connection = connection,
                        groupId = envelope.groupId,
                        messageId = userMessageId,
                        cardId = card.id,
                        cardType = card.cardType,
                        text = card.text,
                        imageUrl = card.imageUrl,
                        audioUrl = card.audioUrl,
                        audioMime = card.audioMime,
                        durationMs = card.durationMs,
                        extraJson = card.extra?.toString(),
                        updatedAt = envelope.timestamp,
                    )
                }
                connection.commit()
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            }
        }
    }

    override suspend fun findRunEvents(
        userId: String,
        groupId: String,
        clientMessageId: String,
    ): List<ChatEnvelope> = withContext(Dispatchers.IO) {
        if (clientMessageId.isBlank()) {
            return@withContext emptyList()
        }
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT event_id, event, trace_id, group_id, message_id, client_message_id, card_id, seq, timestamp_ms, payload_json
                FROM group_message_events
                WHERE sender_user_id = ? AND group_id = ? AND client_message_id = ?
                ORDER BY event_id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, groupId)
                statement.setString(3, clientMessageId)
                statement.executeQuery().use { rs ->
                    val results = mutableListOf<ChatEnvelope>()
                    while (rs.next()) {
                        results += rowToEnvelope(rs)
                    }
                    results
                }
            }
        }
    }

    override suspend fun appendEvent(userId: String, envelope: ChatEnvelope): ChatEnvelope = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val eventId = connection.prepareStatement(
                    """
                    INSERT INTO group_message_events (
                      group_id, sender_user_id, client_message_id, trace_id, event, message_id, card_id, seq, timestamp_ms, payload_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    RETURNING event_id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, envelope.groupId)
                    statement.setString(2, userId)
                    statement.setString(3, envelope.clientMessageId)
                    statement.setString(4, envelope.traceId)
                    statement.setString(5, envelope.event)
                    statement.setString(6, envelope.messageId)
                    statement.setString(7, envelope.cardId)
                    statement.setLong(8, envelope.seq)
                    statement.setLong(9, envelope.timestamp)
                    statement.setString(10, envelope.payload?.toString())
                    statement.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong("event_id")
                    }
                }

                val persisted = envelope.copy(eventId = eventId)
                applyEnvelopeMutation(connection, userId, persisted)
                connection.commit()
                persisted
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            }
        }
    }

    override suspend fun listGroupEvents(
        userId: String,
        groupId: String,
        afterEventId: Long,
        limit: Int,
    ): ChatEventsPage = withContext(Dispatchers.IO) {
        val normalizedLimit = limit.coerceIn(1, 500)
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT event_id, event, trace_id, group_id, message_id, client_message_id, card_id, seq, timestamp_ms, payload_json
                FROM group_message_events
                WHERE group_id = ? AND event_id > ?
                ORDER BY event_id ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, groupId)
                statement.setLong(2, afterEventId)
                statement.setInt(3, normalizedLimit + 1)
                statement.executeQuery().use { rs ->
                    val rows = mutableListOf<ChatEnvelope>()
                    while (rs.next()) {
                        rows += rowToEnvelope(rs)
                    }
                    val hasMore = rows.size > normalizedLimit
                    val items = if (hasMore) rows.take(normalizedLimit) else rows
                    ChatEventsPage(
                        items = items,
                        nextAfterEventId = items.lastOrNull()?.eventId ?: afterEventId,
                        hasMore = hasMore,
                    )
                }
            }
        }
    }

    override suspend fun cleanupExpired(retentionDays: Int): Int = withContext(Dispatchers.IO) {
        if (retentionDays <= 0) {
            return@withContext 0
        }
        val cutoffMs = System.currentTimeMillis() - retentionDays.toLong() * 24L * 60L * 60L * 1000L
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val deletedEvents = connection.prepareStatement(
                    "DELETE FROM group_message_events WHERE created_at < NOW() - (? || ' day')::interval",
                ).use { statement ->
                    statement.setInt(1, retentionDays)
                    statement.executeUpdate()
                }

                val deletedCards = connection.prepareStatement(
                    "DELETE FROM group_message_cards WHERE updated_at < ?",
                ).use { statement ->
                    statement.setLong(1, cutoffMs)
                    statement.executeUpdate()
                }

                val deletedMessages = connection.prepareStatement(
                    "DELETE FROM group_messages WHERE updated_at < ?",
                ).use { statement ->
                    statement.setLong(1, cutoffMs)
                    statement.executeUpdate()
                }

                val deletedConversations = connection.prepareStatement(
                    "DELETE FROM group_conversations WHERE updated_at < ?",
                ).use { statement ->
                    statement.setLong(1, cutoffMs)
                    statement.executeUpdate()
                }

                connection.commit()
                deletedEvents + deletedCards + deletedMessages + deletedConversations
            } catch (ex: Exception) {
                connection.rollback()
                throw ex
            }
        }
    }

    private fun applyEnvelopeMutation(connection: java.sql.Connection, userId: String, envelope: ChatEnvelope) {
        upsertGroupConversation(connection, envelope.groupId, envelope.timestamp)

        when (envelope.event) {
            "message.ack" -> {
                upsertMessage(
                    connection = connection,
                    groupId = envelope.groupId,
                    messageId = envelope.messageId,
                    role = "user",
                    senderUserId = userId,
                    clientMessageId = envelope.clientMessageId,
                    status = "sent",
                    createdAt = envelope.timestamp,
                    updatedAt = envelope.timestamp,
                )
            }

            "message.start" -> {
                upsertMessage(
                    connection = connection,
                    groupId = envelope.groupId,
                    messageId = envelope.messageId,
                    role = "assistant",
                    senderUserId = userId,
                    clientMessageId = envelope.clientMessageId,
                    status = "streaming",
                    createdAt = envelope.timestamp,
                    updatedAt = envelope.timestamp,
                )
            }

            "message.delta" -> {
                val delta = decodePayload<DeltaPayload>(envelope.payload, DeltaPayload.serializer())?.delta ?: ""
                if (delta.isNotEmpty()) {
                    appendTextCard(
                        connection = connection,
                        groupId = envelope.groupId,
                        messageId = envelope.messageId,
                        cardId = envelope.cardId.ifBlank { "card_text_main" },
                        delta = delta,
                        updatedAt = envelope.timestamp,
                    )
                }
            }

            "card.replace" -> {
                val payload = decodePayload<ReplaceCardPayload>(envelope.payload, ReplaceCardPayload.serializer())
                if (payload != null) {
                    upsertCard(
                        connection = connection,
                        groupId = envelope.groupId,
                        messageId = envelope.messageId,
                        cardId = envelope.cardId.ifBlank { "card_main" },
                        cardType = payload.cardType,
                        text = payload.caption ?: "",
                        imageUrl = payload.url ?: "",
                        audioUrl = payload.url ?: "",
                        audioMime = payload.mime ?: "",
                        durationMs = payload.durationMs ?: 0,
                        extraJson = null,
                        updatedAt = envelope.timestamp,
                    )
                }
            }

            "audio.output.chunk" -> {
                val payload = decodePayload<AudioChunkPayload>(envelope.payload, AudioChunkPayload.serializer())
                if (payload != null) {
                    val dataUri = "data:${payload.mime};base64,${payload.base64}"
                    upsertCard(
                        connection = connection,
                        groupId = envelope.groupId,
                        messageId = envelope.messageId,
                        cardId = envelope.cardId.ifBlank { "card_audio_main" },
                        cardType = "audio",
                        text = "",
                        imageUrl = "",
                        audioUrl = dataUri,
                        audioMime = payload.mime,
                        durationMs = payload.durationMs,
                        extraJson = null,
                        updatedAt = envelope.timestamp,
                    )
                }
            }

            "message.complete" -> {
                updateMessageStatus(
                    connection = connection,
                    groupId = envelope.groupId,
                    messageId = envelope.messageId,
                    status = "completed",
                    updatedAt = envelope.timestamp,
                )
            }

            "message.error" -> {
                updateMessageStatus(
                    connection = connection,
                    groupId = envelope.groupId,
                    messageId = envelope.messageId,
                    status = "failed",
                    updatedAt = envelope.timestamp,
                )
            }

            else -> {
                // Event is persisted in group_message_events even when no snapshot mutation is needed.
            }
        }
    }

    private fun rowToEnvelope(rs: java.sql.ResultSet): ChatEnvelope {
        return ChatEnvelope(
            event = rs.getString("event"),
            traceId = rs.getString("trace_id"),
            groupId = rs.getString("group_id"),
            messageId = rs.getString("message_id") ?: "",
            clientMessageId = rs.getString("client_message_id") ?: "",
            cardId = rs.getString("card_id") ?: "",
            seq = rs.getLong("seq"),
            timestamp = rs.getLong("timestamp_ms"),
            eventId = rs.getLong("event_id"),
            payload = parsePayload(rs.getString("payload_json")),
        )
    }

    private fun parsePayload(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) {
            return null
        }
        return runCatching { json.parseToJsonElement(raw) }
            .onFailure { ex -> logger.warn(ex) { "Failed to parse payload_json" } }
            .getOrNull()
    }

    private fun <T> decodePayload(payload: JsonElement?, serializer: kotlinx.serialization.KSerializer<T>): T? {
        if (payload == null) {
            return null
        }
        return runCatching { json.decodeFromJsonElement(serializer, payload) }
            .onFailure { ex -> logger.warn(ex) { "Failed to decode payload for persisted event" } }
            .getOrNull()
    }

    private fun upsertGroupConversation(
        connection: java.sql.Connection,
        groupId: String,
        now: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO group_conversations (group_id, created_at, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT (group_id)
            DO UPDATE SET updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, groupId)
            statement.setLong(2, now)
            statement.setLong(3, now)
            statement.executeUpdate()
        }
    }

    private fun upsertMessage(
        connection: java.sql.Connection,
        groupId: String,
        messageId: String,
        role: String,
        senderUserId: String,
        clientMessageId: String,
        status: String,
        createdAt: Long,
        updatedAt: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO group_messages (
                group_id, message_id, role, sender_user_id, client_message_id, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (group_id, message_id)
            DO UPDATE SET
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at,
                sender_user_id = EXCLUDED.sender_user_id,
                client_message_id = EXCLUDED.client_message_id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, groupId)
            statement.setString(2, messageId)
            statement.setString(3, role)
            statement.setString(4, senderUserId)
            statement.setString(5, clientMessageId)
            statement.setString(6, status)
            statement.setLong(7, createdAt)
            statement.setLong(8, updatedAt)
            statement.executeUpdate()
        }
    }

    private fun updateMessageStatus(
        connection: java.sql.Connection,
        groupId: String,
        messageId: String,
        status: String,
        updatedAt: Long,
    ) {
        connection.prepareStatement(
            """
            UPDATE group_messages
            SET status = ?, updated_at = ?
            WHERE group_id = ? AND message_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, status)
            statement.setLong(2, updatedAt)
            statement.setString(3, groupId)
            statement.setString(4, messageId)
            val updated = statement.executeUpdate()
            if (updated == 0) {
                upsertMessage(
                    connection = connection,
                    groupId = groupId,
                    messageId = messageId,
                    role = "assistant",
                    senderUserId = "",
                    clientMessageId = "",
                    status = status,
                    createdAt = updatedAt,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    private fun appendTextCard(
        connection: java.sql.Connection,
        groupId: String,
        messageId: String,
        cardId: String,
        delta: String,
        updatedAt: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO group_message_cards (
                group_id, message_id, card_id, card_type, text_content, updated_at
            ) VALUES (?, ?, ?, 'text', ?, ?)
            ON CONFLICT (group_id, message_id, card_id)
            DO UPDATE SET
                text_content = group_message_cards.text_content || EXCLUDED.text_content,
                card_type = 'text',
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, groupId)
            statement.setString(2, messageId)
            statement.setString(3, cardId)
            statement.setString(4, delta)
            statement.setLong(5, updatedAt)
            statement.executeUpdate()
        }
    }

    private fun upsertCard(
        connection: java.sql.Connection,
        groupId: String,
        messageId: String,
        cardId: String,
        cardType: String,
        text: String,
        imageUrl: String,
        audioUrl: String,
        audioMime: String,
        durationMs: Long,
        extraJson: String?,
        updatedAt: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO group_message_cards (
                group_id, message_id, card_id, card_type,
                text_content, image_url, audio_url, audio_mime, duration_ms, extra_json, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (group_id, message_id, card_id)
            DO UPDATE SET
                card_type = EXCLUDED.card_type,
                text_content = EXCLUDED.text_content,
                image_url = EXCLUDED.image_url,
                audio_url = EXCLUDED.audio_url,
                audio_mime = EXCLUDED.audio_mime,
                duration_ms = EXCLUDED.duration_ms,
                extra_json = EXCLUDED.extra_json,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, groupId)
            statement.setString(2, messageId)
            statement.setString(3, cardId)
            statement.setString(4, cardType)
            statement.setString(5, text)
            statement.setString(6, imageUrl)
            statement.setString(7, audioUrl)
            statement.setString(8, audioMime)
            statement.setLong(9, durationMs)
            statement.setString(10, extraJson)
            statement.setLong(11, updatedAt)
            statement.executeUpdate()
        }
    }

    private fun nextUserId(): String = "u_${UUID.randomUUID().toString().replace("-", "").take(24)}"

    private fun nextGroupId(): String = "g_${UUID.randomUUID().toString().replace("-", "").take(24)}"

    private fun nextJoinCode(): String =
        "INVITE-${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
}
