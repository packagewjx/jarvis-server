package jarvis.server.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import jarvis.server.config.JwtConfig
import jarvis.server.persistence.ChatStore
import jarvis.server.persistence.UserProfile
import java.time.Instant
import java.util.Date
import org.mindrot.jbcrypt.BCrypt

data class AuthPrincipal(
    val userId: String,
    val username: String,
)

data class AuthTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
)

sealed interface RegisterResult {
    data class Success(
        val user: UserProfile,
        val tokenPair: AuthTokenPair,
    ) : RegisterResult

    data object InvalidUsername : RegisterResult

    data object InvalidPassword : RegisterResult

    data object UsernameTaken : RegisterResult
}

sealed interface LoginResult {
    data class Success(
        val user: UserProfile,
        val tokenPair: AuthTokenPair,
    ) : LoginResult

    data object InvalidCredentials : LoginResult
}

sealed interface RefreshResult {
    data class Success(
        val principal: AuthPrincipal,
        val tokenPair: AuthTokenPair,
    ) : RefreshResult

    data object InvalidToken : RefreshResult
}

class AuthService(
    private val chatStore: ChatStore,
    private val jwtConfig: JwtConfig,
) {
    private val algorithm = Algorithm.HMAC256(jwtConfig.secret)
    private val accessVerifier = JWT.require(algorithm)
        .withIssuer(jwtConfig.issuer)
        .withClaim("token_type", "access")
        .build()
    private val refreshVerifier = JWT.require(algorithm)
        .withIssuer(jwtConfig.issuer)
        .withClaim("token_type", "refresh")
        .build()

    suspend fun register(usernameRaw: String, passwordRaw: String): RegisterResult {
        val username = normalizeUsername(usernameRaw)
        if (!isValidUsername(username)) {
            return RegisterResult.InvalidUsername
        }
        if (!isValidPassword(passwordRaw)) {
            return RegisterResult.InvalidPassword
        }

        val passwordHash = BCrypt.hashpw(passwordRaw, BCrypt.gensalt())
        val created = chatStore.createUser(username = username, passwordHash = passwordHash)
            ?: return RegisterResult.UsernameTaken
        return RegisterResult.Success(
            user = created,
            tokenPair = issueTokenPair(created.userId, created.username),
        )
    }

    suspend fun login(usernameRaw: String, passwordRaw: String): LoginResult {
        val username = normalizeUsername(usernameRaw)
        val credential = chatStore.findUserCredentialByUsername(username)
            ?: return LoginResult.InvalidCredentials
        val matched = runCatching { BCrypt.checkpw(passwordRaw, credential.passwordHash) }.getOrDefault(false)
        if (!matched) {
            return LoginResult.InvalidCredentials
        }
        val profile = UserProfile(userId = credential.userId, username = credential.username)
        return LoginResult.Success(
            user = profile,
            tokenPair = issueTokenPair(profile.userId, profile.username),
        )
    }

    suspend fun refresh(refreshToken: String): RefreshResult {
        val decoded = verifyToken(refreshToken, refreshVerifier) ?: return RefreshResult.InvalidToken
        val principal = principalFromJwt(decoded) ?: return RefreshResult.InvalidToken
        val profile = chatStore.findUserProfile(principal.userId) ?: return RefreshResult.InvalidToken
        val effectivePrincipal = AuthPrincipal(userId = profile.userId, username = profile.username)
        return RefreshResult.Success(
            principal = effectivePrincipal,
            tokenPair = issueTokenPair(effectivePrincipal.userId, effectivePrincipal.username),
        )
    }

    fun authenticateAccess(header: String?): AuthPrincipal? {
        val token = extractBearerToken(header) ?: return null
        val decoded = verifyToken(token, accessVerifier) ?: return null
        return principalFromJwt(decoded)
    }

    private fun issueTokenPair(userId: String, username: String): AuthTokenPair {
        val now = Instant.now()
        val accessExpireAt = now.plusSeconds(jwtConfig.accessTtlSec)
        val refreshExpireAt = now.plusSeconds(jwtConfig.refreshTtlSec)
        val access = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withSubject(userId)
            .withClaim("username", username)
            .withClaim("token_type", "access")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(accessExpireAt))
            .sign(algorithm)
        val refresh = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withSubject(userId)
            .withClaim("username", username)
            .withClaim("token_type", "refresh")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(refreshExpireAt))
            .sign(algorithm)
        return AuthTokenPair(
            accessToken = access,
            refreshToken = refresh,
            expiresInSec = jwtConfig.accessTtlSec,
        )
    }

    private fun principalFromJwt(jwt: DecodedJWT): AuthPrincipal? {
        val userId = jwt.subject?.trim().orEmpty()
        val username = jwt.getClaim("username").asString()?.trim().orEmpty()
        if (userId.isEmpty() || username.isEmpty()) {
            return null
        }
        return AuthPrincipal(userId = userId, username = username)
    }

    private fun extractBearerToken(header: String?): String? {
        val normalized = header?.trim().orEmpty()
        if (!normalized.startsWith("Bearer ")) {
            return null
        }
        return normalized.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }

    private fun verifyToken(token: String, verifier: com.auth0.jwt.JWTVerifier): DecodedJWT? {
        return try {
            verifier.verify(token)
        } catch (_: JWTVerificationException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeUsername(username: String): String = username.trim()

    private fun isValidUsername(username: String): Boolean {
        if (username.length !in 3..64) {
            return false
        }
        return username.matches(Regex("^[a-zA-Z0-9._-]+$"))
    }

    private fun isValidPassword(password: String): Boolean = password.length in 8..128
}
