package jarvis.server.service

import jarvis.server.config.XfyunTtsConfig
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TtsSignUrlServiceTest {
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-04-05T02:12:21Z"), ZoneOffset.UTC)
    private val credentialA = "mock_credential_a"
    private val credentialB = "mock_credential_b"

    @Test
    fun `creates signed url with defaults`() {
        val service = TtsSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = null,
            speedRaw = null,
            pitchRaw = null,
            volumeRaw = null,
            sampleRateRaw = null,
            audioEncodingRaw = null,
            textEncodingRaw = null,
        )

        val success = result as TtsSignResult.Success
        val data = success.data
        val query = parseQuery(data.wsUrl)

        assertTrue(data.wsUrl.startsWith("wss://tts-api.xfyun.cn/v2/tts?"))
        assertEquals("tts-api.xfyun.cn", query["host"])
        assertNotNull(query["authorization"])
        assertEquals("Sun, 05 Apr 2026 02:12:21 GMT", query["date"])
        assertEquals(120, data.ttlSec)
        assertEquals(1775355261000L, data.expireAt)
        assertEquals("64ff2388", data.config.appId)
        assertEquals("xiaoyan", data.config.vcn)
        assertEquals(50, data.config.speed)
        assertEquals(50, data.config.pitch)
        assertEquals(50, data.config.volume)
        assertEquals("lame", data.config.aue)
        assertEquals("audio/L16;rate=16000", data.config.auf)
        assertEquals("utf8", data.config.tte)
    }

    @Test
    fun `accepts custom params`() {
        val service = TtsSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = "xiaofeng",
            speedRaw = "70",
            pitchRaw = "60",
            volumeRaw = "80",
            sampleRateRaw = "8000",
            audioEncodingRaw = "raw",
            textEncodingRaw = "unicode",
        )

        val success = result as TtsSignResult.Success
        assertEquals("xiaofeng", success.data.config.vcn)
        assertEquals(70, success.data.config.speed)
        assertEquals(60, success.data.config.pitch)
        assertEquals(80, success.data.config.volume)
        assertEquals("raw", success.data.config.aue)
        assertEquals("audio/L16;rate=8000", success.data.config.auf)
        assertEquals("unicode", success.data.config.tte)
    }

    @Test
    fun `rejects invalid params`() {
        val service = TtsSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = "xiaoyan",
            speedRaw = "200",
            pitchRaw = "50",
            volumeRaw = "50",
            sampleRateRaw = "16000",
            audioEncodingRaw = "lame",
            textEncodingRaw = "utf8",
        )

        val error = result as TtsSignResult.Error
        assertEquals(40001, error.code)
        assertEquals("TTS_SIGN_INVALID_ARGUMENT", error.message)
    }

    @Test
    fun `returns config missing when api secret absent`() {
        val service = TtsSignUrlService(
            config = validConfig().copy(apiSecret = null),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = null,
            speedRaw = null,
            pitchRaw = null,
            volumeRaw = null,
            sampleRateRaw = null,
            audioEncodingRaw = null,
            textEncodingRaw = null,
        )

        val error = result as TtsSignResult.Error
        assertEquals(50002, error.code)
        assertEquals("TTS_CONFIG_MISSING", error.message)
    }

    @Test
    fun `rate limiter blocks excessive requests`() {
        val service = TtsSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 1),
        )

        val first = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = null,
            speedRaw = null,
            pitchRaw = null,
            volumeRaw = null,
            sampleRateRaw = null,
            audioEncodingRaw = null,
            textEncodingRaw = null,
        )
        val second = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = null,
            speedRaw = null,
            pitchRaw = null,
            volumeRaw = null,
            sampleRateRaw = null,
            audioEncodingRaw = null,
            textEncodingRaw = null,
        )

        assertTrue(first is TtsSignResult.Success)
        val error = second as TtsSignResult.Error
        assertEquals(42901, error.code)
        assertEquals("TTS_SIGN_RATE_LIMITED", error.message)
    }

    private fun validConfig(): XfyunTtsConfig {
        return XfyunTtsConfig(
            appId = "64ff2388",
            apiKey = credentialA,
            apiSecret = credentialB,
            host = "tts-api.xfyun.cn",
            path = "/v2/tts",
            ttlSec = 120,
            rateLimitPerMinute = 30,
            defaultVcn = "xiaoyan",
            defaultSpeed = 50,
            defaultPitch = 50,
            defaultVolume = 50,
            defaultSampleRate = 16000,
            defaultAudioEncoding = "lame",
            defaultTextEncoding = "utf8",
        )
    }

    private fun parseQuery(url: String): Map<String, String> {
        return URI(url).rawQuery
            .split("&")
            .mapNotNull { item ->
                val parts = item.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to java.net.URLDecoder.decode(parts[1], Charsets.UTF_8)
                } else {
                    null
                }
            }
            .toMap()
    }
}
