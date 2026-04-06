package jarvis.server.persistence

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InMemoryChatStoreGroupTest {
    @Test
    fun `create group creates membership and invite code`() = runBlocking {
        val store = InMemoryChatStore()
        val owner = requireNotNull(store.createUser("owner", "hash"))

        val created = store.createGroupForUser(owner.userId, "产品讨论群")
        assertNotNull(created)
        assertTrue(created.groupId.startsWith("g_"))
        assertTrue(created.joinCode.startsWith("INVITE-"))

        val groups = store.listJoinedGroups(owner.userId)
        assertTrue(groups.any { it.groupId == created.groupId && it.name == "产品讨论群" })
    }

    @Test
    fun `join by created invite code lets another user join`() = runBlocking {
        val store = InMemoryChatStore()
        val owner = requireNotNull(store.createUser("owner2", "hash"))
        val member = requireNotNull(store.createUser("member2", "hash"))
        val created = requireNotNull(store.createGroupForUser(owner.userId, "研发测试群"))

        val joined = store.joinGroupByInvite(member.userId, created.joinCode)
        assertNotNull(joined)
        assertEquals(created.groupId, joined.groupId)

        val memberGroups = store.listJoinedGroups(member.userId)
        assertTrue(memberGroups.any { it.groupId == created.groupId })
    }
}
