package jarvis.server.service

import jarvis.server.config.XfyunSuperTtsConfig
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuperTtsSignUrlServiceTest {
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-04-07T10:12:21Z"), ZoneOffset.UTC)
    private val credentialA = "mock_credential_a"
    private val credentialB = "mock_credential_b"

    @Test
    fun `creates signed url with defaults`() {
        val service = SuperTtsSignUrlService(
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
            regRaw = null,
            rdnRaw = null,
            rhyRaw = null,
            scnRaw = null,
        )

        val success = result as SuperTtsSignResult.Success
        val data = success.data
        val query = parseQuery(data.wsUrl)

        assertTrue(data.wsUrl.startsWith("wss://cbm01.cn-huabei-1.xf-yun.com/v1/private/mcd9m97e6?"))
        assertEquals("cbm01.cn-huabei-1.xf-yun.com", query["host"])
        assertNotNull(query["authorization"])
        assertEquals("Tue, 07 Apr 2026 10:12:21 GMT", query["date"])
        assertEquals(120, data.ttlSec)
        assertEquals(1775556861000L, data.expireAt)
        assertEquals("64ff2388", data.config.appId)
        assertEquals("x4_lingxiaoxuan_oral", data.config.vcn)
        assertEquals(50, data.config.speed)
        assertEquals(50, data.config.pitch)
        assertEquals(50, data.config.volume)
        assertEquals("raw", data.config.aue)
        assertEquals("audio/L16;rate=24000", data.config.auf)
        assertEquals(0, data.config.reg)
        assertEquals(0, data.config.rdn)
        assertEquals(0, data.config.rhy)
        assertEquals(0, data.config.scn)
    }

    @Test
    fun `accepts custom params`() {
        val service = SuperTtsSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = "x4_guanshan",
            speedRaw = "70",
            pitchRaw = "60",
            volumeRaw = "80",
            sampleRateRaw = "16000",
            audioEncodingRaw = "lame",
            regRaw = "1",
            rdnRaw = "1",
            rhyRaw = "1",
            scnRaw = "1",
        )

        val success = result as SuperTtsSignResult.Success
        assertEquals("x4_guanshan", success.data.config.vcn)
        assertEquals(70, success.data.config.speed)
        assertEquals(60, success.data.config.pitch)
        assertEquals(80, success.data.config.volume)
        assertEquals("lame", success.data.config.aue)
        assertEquals("audio/L16;rate=16000", success.data.config.auf)
        assertEquals(1, success.data.config.reg)
        assertEquals(1, success.data.config.rdn)
        assertEquals(1, success.data.config.rhy)
        assertEquals(1, success.data.config.scn)
    }

    @Test
    fun `rejects invalid params`() {
        val service = SuperTtsSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = null,
            speedRaw = "999",
            pitchRaw = null,
            volumeRaw = null,
            sampleRateRaw = null,
            audioEncodingRaw = null,
            regRaw = null,
            rdnRaw = null,
            rhyRaw = null,
            scnRaw = null,
        )

        val error = result as SuperTtsSignResult.Error
        assertEquals(40001, error.code)
        assertEquals("SUPER_TTS_SIGN_INVALID_ARGUMENT", error.message)
    }

    @Test
    fun `returns config missing when api secret absent`() {
        val service = SuperTtsSignUrlService(
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
            regRaw = null,
            rdnRaw = null,
            rhyRaw = null,
            scnRaw = null,
        )

        val error = result as SuperTtsSignResult.Error
        assertEquals(50002, error.code)
        assertEquals("SUPER_TTS_CONFIG_MISSING", error.message)
    }

    @Test
    fun `rate limiter blocks excessive requests`() {
        val service = SuperTtsSignUrlService(
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
            regRaw = null,
            rdnRaw = null,
            rhyRaw = null,
            scnRaw = null,
        )
        val second = service.createSignedUrl(
            userKey = "user_1",
            vcnRaw = null,
            speedRaw = null,
            pitchRaw = null,
            volumeRaw = null,
            sampleRateRaw = null,
            audioEncodingRaw = null,
            regRaw = null,
            rdnRaw = null,
            rhyRaw = null,
            scnRaw = null,
        )

        assertTrue(first is SuperTtsSignResult.Success)
        val error = second as SuperTtsSignResult.Error
        assertEquals(42901, error.code)
        assertEquals("SUPER_TTS_SIGN_RATE_LIMITED", error.message)
    }

    private fun validConfig(): XfyunSuperTtsConfig {
        return XfyunSuperTtsConfig(
            appId = "64ff2388",
            apiKey = credentialA,
            apiSecret = credentialB,
            host = "cbm01.cn-huabei-1.xf-yun.com",
            path = "/v1/private/mcd9m97e6",
            ttlSec = 120,
            rateLimitPerMinute = 30,
            defaultVcn = "x4_lingxiaoxuan_oral",
            defaultSpeed = 50,
            defaultPitch = 50,
            defaultVolume = 50,
            defaultSampleRate = 24000,
            defaultAudioEncoding = "raw",
            defaultReg = 0,
            defaultRdn = 0,
            defaultRhy = 0,
            defaultScn = 0,
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
