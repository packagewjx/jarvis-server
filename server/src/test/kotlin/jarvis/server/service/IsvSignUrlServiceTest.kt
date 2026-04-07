package jarvis.server.service

import jarvis.server.config.XfyunIsvConfig
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IsvSignUrlServiceTest {
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-04-07T02:12:21Z"), ZoneOffset.UTC)
    private val credentialA = "mock_credential_a"
    private val credentialB = "mock_credential_b"

    @Test
    fun `creates signed request url with defaults`() {
        val service = IsvSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
        )

        val success = result as IsvSignResult.Success
        val data = success.data
        val query = parseQuery(data.requestUrl)

        assertTrue(data.requestUrl.startsWith("https://api.xf-yun.com/v1/private/s1aa729d0?"))
        assertEquals("api.xf-yun.com", query["host"])
        assertNotNull(query["authorization"])
        assertEquals("Tue, 07 Apr 2026 02:12:21 GMT", query["date"])
        assertEquals(120, data.ttlSec)
        assertEquals(1775528061000L, data.expireAt)
        assertEquals("64ff2388", data.config.appId)
        assertEquals("api.xf-yun.com", data.config.host)
        assertEquals("/v1/private/s1aa729d0", data.config.path)
        assertEquals("POST", data.config.method)
    }

    @Test
    fun `returns config missing when app id absent`() {
        val service = IsvSignUrlService(
            config = validConfig().copy(appId = null),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 10),
        )

        val result = service.createSignedUrl(
            userKey = "user_1",
        )

        val error = result as IsvSignResult.Error
        assertEquals(50002, error.code)
        assertEquals("ISV_CONFIG_MISSING", error.message)
    }

    @Test
    fun `rate limiter blocks excessive requests`() {
        val service = IsvSignUrlService(
            config = validConfig(),
            clock = fixedClock,
            rateLimiter = FixedWindowRateLimiter(windowMs = 60_000, limit = 1),
        )

        val first = service.createSignedUrl(userKey = "user_1")
        val second = service.createSignedUrl(userKey = "user_1")

        assertTrue(first is IsvSignResult.Success)
        val error = second as IsvSignResult.Error
        assertEquals(42901, error.code)
        assertEquals("ISV_SIGN_RATE_LIMITED", error.message)
    }

    private fun validConfig(): XfyunIsvConfig {
        return XfyunIsvConfig(
            appId = "64ff2388",
            apiKey = credentialA,
            apiSecret = credentialB,
            host = "api.xf-yun.com",
            path = "/v1/private/s1aa729d0",
            ttlSec = 120,
            rateLimitPerMinute = 30,
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
