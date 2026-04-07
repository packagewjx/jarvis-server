package jarvis.server.service

import jarvis.server.config.XfyunIsvConfig
import jarvis.server.model.IsvSessionConfig
import jarvis.server.model.IsvSignUrlResponseData
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class IsvSignUrlService(
    private val config: XfyunIsvConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val rateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        windowMs = 60_000,
        limit = config.rateLimitPerMinute,
    ),
) {
    fun createSignedUrl(userKey: String): IsvSignResult {
        val appId = config.appId ?: return errorResult(
            code = 50002,
            message = "ISV_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_ISV_APP_ID",
        )
        val apiKey = config.apiKey ?: return errorResult(
            code = 50002,
            message = "ISV_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_ISV_API_KEY",
        )
        val apiSecret = config.apiSecret ?: return errorResult(
            code = 50002,
            message = "ISV_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_ISV_API_SECRET",
        )

        if (!rateLimiter.tryAcquire(userKey, clock.millis())) {
            return errorResult(
                code = 42901,
                message = "ISV_SIGN_RATE_LIMITED",
                detail = "too many requests",
            )
        }

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
                append("POST ")
                append(config.path)
                append(" HTTP/1.1")
            }

            val signature = base64HmacSha256(signatureOrigin, apiSecret)
            val authorizationOrigin =
                "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
            val authorization = Base64.getEncoder()
                .encodeToString(authorizationOrigin.toByteArray(StandardCharsets.UTF_8))

            val requestUrl = buildString {
                append("https://")
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
            val data = IsvSignUrlResponseData(
                requestUrl = requestUrl,
                expireAt = now.toEpochMilli() + ttlSec * 1000,
                ttlSec = ttlSec,
                config = IsvSessionConfig(
                    appId = appId,
                    host = config.host,
                    path = config.path,
                    method = "POST",
                ),
            )
            IsvSignResult.Success(data)
        } catch (ex: Exception) {
            errorResult(
                code = 50001,
                message = "ISV_SIGN_GENERATE_FAILED",
                detail = ex.message ?: "unable to generate sign url",
            )
        }
    }

    private fun base64HmacSha256(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun errorResult(code: Int, message: String, detail: String): IsvSignResult.Error {
        return IsvSignResult.Error(
            code = code,
            message = message,
            detail = detail,
        )
    }

    companion object {
        private val RFC_1123_GMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}

sealed interface IsvSignResult {
    data class Success(val data: IsvSignUrlResponseData) : IsvSignResult

    data class Error(
        val code: Int,
        val message: String,
        val detail: String,
    ) : IsvSignResult
}
