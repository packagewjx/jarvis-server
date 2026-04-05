package jarvis.server.service

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import jarvis.server.config.JwtConfig
import jarvis.server.persistence.ChatStore
import jarvis.server.persistence.UserProfile
import jarvis.server.persistence.UserSession
import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import org.mindrot.jbcrypt.BCrypt

data class AuthPrincipal(
    val userId: String,
    val username: String,
    val sessionId: String,
    val tokenJti: String,
    val tokenExpiresAtMs: Long,
)

data class AuthTokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSec: Long,
    val expiresAtMs: Long,
    val sessionId: String,
    val accessJti: String,
    val accessExpiresAtMs: Long,
    val refreshJti: String,
    val refreshExpiresAtMs: Long,
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

sealed interface PasswordChangeResult {
    data object Success : PasswordChangeResult
    data object InvalidOldPassword : PasswordChangeResult
    data object InvalidNewPassword : PasswordChangeResult
    data object UserNotFound : PasswordChangeResult
}

sealed interface PasswordForgotResult {
    data class Success(
        val resetToken: String,
        val expiresInSec: Long,
    ) : PasswordForgotResult

    data object UserNotFound : PasswordForgotResult
}

sealed interface PasswordResetResult {
    data object Success : PasswordResetResult
    data object InvalidToken : PasswordResetResult
    data object InvalidPassword : PasswordResetResult
}

sealed interface VerifySendResult {
    data class Success(
        val challengeId: String,
        val expiresInSec: Long,
        val devCode: String,
    ) : VerifySendResult

    data object InvalidChannel : VerifySendResult
    data object InvalidTarget : VerifySendResult
}

sealed interface VerifyConfirmResult {
    data object Success : VerifyConfirmResult
    data object InvalidChallenge : VerifyConfirmResult
    data object Forbidden : VerifyConfirmResult
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

    suspend fun register(
        usernameRaw: String,
        passwordRaw: String,
        userAgent: String?,
        ip: String?,
    ): RegisterResult {
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
        val tokenPair = issueSessionTokenPair(created.userId, created.username, userAgent, ip)
        return RegisterResult.Success(
            user = created,
            tokenPair = tokenPair,
        )
    }

    suspend fun login(
        usernameRaw: String,
        passwordRaw: String,
        userAgent: String?,
        ip: String?,
    ): LoginResult {
        val username = normalizeUsername(usernameRaw)
        val credential = chatStore.findUserCredentialByUsername(username)
            ?: return LoginResult.InvalidCredentials
        val matched = runCatching { BCrypt.checkpw(passwordRaw, credential.passwordHash) }.getOrDefault(false)
        if (!matched) {
            return LoginResult.InvalidCredentials
        }
        val profile = chatStore.findUserProfile(credential.userId)
            ?: return LoginResult.InvalidCredentials
        val tokenPair = issueSessionTokenPair(profile.userId, profile.username, userAgent, ip)
        return LoginResult.Success(
            user = profile,
            tokenPair = tokenPair,
        )
    }

    suspend fun refresh(
        refreshToken: String,
        userAgent: String?,
        ip: String?,
    ): RefreshResult {
        val decoded = verifyToken(refreshToken, refreshVerifier) ?: return RefreshResult.InvalidToken
        val refreshPrincipal = principalFromJwt(decoded) ?: return RefreshResult.InvalidToken
        if (chatStore.isTokenRevoked(refreshPrincipal.tokenJti)) {
            return RefreshResult.InvalidToken
        }
        val session = chatStore.findSession(refreshPrincipal.sessionId) ?: return RefreshResult.InvalidToken
        if (session.userId != refreshPrincipal.userId || session.revokedAt != null || session.expiresAt <= nowMs()) {
            return RefreshResult.InvalidToken
        }
        val storedHash = chatStore.getSessionRefreshTokenHash(session.sessionId) ?: return RefreshResult.InvalidToken
        if (storedHash != sha256Hex(refreshToken)) {
            return RefreshResult.InvalidToken
        }

        val profile = chatStore.findUserProfile(refreshPrincipal.userId) ?: return RefreshResult.InvalidToken
        val tokenPair = issueTokenPair(profile.userId, profile.username, session.sessionId)
        val rotated = chatStore.rotateSessionRefresh(
            sessionId = session.sessionId,
            refreshTokenHash = sha256Hex(tokenPair.refreshToken),
            refreshExpiresAt = tokenPair.refreshExpiresAtMs,
            updatedAt = nowMs(),
        )
        if (!rotated) {
            return RefreshResult.InvalidToken
        }
        chatStore.saveRevokedToken(refreshPrincipal.tokenJti, refreshPrincipal.tokenExpiresAtMs)
        return RefreshResult.Success(
            principal = refreshPrincipal,
            tokenPair = tokenPair,
        )
    }

    suspend fun authenticateAccess(header: String?): AuthPrincipal? {
        val token = extractBearerToken(header) ?: return null
        val decoded = verifyToken(token, accessVerifier) ?: return null
        val principal = principalFromJwt(decoded) ?: return null
        if (chatStore.isTokenRevoked(principal.tokenJti)) {
            return null
        }
        val session = chatStore.findSession(principal.sessionId) ?: return null
        if (session.userId != principal.userId || session.revokedAt != null || session.expiresAt <= nowMs()) {
            return null
        }
        return principal
    }

