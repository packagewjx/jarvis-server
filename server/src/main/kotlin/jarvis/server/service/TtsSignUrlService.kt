package jarvis.server.service

import jarvis.server.config.XfyunTtsConfig
import jarvis.server.model.TtsSessionConfig
import jarvis.server.model.TtsSignUrlResponseData
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

class TtsSignUrlService(
    private val config: XfyunTtsConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val rateLimiter: FixedWindowRateLimiter = FixedWindowRateLimiter(
        windowMs = 60_000,
        limit = config.rateLimitPerMinute,
    ),
) {
    fun createSignedUrl(
        userKey: String,
        vcnRaw: String?,
        speedRaw: String?,
        pitchRaw: String?,
        volumeRaw: String?,
        sampleRateRaw: String?,
        audioEncodingRaw: String?,
        textEncodingRaw: String?,
    ): TtsSignResult {
        val appId = config.appId ?: return errorResult(
            code = 50002,
            message = "TTS_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_TTS_APP_ID",
        )
        val apiKey = config.apiKey ?: return errorResult(
            code = 50002,
            message = "TTS_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_TTS_API_KEY",
        )
        val apiSecret = config.apiSecret ?: return errorResult(
            code = 50002,
            message = "TTS_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_TTS_API_SECRET",
        )

        if (!rateLimiter.tryAcquire(userKey, clock.millis())) {
            return errorResult(
                code = 42901,
                message = "TTS_SIGN_RATE_LIMITED",
                detail = "too many requests",
            )
        }

        val params = parseAndValidateParams(
            vcnRaw = vcnRaw,
            speedRaw = speedRaw,
            pitchRaw = pitchRaw,
            volumeRaw = volumeRaw,
            sampleRateRaw = sampleRateRaw,
            audioEncodingRaw = audioEncodingRaw,
            textEncodingRaw = textEncodingRaw,
        ) ?: return errorResult(
            code = 40001,
            message = "TTS_SIGN_INVALID_ARGUMENT",
            detail = "vcn required; speed/pitch/volume: 0..100; sampleRate: 8000/16000; audioEncoding: lame/raw/speex; tte: utf8/unicode",
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
            val data = TtsSignUrlResponseData(
                wsUrl = wsUrl,
                expireAt = now.toEpochMilli() + ttlSec * 1000,
                ttlSec = ttlSec,
                config = TtsSessionConfig(
                    appId = appId,
                    vcn = params.vcn,
                    speed = params.speed,
                    pitch = params.pitch,
                    volume = params.volume,
                    aue = params.aue,
                    auf = params.auf,
                    tte = params.tte,
                ),
            )
            TtsSignResult.Success(data)
        } catch (ex: Exception) {
            errorResult(
                code = 50001,
                message = "TTS_SIGN_GENERATE_FAILED",
                detail = ex.message ?: "unable to generate sign url",
            )
        }
    }

    private fun parseAndValidateParams(
        vcnRaw: String?,
        speedRaw: String?,
        pitchRaw: String?,
        volumeRaw: String?,
        sampleRateRaw: String?,
        audioEncodingRaw: String?,
        textEncodingRaw: String?,
    ): TtsParams? {
        val vcn = vcnRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultVcn

        val speed = speedRaw?.toIntOrNull() ?: config.defaultSpeed
        if (speed !in 0..100) {
            return null
        }

        val pitch = pitchRaw?.toIntOrNull() ?: config.defaultPitch
        if (pitch !in 0..100) {
            return null
        }

        val volume = volumeRaw?.toIntOrNull() ?: config.defaultVolume
        if (volume !in 0..100) {
            return null
        }

        val sampleRate = sampleRateRaw?.toIntOrNull() ?: config.defaultSampleRate
        if (sampleRate != 8000 && sampleRate != 16000) {
            return null
        }

        val aue = (audioEncodingRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultAudioEncoding)
            .lowercase(Locale.ROOT)
        if (aue !in setOf("lame", "raw", "speex")) {
            return null
        }

        val tte = (textEncodingRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultTextEncoding)
            .lowercase(Locale.ROOT)
        if (tte !in setOf("utf8", "unicode")) {
            return null
        }

        return TtsParams(
            vcn = vcn,
            speed = speed,
            pitch = pitch,
            volume = volume,
            aue = aue,
            auf = "audio/L16;rate=$sampleRate",
            tte = tte,
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

    private fun errorResult(code: Int, message: String, detail: String): TtsSignResult.Error {
        return TtsSignResult.Error(
            code = code,
            message = message,
            detail = detail,
        )
    }

    private data class TtsParams(
        val vcn: String,
        val speed: Int,
        val pitch: Int,
        val volume: Int,
        val aue: String,
        val auf: String,
        val tte: String,
    )

    companion object {
        private val RFC_1123_GMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}

sealed interface TtsSignResult {
    data class Success(val data: TtsSignUrlResponseData) : TtsSignResult

    data class Error(
        val code: Int,
        val message: String,
        val detail: String,
    ) : TtsSignResult
}
