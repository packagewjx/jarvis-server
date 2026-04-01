package jarvis.server.config

import java.net.URI

data class AppConfig(
    val host: String,
    val port: Int,
    val authToken: String,
    val userId: String,
    val channel: ChannelConfig,
) {
    companion object {
        fun fromEnvironment(): AppConfig {
            return AppConfig(
                host = env("JARVIS_SERVER_HOST", "0.0.0.0"),
                port = env("JARVIS_SERVER_PORT", "8080").toInt(),
                authToken = env("JARVIS_SERVER_AUTH_TOKEN", "dev-client-token"),
                userId = env("JARVIS_SERVER_USER_ID", "dev-user"),
                channel = ChannelConfig.fromEnvironment(),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }
    }
}

data class ChannelConfig(
    val baseUrl: String,
    val authToken: String,
    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val caCertPath: String?,
    val hostnameVerification: Boolean,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https") { "JARVIS_CHANNEL_BASE_URL must use https" }
    }

    companion object {
        fun fromEnvironment(): ChannelConfig {
            return ChannelConfig(
                baseUrl = env("JARVIS_CHANNEL_BASE_URL"),
                authToken = env("JARVIS_CHANNEL_AUTH_TOKEN"),
                connectTimeoutMs = env("JARVIS_CHANNEL_CONNECT_TIMEOUT_MS", "10000").toLong(),
                readTimeoutMs = env("JARVIS_CHANNEL_READ_TIMEOUT_MS", "60000").toLong(),
                caCertPath = System.getenv("JARVIS_CHANNEL_CA_CERT_PATH"),
                hostnameVerification = env("JARVIS_CHANNEL_HOSTNAME_VERIFICATION", "true").toBooleanStrict(),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }
    }
}
