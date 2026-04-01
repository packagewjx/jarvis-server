package jarvis.server.gateway

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import jarvis.server.config.ChannelConfig
import jarvis.server.model.ChannelSendAccepted
import jarvis.server.model.ChannelSendRequest
import jarvis.server.model.ChannelStreamEvent
import jarvis.server.util.TlsUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class HttpsSseChannelGateway(
    private val config: ChannelConfig,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    },
) : ChannelGateway {
    private val logger = KotlinLogging.logger {}

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeoutMs
            requestTimeoutMillis = config.readTimeoutMs
            socketTimeoutMillis = config.readTimeoutMs
        }
        engine {
            https {
                trustManager = TlsUtils.buildTrustManager(config.caCertPath)
                serverName = if (config.hostnameVerification) null else ""
            }
        }
        expectSuccess = false
    }

    override suspend fun submit(request: ChannelSendRequest): String {
        val response = client.post("${config.baseUrl}/internal/messages/send") {
            bearerAuth(config.authToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

        if (response.status.value !in 200..299) {
            throw IllegalStateException("Channel send failed with ${response.status.value}: ${response.bodyAsText()}")
        }

        return response.body<ChannelSendAccepted>().requestId
    }

    override fun streamEvents(requestId: String): Flow<ChannelStreamEvent> = flow {
        val response = client.get("${config.baseUrl}/internal/messages/stream/$requestId") {
            bearerAuth(config.authToken)
            headers.append(HttpHeaders.Accept, "text/event-stream")
        }

        if (response.status.value !in 200..299) {
            throw IllegalStateException("Channel stream failed with ${response.status.value}: ${response.bodyAsText()}")
        }

        val channel = response.bodyAsChannel()
        val dataBuffer = StringBuilder()

        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            when {
                line.startsWith("data:") -> {
                    dataBuffer.append(line.removePrefix("data:").trimStart())
                }
                line.isBlank() -> {
                    if (dataBuffer.isNotEmpty()) {
                        emit(json.decodeFromString<ChannelStreamEvent>(dataBuffer.toString()))
                        dataBuffer.clear()
                    }
                }
                else -> logger.debug { "Ignoring SSE line: $line" }
            }
        }

        if (dataBuffer.isNotEmpty()) {
            emit(json.decodeFromString<ChannelStreamEvent>(dataBuffer.toString()))
        }
    }
}
