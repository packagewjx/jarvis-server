package jarvis.server.persistence

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import jarvis.server.model.ChatEnvelope
import jarvis.server.model.MessageSendPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryChatStore : ChatStore {
    private val mutex = Mutex()
    private val nextEventId = AtomicLong(1)
    private val usersById = linkedMapOf<String, UserCredential>()
    private val usersByName = linkedMapOf<String, String>()
    private val groupsById = linkedMapOf<String, GroupSummary>()
    private val memberships = linkedMapOf<String, MutableSet<String>>()
    private val inviteCodeToGroup = linkedMapOf<String, String>()
    private val eventsByGroup = linkedMapOf<String, MutableList<ChatEnvelope>>()
    private val eventsByRun = linkedMapOf<String, MutableList<ChatEnvelope>>()

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
            val credential = UserCredential(userId = userId, username = username, passwordHash = passwordHash)
            usersById[userId] = credential
            usersByName[username] = userId
            UserProfile(userId = userId, username = username)
        }
    }

    override suspend fun findUserCredentialByUsername(username: String): UserCredential? {
        return mutex.withLock {
            val userId = usersByName[username] ?: return@withLock null
            usersById[userId]
        }
    }

    override suspend fun findUserProfile(userId: String): UserProfile? {
        return mutex.withLock {
            usersById[userId]?.let { UserProfile(userId = it.userId, username = it.username) }
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