    suspend fun logout(principal: AuthPrincipal, refreshToken: String?, allDevices: Boolean): Boolean {
        if (allDevices) {
            chatStore.revokeAllSessions(userId = principal.userId, revokedAt = nowMs(), exceptSessionId = null)
            chatStore.saveRevokedToken(principal.tokenJti, principal.tokenExpiresAtMs)
            return true
        }

        if (!refreshToken.isNullOrBlank()) {
            val decoded = verifyToken(refreshToken, refreshVerifier) ?: return false
            val refreshPrincipal = principalFromJwt(decoded) ?: return false
            if (refreshPrincipal.userId != principal.userId) {
                return false
            }
            chatStore.revokeSession(refreshPrincipal.sessionId, nowMs())
            chatStore.saveRevokedToken(refreshPrincipal.tokenJti, refreshPrincipal.tokenExpiresAtMs)
            chatStore.saveRevokedToken(principal.tokenJti, principal.tokenExpiresAtMs)
            return true
        }

        chatStore.revokeSession(principal.sessionId, nowMs())
        chatStore.saveRevokedToken(principal.tokenJti, principal.tokenExpiresAtMs)
        return true
    }

    suspend fun revoke(principal: AuthPrincipal, sessionId: String?, allDevices: Boolean): Boolean {
        return if (allDevices) {
            chatStore.revokeAllSessions(principal.userId, nowMs(), exceptSessionId = null)
            true
        } else if (!sessionId.isNullOrBlank()) {
            val session = chatStore.findSession(sessionId) ?: return false
            if (session.userId != principal.userId) {
                return false
            }
            chatStore.revokeSession(sessionId, nowMs())
            true
        } else {
            false
        }
    }

