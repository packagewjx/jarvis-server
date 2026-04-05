package jarvis.server.service

import jarvis.server.config.JwtConfig
import jarvis.server.persistence.InMemoryChatStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthServiceTest {
    @Test
    fun `register login refresh lifecycle works`() = kotlinx.coroutines.test.runTest {
        val store = InMemoryChatStore()
        val service = AuthService(
            chatStore = store,
            jwtConfig = JwtConfig(
                secret = "test-secret",
                issuer = "test-issuer",
                accessTtlSec = 600,
                refreshTtlSec = 3600,
            ),
        )

        val register = service.register(
            usernameRaw = "alice_01",
            passwordRaw = "Password123",
            userAgent = "test-agent",
            ip = "127.0.0.1",
        )
        assertTrue(register is RegisterResult.Success)
        val registerResult = register as RegisterResult.Success
        assertEquals("alice_01", registerResult.user.username)
        assertTrue(registerResult.tokenPair.sessionId.isNotBlank())

        val accessPrincipal = service.authenticateAccess("Bearer ${registerResult.tokenPair.accessToken}")
        assertNotNull(accessPrincipal)
        assertEquals(registerResult.user.userId, accessPrincipal.userId)
        assertEquals("alice_01", accessPrincipal.username)
        assertEquals(registerResult.tokenPair.sessionId, accessPrincipal.sessionId)

        val login = service.login(
            usernameRaw = "alice_01",
            passwordRaw = "Password123",
            userAgent = "test-agent",
            ip = "127.0.0.1",
        )
        assertTrue(login is LoginResult.Success)
        val loginResult = login as LoginResult.Success
        assertTrue(loginResult.tokenPair.sessionId.isNotBlank())

        val refreshed = service.refresh(
            registerResult.tokenPair.refreshToken,
            userAgent = "test-agent",
            ip = "127.0.0.1",
        )
        assertTrue(refreshed is RefreshResult.Success)
        val refreshedResult = refreshed as RefreshResult.Success
        assertEquals(registerResult.user.userId, refreshedResult.principal.userId)
        assertNotNull(service.authenticateAccess("Bearer ${refreshedResult.tokenPair.accessToken}"))
    }

    @Test
    fun `password change revokes other sessions`() = kotlinx.coroutines.test.runTest {
        val store = InMemoryChatStore()
        val service = AuthService(
            chatStore = store,
            jwtConfig = JwtConfig(
                secret = "test-secret",
                issuer = "test-issuer",
                accessTtlSec = 600,
                refreshTtlSec = 3600,
            ),
        )

        val register = service.register("alice_02", "Password123", "agent-a", "1.1.1.1") as RegisterResult.Success
        val login = service.login("alice_02", "Password123", "agent-b", "2.2.2.2") as LoginResult.Success
        val principal = service.authenticateAccess("Bearer ${register.tokenPair.accessToken}")
        assertNotNull(principal)

        val changed = service.passwordChange(principal, "Password123", "Password456")
        assertEquals(PasswordChangeResult.Success, changed)

        val sessions = service.listSessions(register.user.userId)
        assertTrue(sessions.size >= 2)
        val current = sessions.first { it.sessionId == register.tokenPair.sessionId }
        val other = sessions.first { it.sessionId == login.tokenPair.sessionId }
        assertTrue(current.revokedAt == null)
        assertNotNull(other.revokedAt)
    }
}
