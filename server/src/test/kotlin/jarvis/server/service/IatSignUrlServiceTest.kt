package jarvis.server.service

import jarvis.server.config.XfyunIatConfig
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IatSignUrlServiceTest {
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2024-05-14T08:46:48Z"), ZoneOffset.UTC)
    private val credentialA = "mock_credential_a"
    private val credentialB = "mock_credential_b"

    @Test
    fun `creates signed url with required params and defaults`() {
        val service = IatSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            sampleRateRaw = null,
            domainRaw = null,
            languageRaw = null,
            accentRaw = null,
        )

        val success = result as IatSignResult.Success
        val data = success.data
        val query = parseQuery(data.wsUrl)

        assertTrue(data.wsUrl.startsWith("wss://iat.cn-huabei-1.xf-yun.com/v1?"))
        assertEquals("iat.cn-huabei-1.xf-yun.com", query["host"])
        assertNotNull(query["authorization"])
        assertEquals("Tue, 14 May 2024 08:46:48 GMT", query["date"])
        assertEquals(120, data.ttlSec)
        assertEquals(1715676528000L, data.expireAt)
        assertEquals(16000, data.config.sampleRate)
        assertEquals("slm", data.config.domain)
        assertEquals("zh_cn", data.config.language)
        assertEquals("mulacc", data.config.accent)
        assertEquals("lame", data.config.audioEncoding)
    }

    @Test
    fun `rejects invalid query params`() {
        val service = IatSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            sampleRateRaw = "44100",
            domainRaw = "slm",
            languageRaw = "zh_cn",
            accentRaw = "mulacc",
        )

        val error = result as IatSignResult.Error
        assertEquals(40001, error.code)
        assertEquals("IAT_SIGN_INVALID_ARGUMENT", error.message)
    }

    @Test
    fun `returns config missing when api secret absent`() {
        val service = IatSignUrlService(
            config = validConfig().copy(apiSecret = null),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
            sampleRateRaw = null,
            domainRaw = null,
            languageRaw = null,
            accentRaw = null,
        )

        val error = result as IatSignResult.Error
        assertEquals(50002, error.code)
        assertEquals("IAT_CONFIG_MISSING", error.message)
    }

    @Test
    fun `rate limiter blocks excessive requests`() {
        val service = IatSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 1),
        )

        val first = service.createSignedUrl(
            userKey = "user_1",
            sampleRateRaw = null,
            domainRaw = null,
            languageRaw = null,
            accentRaw = null,
        )
        val second = service.createSignedUrl(
            userKey = "user_1",
            sampleRateRaw = null,
            domainRaw = null,
            languageRaw = null,
            accentRaw = null,
        )

        assertTrue(first is IatSignResult.Success)
        val error = second as IatSignResult.Error
        assertEquals(42901, error.code)
        assertEquals("IAT_SIGN_RATE_LIMITED", error.message)
    }

    private fun validConfig(): XfyunIatConfig {
        return XfyunIatConfig(
            apiKey = credentialA,
            apiSecret = credentialB,
            host = "iat.cn-huabei-1.xf-yun.com",
            path = "/v1",
            ttlSec = 120,
            rateLimitPerMinute = 30,
            defaultSampleRate = 16000,
            defaultDomain = "slm",
            defaultLanguage = "zh_cn",
            defaultAccent = "mulacc",
            audioEncoding = "lame",
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
