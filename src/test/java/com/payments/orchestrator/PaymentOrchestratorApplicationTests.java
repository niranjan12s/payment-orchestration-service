package com.payments.orchestrator;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentOrchestratorApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // Smoke test to verify Spring Application Context starts up correctly
        assertThat(redis.isRunning()).isTrue();
    }
}
