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

        val register = service.register(usernameRaw = "alice_01", passwordRaw = "Password123")
        assertTrue(register is RegisterResult.Success)
        val registerResult = register
        assertEquals("alice_01", registerResult.user.username)

        val accessPrincipal = service.authenticateAccess("Bearer ${registerResult.tokenPair.accessToken}")
        assertNotNull(accessPrincipal)
        assertEquals(registerResult.user.userId, accessPrincipal.userId)
        assertEquals("alice_01", accessPrincipal.username)

        val login = service.login(usernameRaw = "alice_01", passwordRaw = "Password123")
        assertTrue(login is LoginResult.Success)

        val refreshed = service.refresh(registerResult.tokenPair.refreshToken)
        assertTrue(refreshed is RefreshResult.Success)
        val refreshedResult = refreshed
        assertEquals(registerResult.user.userId, refreshedResult.principal.userId)
        assertNotNull(service.authenticateAccess("Bearer ${refreshedResult.tokenPair.accessToken}"))
    }
}
