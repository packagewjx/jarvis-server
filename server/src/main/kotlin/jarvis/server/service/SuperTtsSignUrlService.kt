package jarvis.server.service

import jarvis.server.config.XfyunSuperTtsConfig
import jarvis.server.model.SuperTtsSessionConfig
import jarvis.server.model.SuperTtsSignUrlResponseData
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

class SuperTtsSignUrlService(
    private val config: XfyunSuperTtsConfig,
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
        regRaw: String?,
        rdnRaw: String?,
        rhyRaw: String?,
        scnRaw: String?,
    ): SuperTtsSignResult {
        val appId = config.appId ?: return errorResult(
            code = 50002,
            message = "SUPER_TTS_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_SUPER_TTS_APP_ID",
        )
        val apiKey = config.apiKey ?: return errorResult(
            code = 50002,
            message = "SUPER_TTS_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_SUPER_TTS_API_KEY",
        )
        val apiSecret = config.apiSecret ?: return errorResult(
            code = 50002,
            message = "SUPER_TTS_CONFIG_MISSING",
            detail = "missing env JARVIS_XFYUN_SUPER_TTS_API_SECRET",
        )

        if (!rateLimiter.tryAcquire(userKey, clock.millis())) {
            return errorResult(
                code = 42901,
                message = "SUPER_TTS_SIGN_RATE_LIMITED",
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
            regRaw = regRaw,
            rdnRaw = rdnRaw,
            rhyRaw = rhyRaw,
            scnRaw = scnRaw,
        ) ?: return errorResult(
            code = 40001,
            message = "SUPER_TTS_SIGN_INVALID_ARGUMENT",
            detail = "vcn required; speed/pitch/volume: 0..100; sampleRate: 8000/16000/24000; audioEncoding: raw/lame/speex/speex-wb; reg/rdn/rhy/scn: 0/1",
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
            val data = SuperTtsSignUrlResponseData(
                wsUrl = wsUrl,
                expireAt = now.toEpochMilli() + ttlSec * 1000,
                ttlSec = ttlSec,
                config = SuperTtsSessionConfig(
                    appId = appId,
                    vcn = params.vcn,
                    speed = params.speed,
                    pitch = params.pitch,
                    volume = params.volume,
                    aue = params.aue,
                    auf = params.auf,
                    reg = params.reg,
                    rdn = params.rdn,
                    rhy = params.rhy,
                    scn = params.scn,
                ),
            )
            SuperTtsSignResult.Success(data)
        } catch (ex: Exception) {
            errorResult(
                code = 50001,
                message = "SUPER_TTS_SIGN_GENERATE_FAILED",
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
        regRaw: String?,
        rdnRaw: String?,
        rhyRaw: String?,
        scnRaw: String?,
    ): SuperTtsParams? {
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
        if (sampleRate !in setOf(8000, 16000, 24000)) {
            return null
        }

        val aue = (audioEncodingRaw?.trim()?.takeIf { it.isNotEmpty() } ?: config.defaultAudioEncoding)
            .lowercase(Locale.ROOT)
        if (aue !in setOf("raw", "lame", "speex", "speex-wb")) {
            return null
        }

        val reg = regRaw?.toIntOrNull() ?: config.defaultReg
        if (reg !in 0..1) {
            return null
        }
        val rdn = rdnRaw?.toIntOrNull() ?: config.defaultRdn
        if (rdn !in 0..1) {
            return null
        }
        val rhy = rhyRaw?.toIntOrNull() ?: config.defaultRhy
        if (rhy !in 0..1) {
            return null
        }
        val scn = scnRaw?.toIntOrNull() ?: config.defaultScn
        if (scn !in 0..1) {
            return null
        }

        return SuperTtsParams(
            vcn = vcn,
            speed = speed,
            pitch = pitch,
            volume = volume,
            aue = aue,
            auf = "audio/L16;rate=$sampleRate",
            reg = reg,
            rdn = rdn,
            rhy = rhy,
            scn = scn,
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

    private fun errorResult(code: Int, message: String, detail: String): SuperTtsSignResult.Error {
        return SuperTtsSignResult.Error(
            code = code,
            message = message,
            detail = detail,
        )
    }

    private data class SuperTtsParams(
        val vcn: String,
        val speed: Int,
        val pitch: Int,
        val volume: Int,
        val aue: String,
        val auf: String,
        val reg: Int,
        val rdn: Int,
        val rhy: Int,
        val scn: Int,
    )

    companion object {
        private val RFC_1123_GMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}

sealed interface SuperTtsSignResult {
    data class Success(val data: SuperTtsSignUrlResponseData) : SuperTtsSignResult

    data class Error(
        val code: Int,
        val message: String,
        val detail: String,
    ) : SuperTtsSignResult
}
