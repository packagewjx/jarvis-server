package jarvis.server.config

import java.net.URI

data class AppConfig(
    val host: String,
    val port: Int,
    val authToken: String,
    val userId: String,
    val channel: ChannelConfig,
    val iat: XfyunIatConfig,
) {
    companion object {
        fun fromEnvironment(): AppConfig {
            return AppConfig(
                host = env("JARVIS_SERVER_HOST", "0.0.0.0"),
                port = env("JARVIS_SERVER_PORT", "8080").toInt(),
                authToken = env("JARVIS_SERVER_AUTH_TOKEN", "dev-client-token"),
                userId = env("JARVIS_SERVER_USER_ID", "dev-user"),
                channel = ChannelConfig.fromEnvironment(),
                iat = XfyunIatConfig.fromEnvironment(),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }
    }
}

data class XfyunIatConfig(
    val apiKey: String?,
    val apiSecret: String?,
    val host: String,
    val path: String,
    val ttlSec: Long,
    val rateLimitPerMinute: Int,
    val defaultSampleRate: Int,
    val defaultDomain: String,
    val defaultLanguage: String,
    val defaultAccent: String,
    val audioEncoding: String,
) {
    companion object {
        fun fromEnvironment(): XfyunIatConfig {
            return XfyunIatConfig(
                apiKey = optionalEnv("JARVIS_XFYUN_IAT_API_KEY"),
                apiSecret = optionalEnv("JARVIS_XFYUN_IAT_API_SECRET"),
                host = env("JARVIS_XFYUN_IAT_HOST", "iat.cn-huabei-1.xf-yun.com"),
                path = normalizePath(env("JARVIS_XFYUN_IAT_PATH", "/v1")),
                ttlSec = env("JARVIS_XFYUN_IAT_TTL_SEC", "120").toLong(),
                rateLimitPerMinute = env("JARVIS_XFYUN_IAT_RATE_LIMIT_PER_MIN", "30").toInt(),
                defaultSampleRate = env("JARVIS_XFYUN_IAT_DEFAULT_SAMPLE_RATE", "16000").toInt(),
                defaultDomain = env("JARVIS_XFYUN_IAT_DEFAULT_DOMAIN", "slm"),
                defaultLanguage = env("JARVIS_XFYUN_IAT_DEFAULT_LANGUAGE", "zh_cn"),
                defaultAccent = env("JARVIS_XFYUN_IAT_DEFAULT_ACCENT", "mulacc"),
                audioEncoding = env("JARVIS_XFYUN_IAT_AUDIO_ENCODING", "lame"),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }

        private fun optionalEnv(name: String): String? =
            System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

        private fun normalizePath(path: String): String =
            if (path.startsWith("/")) path else "/$path"
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
