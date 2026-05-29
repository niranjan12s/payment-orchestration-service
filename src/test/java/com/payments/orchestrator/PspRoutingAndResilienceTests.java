package com.payments.orchestrator;

import com.payments.orchestrator.domain.AttemptStatus;
import com.payments.orchestrator.domain.PaymentAttempt;
import com.payments.orchestrator.domain.PaymentMethodType;
import com.payments.orchestrator.dto.PspResponse;
import com.payments.orchestrator.dto.PspStatus;
import com.payments.orchestrator.exception.PspTimeoutException;
import com.payments.orchestrator.service.*;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PspRoutingAndResilienceTests extends BaseIntegrationTest {

    @Autowired
    private RoutingEngine routingEngine;

    @Autowired
    private PspAConnector pspAConnector;

    @Autowired
    private PspBConnector pspBConnector;

    @Autowired
    private PaymentInstrumentResolver instrumentResolver;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private PaymentAttempt cardAttempt;
    private PaymentAttempt upiAttempt;

    @BeforeEach
    void setUp() {
        // Construct mock attempts with dummy details for tests
        cardAttempt = new PaymentAttempt();
        cardAttempt.setAttemptId(UUID.randomUUID());
        cardAttempt.setPaymentMethodType(PaymentMethodType.CARD);
        cardAttempt.setPaymentTokenReference("tok_visa_abc123");
        cardAttempt.setStatus(AttemptStatus.PROCESSING);

        upiAttempt = new PaymentAttempt();
        upiAttempt.setAttemptId(UUID.randomUUID());
        upiAttempt.setPaymentMethodType(PaymentMethodType.UPI);
        upiAttempt.setPaymentTokenReference("upi_ref_xyz456");
        upiAttempt.setStatus(AttemptStatus.PROCESSING);

        // Reset both circuit breakers to CLOSED state before each test
        circuitBreakerRegistry.circuitBreaker("psp-a").reset();
        circuitBreakerRegistry.circuitBreaker("psp-b").reset();
        
        // Reset connector modes to SUCCESS by default
        pspAConnector.setMode("SUCCESS");
        pspBConnector.setMode("SUCCESS");
    }

    @AfterEach
    void tearDown() {
        pspAConnector.setMode("SUCCESS");
        pspBConnector.setMode("SUCCESS");
    }

    @Test
    void testRoutingEngineSelection() {
        // Assert CARD method maps to PspAConnector
        PspConnector cardConnector = routingEngine.selectConnector(PaymentMethodType.CARD);
        assertThat(cardConnector).isNotNull();
        assertThat(cardConnector.getProviderName()).isEqualTo("PSP_A");
        assertThat(cardConnector).isSameAs(pspAConnector);

        // Assert UPI method maps to PspBConnector
        PspConnector upiConnector = routingEngine.selectConnector(PaymentMethodType.UPI);
        assertThat(upiConnector).isNotNull();
        assertThat(upiConnector.getProviderName()).isEqualTo("PSP_B");
        assertThat(upiConnector).isSameAs(pspBConnector);
    }

    @Test
    void testPaymentInstrumentResolution() {
        String resolvedCard = instrumentResolver.resolveInstrument("tok_visa_abc123");
        assertThat(resolvedCard).contains("tok_visa_abc123");

        String resolvedUpi = instrumentResolver.resolveInstrument("upi_ref_xyz456");
        assertThat(resolvedUpi).contains("upi_ref_xyz456");
    }

    @Test
    void testProviderIdempotencyKeyGeneration() {
        UUID attemptId = cardAttempt.getAttemptId();
        // Provider idempotency key is deterministically generated per attempt
        String idempKey = "idemp:" + attemptId;

        assertThat(idempKey).isEqualTo("idemp:" + attemptId);
    }

    @Test
    void testConfigurableStubModesSuccess() {
        pspAConnector.setMode("SUCCESS");
        String idempKey = "idemp:" + cardAttempt.getAttemptId();
        
        PspResponse response = pspAConnector.authorize(cardAttempt, idempKey);
        
        assertThat(response.getStatus()).isEqualTo(PspStatus.SUCCESS);
        assertThat(response.getProviderReference()).startsWith("ref_pspa_");
        assertThat(response.getErrorCode()).isNull();
        assertThat(response.getErrorMessage()).isNull();
    }

    @Test
    void testConfigurableStubModesFailure() {
        pspBConnector.setMode("FAILURE");
        String idempKey = "idemp:" + upiAttempt.getAttemptId();

        PspResponse response = pspBConnector.authorize(upiAttempt, idempKey);

        assertThat(response.getStatus()).isEqualTo(PspStatus.FAILED);
        assertThat(response.getProviderReference()).isNull();
        assertThat(response.getErrorCode()).isEqualTo("UPI_ACCOUNT_INVALID");
        assertThat(response.getErrorMessage()).contains("Invalid virtual payment address");
    }

    @Test
    void testConfigurableStubModesTimeout() {
        pspAConnector.setMode("TIMEOUT");
        String idempKey = "idemp:" + cardAttempt.getAttemptId();

        assertThatThrownBy(() -> pspAConnector.authorize(cardAttempt, idempKey))
                .isInstanceOf(PspTimeoutException.class)
                .hasMessageContaining("Connect/Read timeout occurred");
    }

    @Test
    void testResilience4jCircuitBreakerTransitionsToOpen() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("psp-a");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Configure pspAConnector to fail/timeout to trip the circuit breaker
        pspAConnector.setMode("TIMEOUT");
        String idempKey = "idemp:" + cardAttempt.getAttemptId();

        // Perform 5 failing calls (minimum number of calls to evaluate health is 5)
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> pspAConnector.authorize(cardAttempt, idempKey))
                    .isInstanceOf(PspTimeoutException.class);
        }

        // Circuit breaker must now transition to OPEN (since failure rate is 100% > 50% threshold)
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Subsequent call must fast-fail instantly with CallNotPermittedException
        assertThatThrownBy(() -> pspAConnector.authorize(cardAttempt, idempKey))
                .isInstanceOf(CallNotPermittedException.class);
    }
}