    suspend fun passwordChange(
        principal: AuthPrincipal,
        oldPassword: String,
        newPassword: String,
    ): PasswordChangeResult {
        if (!isValidPassword(newPassword)) {
            return PasswordChangeResult.InvalidNewPassword
        }
        val credential = chatStore.findUserCredentialByUserId(principal.userId)
            ?: return PasswordChangeResult.UserNotFound
        val matched = runCatching { BCrypt.checkpw(oldPassword, credential.passwordHash) }.getOrDefault(false)
        if (!matched) {
            return PasswordChangeResult.InvalidOldPassword
        }
        val updated = chatStore.updateUserPassword(
            userId = principal.userId,
            passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt()),
            updatedAt = nowMs(),
        )
        if (!updated) {
            return PasswordChangeResult.UserNotFound
        }
        chatStore.revokeAllSessions(userId = principal.userId, revokedAt = nowMs(), exceptSessionId = principal.sessionId)
        return PasswordChangeResult.Success
    }

    suspend fun passwordForgot(usernameRaw: String): PasswordForgotResult {
        val username = normalizeUsername(usernameRaw)
        val credential = chatStore.findUserCredentialByUsername(username) ?: return PasswordForgotResult.UserNotFound
        val resetTokenPlain = generatePasswordResetToken()
        val expiresInSec = 15 * 60L
        val now = nowMs()
        chatStore.createPasswordResetToken(
            userId = credential.userId,
            tokenHash = sha256Hex(resetTokenPlain),
            expiresAt = now + expiresInSec * 1000L,
            createdAt = now,
        )
        return PasswordForgotResult.Success(
            resetToken = resetTokenPlain,
            expiresInSec = expiresInSec,
        )
    }

    suspend fun passwordReset(resetToken: String, newPassword: String): PasswordResetResult {
        if (!isValidPassword(newPassword)) {
            return PasswordResetResult.InvalidPassword
        }
        val userId = chatStore.consumePasswordResetToken(sha256Hex(resetToken), nowMs())
            ?: return PasswordResetResult.InvalidToken
        val updated = chatStore.updateUserPassword(
            userId = userId,
            passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt()),
            updatedAt = nowMs(),
        )
        if (!updated) {
            return PasswordResetResult.InvalidToken
        }
        chatStore.revokeAllSessions(userId = userId, revokedAt = nowMs(), exceptSessionId = null)
        return PasswordResetResult.Success
    }

    suspend fun verifySend(principal: AuthPrincipal, channelRaw: String, targetRaw: String): VerifySendResult {
        val channel = channelRaw.trim().lowercase()
        val target = targetRaw.trim()
        if (channel !in setOf("email", "phone")) {
            return VerifySendResult.InvalidChannel
        }
        if (target.isBlank()) {
            return VerifySendResult.InvalidTarget
        }
        if (channel == "email" && !target.contains("@")) {
            return VerifySendResult.InvalidTarget
        }
        if (channel == "phone" && target.length !in 6..32) {
            return VerifySendResult.InvalidTarget
        }
        val code = Random.nextInt(100000, 999999).toString()
        val expiresInSec = 10 * 60L
        val now = nowMs()
        val challenge = chatStore.createVerificationChallenge(
            userId = principal.userId,
            channel = channel,
            target = target,
            codeHash = sha256Hex(code),
            expiresAt = now + expiresInSec * 1000L,
            createdAt = now,
        )
        return VerifySendResult.Success(
            challengeId = challenge.challengeId,
            expiresInSec = expiresInSec,
            devCode = code,
        )
    }

    suspend fun verifyConfirm(principal: AuthPrincipal, challengeId: String, code: String): VerifyConfirmResult {
        val challenge = chatStore.consumeVerificationChallenge(
            challengeId = challengeId.trim(),
            codeHash = sha256Hex(code.trim()),
            now = nowMs(),
        ) ?: return VerifyConfirmResult.InvalidChallenge
        if (challenge.userId != principal.userId) {
            return VerifyConfirmResult.Forbidden
        }
        val marked = chatStore.markUserVerified(
            userId = principal.userId,
            channel = challenge.channel,
            target = challenge.target,
            updatedAt = nowMs(),
        )
        return if (marked) VerifyConfirmResult.Success else VerifyConfirmResult.InvalidChallenge
    }

    suspend fun getUserProfile(userId: String): UserProfile? = chatStore.findUserProfile(userId)

    suspend fun listSessions(userId: String): List<UserSession> = chatStore.listSessions(userId)

    suspend fun revokeSession(userId: String, sessionId: String): Boolean {
        val session = chatStore.findSession(sessionId) ?: return false
        if (session.userId != userId) {
            return false
        }
        return chatStore.revokeSession(sessionId, nowMs())
    }

    private suspend fun issueSessionTokenPair(
        userId: String,
        username: String,
        userAgent: String?,
        ip: String?,
    ): AuthTokenPair {
        val provisional = chatStore.createSession(
            userId = userId,
            refreshTokenHash = "pending",
            refreshExpiresAt = nowMs() + jwtConfig.refreshTtlSec * 1000L,
            userAgent = userAgent,
            ip = ip,
        )
        val tokenPair = issueTokenPair(
            userId = userId,
            username = username,
            sessionId = provisional.sessionId,
        )
        chatStore.rotateSessionRefresh(
            sessionId = provisional.sessionId,
            refreshTokenHash = sha256Hex(tokenPair.refreshToken),
            refreshExpiresAt = tokenPair.refreshExpiresAtMs,
            updatedAt = nowMs(),
        )
        return tokenPair
    }

    private fun issueTokenPair(userId: String, username: String, sessionId: String): AuthTokenPair {
        val now = Instant.now()
        val accessExpireAt = now.plusSeconds(jwtConfig.accessTtlSec)
        val refreshExpireAt = now.plusSeconds(jwtConfig.refreshTtlSec)
        val accessJti = UUID.randomUUID().toString().replace("-", "")
        val refreshJti = UUID.randomUUID().toString().replace("-", "")
        val access = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withSubject(userId)
            .withJWTId(accessJti)
            .withClaim("username", username)
            .withClaim("session_id", sessionId)
            .withClaim("token_type", "access")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(accessExpireAt))
            .sign(algorithm)
        val refresh = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withSubject(userId)
            .withJWTId(refreshJti)
            .withClaim("username", username)
            .withClaim("session_id", sessionId)
            .withClaim("token_type", "refresh")
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(refreshExpireAt))
            .sign(algorithm)
        return AuthTokenPair(
            accessToken = access,
            refreshToken = refresh,
            expiresInSec = jwtConfig.accessTtlSec,
            expiresAtMs = accessExpireAt.toEpochMilli(),
            sessionId = sessionId,
            accessJti = accessJti,
            accessExpiresAtMs = accessExpireAt.toEpochMilli(),
            refreshJti = refreshJti,
            refreshExpiresAtMs = refreshExpireAt.toEpochMilli(),
        )
    }

    private fun principalFromJwt(jwt: DecodedJWT): AuthPrincipal? {
        val userId = jwt.subject?.trim().orEmpty()
        val username = jwt.getClaim("username").asString()?.trim().orEmpty()
        val sessionId = jwt.getClaim("session_id").asString()?.trim().orEmpty()
        val jti = jwt.id?.trim().orEmpty()
        val expiresAt = jwt.expiresAt?.time ?: 0L
        if (userId.isEmpty() || username.isEmpty() || sessionId.isEmpty() || jti.isEmpty() || expiresAt <= 0L) {
            return null
        }
        return AuthPrincipal(
            userId = userId,
            username = username,
            sessionId = sessionId,
            tokenJti = jti,
            tokenExpiresAtMs = expiresAt,
        )
    }

    private fun extractBearerToken(header: String?): String? {
        val normalized = header?.trim().orEmpty()
        if (!normalized.startsWith("Bearer ")) {
            return null
        }
        return normalized.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }

    private fun verifyToken(token: String, verifier: JWTVerifier): DecodedJWT? {
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

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    private fun generatePasswordResetToken(): String {
        val part1 = UUID.randomUUID().toString().replace("-", "")
        val part2 = UUID.randomUUID().toString().replace("-", "")
        return "prt_${part1}${part2}"
    }
}
