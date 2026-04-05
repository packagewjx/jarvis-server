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
                INSERT INTO app_users (user_id, username, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
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

    override suspend fun findUserProfile(userId: String): UserProfile? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT user_id, username
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
                        )
                    }
                }
            }
        }
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
}
