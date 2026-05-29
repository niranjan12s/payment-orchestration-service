package com.payments.orchestrator.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Properties;

/**
 * Custom health indicator for Redis.
 * Checks connectivity via PING and reports server info (version, mode, uptime).
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthIndicator.class);

    private final RedisConnectionFactory redisConnectionFactory;

    public RedisHealthIndicator(RedisConnectionFactory redisConnectionFactory) {
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @Override
    public Health health() {
        long start = System.currentTimeMillis();
        try (var connection = redisConnectionFactory.getConnection()) {
            // PING -> "PONG"
            String pingResponse = connection.ping();
            long latencyMs = System.currentTimeMillis() - start;

            if (!"PONG".equalsIgnoreCase(pingResponse)) {
                return Health.down()
                        .withDetail("reason", "Unexpected PING response: " + pingResponse)
                        .build();
            }

            // Fetch server info for enriched detail
            RedisServerCommands serverCommands = connection.serverCommands();
            Properties serverInfo = serverCommands.info("server");
            String version = serverInfo != null ? serverInfo.getProperty("redis_version", "unknown") : "unknown";
            String mode = serverInfo != null ? serverInfo.getProperty("redis_mode", "unknown") : "unknown";
            String uptimeSecs = serverInfo != null ? serverInfo.getProperty("uptime_in_seconds", "unknown") : "unknown";

            return Health.up()
                    .withDetail("ping", "PONG")
                    .withDetail("redisVersion", version)
                    .withDetail("mode", mode)
                    .withDetail("uptimeSeconds", uptimeSecs)
                    .withDetail("responseTimeMs", latencyMs)
                    .withDetail("checkedAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
                    .build();

        } catch (Exception redisProbeFailure) {
            long latencyMs = System.currentTimeMillis() - start;
            log.error("Redis health check failed: {}", redisProbeFailure.getMessage(), redisProbeFailure);
            return Health.down(redisProbeFailure)
                    .withDetail("responseTimeMs", latencyMs)
                    .withDetail("error", redisProbeFailure.getMessage())
                    .build();
        }
    }
}
