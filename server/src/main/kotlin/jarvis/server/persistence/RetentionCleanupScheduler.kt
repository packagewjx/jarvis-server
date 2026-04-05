package jarvis.server.persistence

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RetentionCleanupScheduler(
    private val chatStore: ChatStore,
    private val retentionDays: Int,
) {
    private val logger = KotlinLogging.logger {}
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "chat-retention-cleaner").apply { isDaemon = true }
    }

    fun start() {
        runOnce()
        executor.scheduleAtFixedRate(
            { runOnce() },
            24,
            24,
            TimeUnit.HOURS,
        )
    }

    fun stop() {
        executor.shutdownNow()
    }

    private fun runOnce() {
        runCatching {
            kotlinx.coroutines.runBlocking {
                chatStore.cleanupExpired(retentionDays)
            }
        }.onSuccess { deleted ->
            if (deleted > 0) {
                logger.info { "Retention cleanup removed $deleted expired rows" }
            }
        }.onFailure { ex ->
            logger.error(ex) { "Retention cleanup failed" }
        }
    }
}
