package jarvis.server.config

import java.net.URI

data class AppConfig(
    val host: String,
    val port: Int,
    val channel: ChannelConfig,
    val iat: XfyunIatConfig,
    val tts: XfyunTtsConfig,
    val superTts: XfyunSuperTtsConfig,
    val isv: XfyunIsvConfig,
    val database: DatabaseConfig,
    val chatPersistence: ChatPersistenceConfig,
    val jwt: JwtConfig,
) {
    companion object {
        fun fromEnvironment(): AppConfig {
            return AppConfig(
                host = env("JARVIS_SERVER_HOST", "0.0.0.0"),
                port = env("JARVIS_SERVER_PORT", "8080").toInt(),
                channel = ChannelConfig.fromEnvironment(),
                iat = XfyunIatConfig.fromEnvironment(),
                tts = XfyunTtsConfig.fromEnvironment(),
                superTts = XfyunSuperTtsConfig.fromEnvironment(),
                isv = XfyunIsvConfig.fromEnvironment(),
                database = DatabaseConfig.fromEnvironment(),
                chatPersistence = ChatPersistenceConfig.fromEnvironment(),
                jwt = JwtConfig.fromEnvironment(),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }
    }
}

data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    companion object {
        fun fromEnvironment(): DatabaseConfig {
            return DatabaseConfig(
                jdbcUrl = env("JARVIS_DB_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/jarvis"),
                user = env("JARVIS_DB_USER", "jarvis"),
                password = env("JARVIS_DB_PASSWORD", "jarvis"),
                maxPoolSize = env("JARVIS_DB_MAX_POOL_SIZE", "10").toInt(),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }
    }
}

data class ChatPersistenceConfig(
    val retentionDays: Int,
) {
    companion object {
        fun fromEnvironment(): ChatPersistenceConfig {
            return ChatPersistenceConfig(
                retentionDays = env("JARVIS_CHAT_RETENTION_DAYS", "7").toInt(),
            )
        }

        private fun env(name: String, default: String? = null): String {
            return System.getenv(name)
                ?: default
                ?: error("Missing required environment variable: $name")
        }
    }
}

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val accessTtlSec: Long,
    val refreshTtlSec: Long,
) {
    companion object {
        fun fromEnvironment(): JwtConfig {
            return JwtConfig(
                secret = env("JARVIS_JWT_SECRET", "dev-jwt-secret-change-me"),
                issuer = env("JARVIS_JWT_ISSUER", "jarvis-server"),
                accessTtlSec = env("JARVIS_JWT_ACCESS_TTL_SEC", "7200").toLong(),
                refreshTtlSec = env("JARVIS_JWT_REFRESH_TTL_SEC", "2592000").toLong(),
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
    val appId: String?,
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
                appId = optionalEnv("JARVIS_XFYUN_IAT_APP_ID"),
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

data class XfyunTtsConfig(
    val appId: String?,
    val apiKey: String?,
    val apiSecret: String?,
    val host: String,
    val path: String,
    val ttlSec: Long,
    val rateLimitPerMinute: Int,
    val defaultVcn: String,
    val defaultSpeed: Int,
    val defaultPitch: Int,
    val defaultVolume: Int,
    val defaultSampleRate: Int,
    val defaultAudioEncoding: String,
    val defaultTextEncoding: String,
) {
    companion object {
        fun fromEnvironment(): XfyunTtsConfig {
            return XfyunTtsConfig(
                appId = optionalEnv("JARVIS_XFYUN_TTS_APP_ID")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_APP_ID"),
                apiKey = optionalEnv("JARVIS_XFYUN_TTS_API_KEY")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_API_KEY"),
                apiSecret = optionalEnv("JARVIS_XFYUN_TTS_API_SECRET")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_API_SECRET"),
                host = env("JARVIS_XFYUN_TTS_HOST", "tts-api.xfyun.cn"),
                path = normalizePath(env("JARVIS_XFYUN_TTS_PATH", "/v2/tts")),
                ttlSec = env("JARVIS_XFYUN_TTS_TTL_SEC", "120").toLong(),
                rateLimitPerMinute = env("JARVIS_XFYUN_TTS_RATE_LIMIT_PER_MIN", "30").toInt(),
                defaultVcn = env("JARVIS_XFYUN_TTS_DEFAULT_VCN", "xiaoyan"),
                defaultSpeed = env("JARVIS_XFYUN_TTS_DEFAULT_SPEED", "50").toInt(),
                defaultPitch = env("JARVIS_XFYUN_TTS_DEFAULT_PITCH", "50").toInt(),
                defaultVolume = env("JARVIS_XFYUN_TTS_DEFAULT_VOLUME", "50").toInt(),
                defaultSampleRate = env("JARVIS_XFYUN_TTS_DEFAULT_SAMPLE_RATE", "16000").toInt(),
                defaultAudioEncoding = env("JARVIS_XFYUN_TTS_DEFAULT_AUDIO_ENCODING", "lame"),
                defaultTextEncoding = env("JARVIS_XFYUN_TTS_DEFAULT_TEXT_ENCODING", "utf8"),
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

data class XfyunSuperTtsConfig(
    val appId: String?,
    val apiKey: String?,
    val apiSecret: String?,
    val host: String,
    val path: String,
    val ttlSec: Long,
    val rateLimitPerMinute: Int,
    val defaultVcn: String,
    val defaultSpeed: Int,
    val defaultPitch: Int,
    val defaultVolume: Int,
    val defaultSampleRate: Int,
    val defaultAudioEncoding: String,
    val defaultReg: Int,
    val defaultRdn: Int,
    val defaultRhy: Int,
    val defaultScn: Int,
) {
    companion object {
        fun fromEnvironment(): XfyunSuperTtsConfig {
            return XfyunSuperTtsConfig(
                appId = optionalEnv("JARVIS_XFYUN_SUPER_TTS_APP_ID")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_APP_ID"),
                apiKey = optionalEnv("JARVIS_XFYUN_SUPER_TTS_API_KEY")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_API_KEY"),
                apiSecret = optionalEnv("JARVIS_XFYUN_SUPER_TTS_API_SECRET")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_API_SECRET"),
                host = env("JARVIS_XFYUN_SUPER_TTS_HOST", "cbm01.cn-huabei-1.xf-yun.com"),
                path = normalizePath(env("JARVIS_XFYUN_SUPER_TTS_PATH", "/v1/private/mcd9m97e6")),
                ttlSec = env("JARVIS_XFYUN_SUPER_TTS_TTL_SEC", "120").toLong(),
                rateLimitPerMinute = env("JARVIS_XFYUN_SUPER_TTS_RATE_LIMIT_PER_MIN", "30").toInt(),
                defaultVcn = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_VCN", "x4_lingxiaoxuan_oral"),
                defaultSpeed = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_SPEED", "50").toInt(),
                defaultPitch = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_PITCH", "50").toInt(),
                defaultVolume = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_VOLUME", "50").toInt(),
                defaultSampleRate = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_SAMPLE_RATE", "24000").toInt(),
                defaultAudioEncoding = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_AUDIO_ENCODING", "raw"),
                defaultReg = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_REG", "0").toInt(),
                defaultRdn = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_RDN", "0").toInt(),
                defaultRhy = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_RHY", "0").toInt(),
                defaultScn = env("JARVIS_XFYUN_SUPER_TTS_DEFAULT_SCN", "0").toInt(),
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

data class XfyunIsvConfig(
    val appId: String?,
    val apiKey: String?,
    val apiSecret: String?,
    val host: String,
    val path: String,
    val ttlSec: Long,
    val rateLimitPerMinute: Int,
) {
    companion object {
        fun fromEnvironment(): XfyunIsvConfig {
            return XfyunIsvConfig(
                appId = optionalEnv("JARVIS_XFYUN_ISV_APP_ID")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_APP_ID"),
                apiKey = optionalEnv("JARVIS_XFYUN_ISV_API_KEY")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_API_KEY"),
                apiSecret = optionalEnv("JARVIS_XFYUN_ISV_API_SECRET")
                    ?: optionalEnv("JARVIS_XFYUN_IAT_API_SECRET"),
                host = env("JARVIS_XFYUN_ISV_HOST", "api.xf-yun.com"),
                path = normalizePath(env("JARVIS_XFYUN_ISV_PATH", "/v1/private/s1aa729d0")),
                ttlSec = env("JARVIS_XFYUN_ISV_TTL_SEC", "120").toLong(),
                rateLimitPerMinute = env("JARVIS_XFYUN_ISV_RATE_LIMIT_PER_MIN", "30").toInt(),
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
