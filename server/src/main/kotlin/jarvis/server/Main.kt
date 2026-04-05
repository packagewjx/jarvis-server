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
import jarvis.server.model.AuthRefreshRequest
import jarvis.server.model.AuthRegisterRequest
import jarvis.server.model.AuthTokenResponse
import jarvis.server.model.AuthUserPayload
import jarvis.server.model.ChatEventsSyncResponse
import jarvis.server.model.GroupListResponse
import jarvis.server.model.GroupMembershipPayload
import jarvis.server.model.GroupPayload
import jarvis.server.model.IatSignUrlErrorResponse
import jarvis.server.model.IatSignUrlSuccessResponse
import jarvis.server.model.JoinGroupRequest
import jarvis.server.model.JoinGroupResponse
import jarvis.server.persistence.DatabaseFactory
import jarvis.server.persistence.PostgresChatStore
import jarvis.server.persistence.RetentionCleanupScheduler
import jarvis.server.service.AuthService
import jarvis.server.service.ChatBridgeService
import jarvis.server.service.IatSignResult
import jarvis.server.service.IatSignUrlService
import jarvis.server.service.LoginResult
import jarvis.server.service.RefreshResult
import jarvis.server.service.RegisterResult
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
                when (val result = authService.register(request.username, request.password)) {
                    is RegisterResult.Success -> {
                        call.respond(
                            HttpStatusCode.Created,
                            AuthTokenResponse(
                                accessToken = result.tokenPair.accessToken,
                                refreshToken = result.tokenPair.refreshToken,
                                expiresIn = result.tokenPair.expiresInSec,
                                user = AuthUserPayload(
                                    userId = result.user.userId,
                                    username = result.user.username,
                                ),
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
                when (val result = authService.login(request.username, request.password)) {
                    is LoginResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            AuthTokenResponse(
                                accessToken = result.tokenPair.accessToken,
                                refreshToken = result.tokenPair.refreshToken,
                                expiresIn = result.tokenPair.expiresInSec,
                                user = AuthUserPayload(
                                    userId = result.user.userId,
                                    username = result.user.username,
                                ),
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
                when (val result = authService.refresh(request.refreshToken)) {
                    is RefreshResult.Success -> {
                        call.respond(
                            HttpStatusCode.OK,
                            AuthTokenResponse(
                                accessToken = result.tokenPair.accessToken,
                                refreshToken = result.tokenPair.refreshToken,
                                expiresIn = result.tokenPair.expiresInSec,
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

private suspend inline fun <reified T : Any> io.ktor.server.application.ApplicationCall.safeReceive(): T? {
    return try {
        receive<T>()
    } catch (_: Exception) {
        null
    }
}
