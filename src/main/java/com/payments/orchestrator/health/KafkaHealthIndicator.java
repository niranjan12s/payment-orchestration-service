package com.payments.orchestrator.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KafkaHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        // Since Kafka publishing is stubbed/simulated in this environment,
        // we provide a healthy connection status and list topics.
        return Health.up()
                .withDetail("broker", "simulated-cluster")
                .withDetail("status", "CONNECTED")
                .withDetail("topics", List.of("payment-events", "payment-outbox"))
                .build();
    }
}
