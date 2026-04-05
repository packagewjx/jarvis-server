package jarvis.server.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import jarvis.server.config.DatabaseConfig
import org.flywaydb.core.Flyway

object DatabaseFactory {
    private val logger = KotlinLogging.logger {}

    fun createDataSource(config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.user
            password = config.password
            maximumPoolSize = config.maxPoolSize
            minimumIdle = 1
            connectionTimeout = 10_000
            idleTimeout = 60_000
            maxLifetime = 600_000
            poolName = "jarvis-hikari"
        }
        return HikariDataSource(hikariConfig)
    }

    fun migrate(dataSource: HikariDataSource) {
        logger.info { "Running Flyway migrations" }
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
