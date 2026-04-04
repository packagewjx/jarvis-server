package jarvis.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import jarvis.server.config.AppConfig
import jarvis.server.gateway.HttpsSseChannelGateway
import jarvis.server.service.ChatBridgeService
import kotlinx.serialization.json.Json

fun main() {
    val config = AppConfig.fromEnvironment()
    val gateway = HttpsSseChannelGateway(config.channel)
    val chatBridgeService = ChatBridgeService(config, gateway)

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
