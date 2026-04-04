package jarvis.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import jarvis.server.config.AppConfig
import jarvis.server.gateway.HttpsSseChannelGateway
import jarvis.server.model.IatSignUrlErrorResponse
import jarvis.server.model.IatSignUrlSuccessResponse
import jarvis.server.service.ChatBridgeService
import jarvis.server.service.IatSignResult
import jarvis.server.service.IatSignUrlService
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig.fromEnvironment()
    val gateway = HttpsSseChannelGateway(config.channel)
    val chatBridgeService = ChatBridgeService(config, gateway)
    val iatSignUrlService = IatSignUrlService(config.iat)

    embeddedServer(Netty, host = config.host, port = config.port) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                }
            )
        }

        routing {
            get("/health") {
                call.respond(mapOf("status" to "ok", "service" to "jarvis-server"))
            }

            get("/api/voice/iat-sign-url") {
                val traceId = "req_${System.currentTimeMillis()}"
                val header = call.request.header("Authorization")
                if (!chatBridgeService.isAuthorized(header)) {
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
                        userKey = config.userId,
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

            webSocket("/ws/chat") {
                val header = call.request.header("Authorization")
                if (!chatBridgeService.isAuthorized(header)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid bearer token"))
                    return@webSocket
                }

                chatBridgeService.handleSession(this)
            }
        }
    }.start(wait = true)
}
