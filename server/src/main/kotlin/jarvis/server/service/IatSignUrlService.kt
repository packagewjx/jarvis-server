package jarvis.server.service

import jarvis.server.config.XfyunIatConfig
import jarvis.server.model.IatSessionConfig
import jarvis.server.model.IatSignUrlResponseData
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IatSignUrlService(
    private val config: XfyunIatConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val rateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        windowMs = 60_000,
        limit = config.rateLimitPerMinute,
    ),
) {
    fun createSignedUrl(
        userKey: String,
        sampleRateRaw: String?,
        domainRaw: String?,
        languageRaw: String?,
        accentRaw: String?,
    ): IatSignResult {
        val appId = config.appId ?: return errorResult(
            code = 50002,
            message = "IAT_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_IAT_APP_ID",
        )
        val apiKey = config.apiKey ?: return errorResult(
            code = 50002,
            message = "IAT_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_IAT_API_KEY",
        )
        val apiSecret = config.apiSecret ?: return errorResult(
            code = 50002,
            message = "IAT_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_IAT_API_SECRET",
        )

        if (!rateLimiter.tryAcquire(userKey, clock.millis())) {
            return errorResult(
                code = 42901,
                message = "IAT_SIGN_RATE_LIMITED",
                detail = "too many requests",
            )
        }

        val params = parseAndValidateParams(sampleRateRaw, domainRaw, languageRaw, accentRaw)
            ?: return errorResult(
                code = 40001,
                message = "IAT_SIGN_INVALID_ARGUMENT",
                detail = "sampleRate must be 16000 or 8000; domain=slm; language=zh_cn",
            )

        return try {
            val now = Instant.now(clock)
            val date = RFC_1123_GMT.format(now)
            val signatureOrigin = buildString {
                append("host: ")
                append(config.host)
                append('\n')
                append("date: ")
                append(date)
                append('\n')
                append("GET ")
                append(config.path)
                append(" HTTP/1.1")
            }

            val signature = base64HmacSha256(signatureOrigin, apiSecret)
            val authorizationOrigin =
                "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
            val authorization = Base64.getEncoder()
                .encodeToString(authorizationOrigin.toByteArray(StandardCharsets.UTF_8))

            val wsUrl = buildString {
                append("wss://")
                append(config.host)
                append(config.path)
                append("?authorization=")
                append(urlEncode(authorization))
                append("&date=")
                append(urlEncode(date))
                append("&host=")
                append(urlEncode(config.host))
            }

            val ttlSec = config.ttlSec.coerceAtLeast(1)
            val data = IatSignUrlResponseData(
                wsUrl = wsUrl,
                expireAt = now.toEpochMilli() + ttlSec * 1000,
                ttlSec = ttlSec,
                config = IatSessionConfig(
                    appId = appId,
                    sampleRate = params.sampleRate,
                    domain = params.domain,
                    language = params.language,
                    accent = params.accent,
                    audioEncoding = config.audioEncoding,
                ),
            )
            IatSignResult.Success(data)
        } catch (ex: Exception) {
            errorResult(
                code = 50001,
                message = "IAT_SIGN_GENERATE_FAILED",
                detail = ex.message ?: "unable to generate sign url",
            )
        }
    }

    private fun parseAndValidateParams(
        sampleRateRaw: String?,
        domainRaw: String?,
        languageRaw: String?,
        accentRaw: String?,
    ): IatParams? {
        val sampleRate = sampleRateRaw?.toIntOrNull() ?: config.defaultSampleRate
        if (sampleRate != 16000 && sampleRate != 8000) {
            return null
        }

        val domain = domainRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultDomain
        if (domain != "slm") {
            return null
        }

        val language = languageRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultLanguage
        if (language != "zh_cn") {
            return null
        }

        val accent = accentRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultAccent
        return IatParams(
            sampleRate = sampleRate,
            domain = domain,
            language = language,
            accent = accent,
        )
    }

    private fun base64HmacSha256(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun errorResult(code: Int, message: String, detail: String): IatSignResult.Error {
        return IatSignResult.Error(
            code = code,
            message = message,
            detail = detail,
        )
    }

    private data class IatParams(
        val sampleRate: Int,
        val domain: String,
        val language: String,
        val accent: String,
    )

    companion object {
        private val RFC_1123_GMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}

sealed interface IatSignResult {
    data class Success(val data: IatSignUrlResponseData) : IatSignResult

    data class Error(
        val code: Int,
        val message: String,
        val detail: String,
    ) : IatSignResult
}

class FixedWindowRateLimiter(
    private val windowMs: Long,
    private val limit: Int,
) {
    private val counters = ConcurrentHashMap<String, Counter>()

    fun tryAcquire(key: String, nowMs: Long): Boolean {
        val effectiveLimit = limit.coerceAtLeast(1)
        var allowed = false
        counters.compute(key) { _, current ->
            val refreshed = if (current == null || nowMs - current.windowStartMs >= windowMs) {
                Counter(windowStartMs = nowMs, count = 0)
            } else {
                current
            }
            if (refreshed.count >= effectiveLimit) {
                allowed = false
                refreshed
            } else {
                allowed = true
                refreshed.copy(count = refreshed.count + 1)
            }
        }
        return allowed
    }

    private data class Counter(
        val windowStartMs: Long,
        val count: Int,
    )
}
