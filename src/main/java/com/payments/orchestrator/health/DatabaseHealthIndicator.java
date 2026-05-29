package com.payments.orchestrator.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Custom health indicator for the database connection.
 * Executes a lightweight validation query and reports response latency.
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthIndicator.class);

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            long latencyMs = System.currentTimeMillis() - start;

            if (result != null && result == 1) {
                return Health.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("validationQuery", "SELECT 1")
                        .withDetail("responseTimeMs", latencyMs)
                        .withDetail("checkedAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
                        .build();
            } else {
                log.warn("Database health check returned unexpected result: {}", result);
                return Health.down()
                        .withDetail("reason", "Unexpected validation query result")
                        .withDetail("responseTimeMs", latencyMs)
                        .build();
            }
        } catch (Exception databaseProbeFailure) {
            long latencyMs = System.currentTimeMillis() - start;
            log.error("Database health check failed: {}", databaseProbeFailure.getMessage(), databaseProbeFailure);
            return Health.down(databaseProbeFailure)
                    .withDetail("database", "PostgreSQL")
                    .withDetail("responseTimeMs", latencyMs)
                    .withDetail("error", databaseProbeFailure.getMessage())
                    .build();
        }
    }
}
