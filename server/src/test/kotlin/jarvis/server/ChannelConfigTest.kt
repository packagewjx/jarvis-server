package jarvis.server

import jarvis.server.config.ChannelConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ChannelConfigTest {
    @Test
    fun `rejects non https channel urls`() {
        assertFailsWith<IllegalArgumentException> {
            ChannelConfig(
                baseUrl = "http://localhost:9443",
                authToken = "token",
                connectTimeoutMs = 1_000,
                readTimeoutMs = 1_000,
                caCertPath = null,
                hostnameVerification = true,
            )
        }
    }
}
