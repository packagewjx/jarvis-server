package jarvis.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import jarvis.server.config.AppConfig
import jarvis.server.gateway.HttpsSseChannelGateway
import jarvis.server.model.AuthLoginRequest
import jarvis.server.model.AuthLogoutRequest
import jarvis.server.model.AuthRefreshRequest
import jarvis.server.model.AuthRevokeRequest
import jarvis.server.model.AuthRegisterRequest
import jarvis.server.model.AuthTokenResponse
import jarvis.server.model.AuthUserPayload
import jarvis.server.model.ChatEventsSyncResponse
import jarvis.server.model.GenericSuccessResponse
import jarvis.server.model.GroupListResponse
import jarvis.server.model.GroupMembershipPayload
import jarvis.server.model.GroupPayload
import jarvis.server.model.IatSignUrlErrorResponse
import jarvis.server.model.IatSignUrlSuccessResponse
import jarvis.server.model.JoinGroupRequest
import jarvis.server.model.JoinGroupResponse
import jarvis.server.model.PasswordChangeRequest
import jarvis.server.model.PasswordForgotRequest
import jarvis.server.model.PasswordForgotResponse
import jarvis.server.model.PasswordResetRequest
import jarvis.server.model.UserMeResponse
import jarvis.server.model.UserProfilePayload
import jarvis.server.model.UserSessionListResponse
import jarvis.server.model.UserSessionPayload
import jarvis.server.model.VerifyConfirmRequest
import jarvis.server.model.VerifySendRequest
import jarvis.server.model.VerifySendResponse
import jarvis.server.persistence.DatabaseFactory
import jarvis.server.persistence.PostgresChatStore
import jarvis.server.persistence.RetentionCleanupScheduler
import jarvis.server.service.AuthService
import jarvis.server.service.ChatBridgeService
import jarvis.server.service.IatSignResult
import jarvis.server.service.IatSignUrlService
import jarvis.server.service.LoginResult
import jarvis.server.service.PasswordChangeResult
import jarvis.server.service.PasswordForgotResult
import jarvis.server.service.PasswordResetResult
import jarvis.server.service.RefreshResult
import jarvis.server.service.RegisterResult
import jarvis.server.service.VerifyConfirmResult
import jarvis.server.service.VerifySendResult
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig.fromEnvironment()
    val sharedJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    val dataSource = DatabaseFactory.createDataSource(config.database)
    DatabaseFactory.migrate(dataSource)
    val chatStore = PostgresChatStore(dataSource, sharedJson)
    val retentionCleanupScheduler = RetentionCleanupScheduler(chatStore, config.chatPersistence.retentionDays)
    retentionCleanupScheduler.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            retentionCleanupScheduler.stop()
            dataSource.close()
        },
    )

    val authService = AuthService(chatStore, config.jwt)
    val gateway = HttpsSseChannelGateway(config.channel)
    val chatBridgeService = ChatBridgeService(gateway, chatStore, sharedJson)
    val iatSignUrlService = IatSignUrlService(config.iat)

    embeddedServer(Netty, host = config.host, port = config.port) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(sharedJson)
        }

        routing {
            get("/health") {
                call.respond(mapOf("status" to "ok", "service" to "jarvis-server"))
            }

            post("/api/auth/register") {
                val request = call.safeReceive<AuthRegisterRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                val userAgent = call.request.header("User-Agent")
                val ip = resolveClientIp(call)
                when (val result = authService.register(request.username, request.password, userAgent, ip)) {
                    is RegisterResult.Success -> {
                        call.respond(
                            HttpStatusCode.Created,
                            tokenResponse(
                                tokenPair = result.tokenPair,
                                user = result.user.let { AuthUserPayload(userId = it.userId, username = it.username) },
                            ),
                        )
                    }
                    RegisterResult.InvalidUsername -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_USERNAME"))
                    }
                    RegisterResult.InvalidPassword -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_PASSWORD"))
                    }
                    RegisterResult.UsernameTaken -> {
                        call.respond(HttpStatusCode.Conflict, mapOf("message" to "USERNAME_ALREADY_EXISTS"))
                    }
                }
            }

            post("/api/auth/login") {
                val request = call.safeReceive<AuthLoginRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                val userAgent = call.request.header("User-Agent")
                val ip = resolveClientIp(call)
                when (val result = authService.login(request.username, request.password, userAgent, ip)) {
                    is LoginResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            tokenResponse(
                                tokenPair = result.tokenPair,
                                user = result.user.let { AuthUserPayload(userId = it.userId, username = it.username) },
                            ),
                        )
                    }
                    LoginResult.InvalidCredentials -> {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "INVALID_CREDENTIALS"))
                    }
                }
            }

            post("/api/auth/refresh") {
                val request = call.safeReceive<AuthRefreshRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                val userAgent = call.request.header("User-Agent")
                val ip = resolveClientIp(call)
                when (val result = authService.refresh(request.refreshToken, userAgent, ip)) {
                    is RefreshResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            tokenResponse(
                                tokenPair = result.tokenPair,
                                user = AuthUserPayload(
                                    userId = result.principal.userId,
                                    username = result.principal.username,
                                ),
                            ),
                        )
                    }
                    RefreshResult.InvalidToken -> {
                        call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "INVALID_REFRESH_TOKEN"))
                    }
                }
            }

            post("/api/auth/logout") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@post
                }
                val request = call.safeReceive<AuthLogoutRequest>() ?: AuthLogoutRequest()
                val ok = authService.logout(
                    principal = principal,
                    refreshToken = request.refreshToken,
                    allDevices = request.allDevices,
                )
                if (!ok) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_LOGOUT_REQUEST"))
                    return@post
                }
                call.respond(HttpStatusCode.OK, GenericSuccessResponse())
            }

            post("/api/auth/revoke") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@post
                }
                val request = call.safeReceive<AuthRevokeRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                val ok = authService.revoke(
                    principal = principal,
                    sessionId = request.sessionId,
                    allDevices = request.allDevices,
                )
                if (!ok) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REVOKE_REQUEST"))
                    return@post
                }
                call.respond(HttpStatusCode.OK, GenericSuccessResponse())
            }

            get("/api/users/me") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@get
                }
                val user = authService.getUserProfile(principal.userId) ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "USER_NOT_FOUND"))
                    return@get
                }
                call.respond(
                    HttpStatusCode.OK,
                    UserMeResponse(
                        user = UserProfilePayload(
                            userId = user.userId,
                            username = user.username,
                            email = user.email,
                            phone = user.phone,
                            emailVerified = user.emailVerified,
                            phoneVerified = user.phoneVerified,
                        ),
                    ),
                )
            }

            get("/api/sessions") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@get
                }
                val sessions = authService.listSessions(principal.userId)
                call.respond(
                    HttpStatusCode.OK,
                    UserSessionListResponse(
                        items = sessions.map {
                            UserSessionPayload(
                                sessionId = it.sessionId,
                                createdAt = it.createdAt,
                                updatedAt = it.updatedAt,
                                expiresAt = it.expiresAt,
                                revokedAt = it.revokedAt,
                                userAgent = it.userAgent,
                                ip = it.ip,
                            )
                        },
                    ),
                )
            }

            delete("/api/sessions/{sessionId}") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@delete
                }
                val sessionId = call.parameters["sessionId"]?.trim().orEmpty()
                if (sessionId.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "sessionId is required"))
                    return@delete
                }
                val ok = authService.revokeSession(principal.userId, sessionId)
                if (!ok) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "SESSION_NOT_FOUND"))
                    return@delete
                }
                call.respond(HttpStatusCode.OK, GenericSuccessResponse())
            }

            post("/api/users/password/change") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@post
                }
                val request = call.safeReceive<PasswordChangeRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                when (
                    authService.passwordChange(
                        principal = principal,
                        oldPassword = request.oldPassword,
                        newPassword = request.newPassword,
                    )
                ) {
                    PasswordChangeResult.Success -> call.respond(HttpStatusCode.OK, GenericSuccessResponse())
                    PasswordChangeResult.InvalidOldPassword -> call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("message" to "INVALID_OLD_PASSWORD"),
                    )
                    PasswordChangeResult.InvalidNewPassword -> call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "INVALID_NEW_PASSWORD"),
                    )
                    PasswordChangeResult.UserNotFound -> call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("message" to "USER_NOT_FOUND"),
                    )
                }
            }

            post("/api/auth/password/forgot") {
                val request = call.safeReceive<PasswordForgotRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                when (val result = authService.passwordForgot(request.username)) {
                    is PasswordForgotResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            PasswordForgotResponse(
                                resetToken = result.resetToken,
                                expiresIn = result.expiresInSec,
                            ),
                        )
                    }
                    PasswordForgotResult.UserNotFound -> {
                        call.respond(HttpStatusCode.NotFound, mapOf("message" to "USER_NOT_FOUND"))
                    }
                }
            }

            post("/api/auth/password/reset") {
                val request = call.safeReceive<PasswordResetRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                when (authService.passwordReset(request.resetToken, request.newPassword)) {
                    PasswordResetResult.Success -> call.respond(HttpStatusCode.OK, GenericSuccessResponse())
                    PasswordResetResult.InvalidPassword -> call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "INVALID_NEW_PASSWORD"),
                    )
                    PasswordResetResult.InvalidToken -> call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("message" to "INVALID_RESET_TOKEN"),
                    )
                }
            }

            post("/api/auth/verify/send") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@post
                }
                val request = call.safeReceive<VerifySendRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                when (val result = authService.verifySend(principal, request.channel, request.target)) {
                    is VerifySendResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            VerifySendResponse(
                                challengeId = result.challengeId,
                                expiresIn = result.expiresInSec,
                                devCode = result.devCode,
                            ),
                        )
                    }
                    VerifySendResult.InvalidChannel -> call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "INVALID_VERIFY_CHANNEL"),
                    )
                    VerifySendResult.InvalidTarget -> call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("message" to "INVALID_VERIFY_TARGET"),
                    )
                }
            }

            post("/api/auth/verify/confirm") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@post
                }
                val request = call.safeReceive<VerifyConfirmRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                when (authService.verifyConfirm(principal, request.challengeId, request.code)) {
                    VerifyConfirmResult.Success -> call.respond(HttpStatusCode.OK, GenericSuccessResponse())
                    VerifyConfirmResult.Forbidden -> call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("message" to "FORBIDDEN_VERIFY_CHALLENGE"),
                    )
                    VerifyConfirmResult.InvalidChallenge -> call.respond(
                        HttpStatusCode.Unauthorized,
                        mapOf("message" to "INVALID_VERIFY_CHALLENGE"),
                    )
                }
            }

            get("/api/groups/mine") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@get
                }
                val groups = chatStore.listJoinedGroups(principal.userId)
                call.respond(
                    HttpStatusCode.OK,
                    GroupListResponse(
                        items = groups.map { GroupPayload(groupId = it.groupId, name = it.name) },
                    ),
                )
            }

            post("/api/groups/join") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@post
                }
                val request = call.safeReceive<JoinGroupRequest>() ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "INVALID_REQUEST_BODY"))
                    return@post
                }
                val membership = chatStore.joinGroupByInvite(principal.userId, request.joinCode.trim())
                if (membership == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "INVALID_JOIN_CODE"))
                    return@post
                }
                call.respond(
                    HttpStatusCode.OK,
                    JoinGroupResponse(
                        group = GroupPayload(groupId = membership.groupId, name = membership.groupName),
                        membership = GroupMembershipPayload(joinedAt = membership.joinedAt),
                    ),
                )
            }

            get("/api/voice/iat-sign-url") {
                val traceId = "req_${System.currentTimeMillis()}"
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        IatSignUrlErrorResponse(
                            code = 40101,
                            message = "UNAUTHORIZED",
                            detail = "missing or invalid bearer token",
                            traceId = traceId,
                        ),
                    )
                    return@get
                }

                val query = call.request.queryParameters
                when (
                    val result = iatSignUrlService.createSignedUrl(
                        userKey = principal.userId,
                        sampleRateRaw = query["sampleRate"],
                        domainRaw = query["domain"],
                        languageRaw = query["language"],
                        accentRaw = query["accent"],
                    )
                ) {
                    is IatSignResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            IatSignUrlSuccessResponse(
                                data = result.data,
                                traceId = traceId,
                            ),
                        )
                    }
                    is IatSignResult.Error -> {
                        val status = when (result.code) {
                            40001 -> HttpStatusCode.BadRequest
                            40101 -> HttpStatusCode.Unauthorized
                            42901 -> HttpStatusCode.TooManyRequests
                            else -> HttpStatusCode.InternalServerError
                        }
                        call.respond(
                            status,
                            IatSignUrlErrorResponse(
                                code = result.code,
                                message = result.message,
                                detail = result.detail,
                                traceId = traceId,
                            ),
                        )
                    }
                }
            }

            get("/api/chat/groups/{groupId}/events/sync") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    call.respond(HttpStatusCode.Unauthorized, mapOf("message" to "UNAUTHORIZED"))
                    return@get
                }

                val groupId = call.parameters["groupId"]?.trim().orEmpty()
                if (groupId.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "groupId is required"))
                    return@get
                }
                if (!chatBridgeService.isUserInGroup(principal.userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("message" to "FORBIDDEN_GROUP"))
                    return@get
                }

                val afterEventId = call.request.queryParameters["after_event_id"]?.toLongOrNull() ?: 0L
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                val page = chatBridgeService.syncGroupEvents(
                    userId = principal.userId,
                    groupId = groupId,
                    afterEventId = afterEventId,
                    limit = limit,
                )

                call.respond(
                    HttpStatusCode.OK,
                    ChatEventsSyncResponse(
                        items = page.items,
                        nextAfterEventId = page.nextAfterEventId,
                        hasMore = page.hasMore,
                    ),
                )
            }

            webSocket("/ws/chat") {
                val principal = authService.authenticateAccess(call.request.header("Authorization"))
                if (principal == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid bearer token"))
                    return@webSocket
                }
                chatBridgeService.handleSession(this, principal.userId)
            }
        }
    }.start(wait = true)
}

private fun tokenResponse(
    tokenPair: jarvis.server.service.AuthTokenPair,
    user: AuthUserPayload?,
): AuthTokenResponse {
    return AuthTokenResponse(
        accessToken = tokenPair.accessToken,
        refreshToken = tokenPair.refreshToken,
        expiresIn = tokenPair.expiresInSec,
        expiresAt = tokenPair.expiresAtMs,
        sessionId = tokenPair.sessionId,
        user = user,
    )
}

private fun resolveClientIp(call: io.ktor.server.application.ApplicationCall): String? {
    val forwardedFor = call.request.header("X-Forwarded-For")
        ?.split(',')
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    if (!forwardedFor.isNullOrBlank()) {
        return forwardedFor
    }
    return call.request.header("X-Real-IP")?.trim()?.takeIf { it.isNotEmpty() }
}

private suspend inline fun <reified T : Any> io.ktor.server.application.ApplicationCall.safeReceive(): T? {
    return try {
        receive<T>()
    } catch (_: Exception) {
        null
    }
}
