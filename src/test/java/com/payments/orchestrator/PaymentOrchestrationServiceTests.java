package com.payments.orchestrator;

import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.dto.MoneyAmount;
import com.payments.orchestrator.exception.DuplicateMerchantOrderException;
import com.payments.orchestrator.repository.PaymentEventRepository;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.repository.PaymentOutboxRepository;
import com.payments.orchestrator.service.PaymentOrchestrationServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOrchestrationServiceTests {

    @Mock
    private PaymentIntentRepository intentRepository;

    @Mock
    private PaymentEventRepository eventRepository;

    @Mock
    private PaymentOutboxRepository outboxRepository;

    @InjectMocks
    private PaymentOrchestrationServiceImpl orchestrationService;

    private CreatePaymentRequest cardRequest;
    private CreatePaymentRequest upiRequest;
    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        idempotencyKey = UUID.randomUUID().toString();

        cardRequest = new CreatePaymentRequest(
                UUID.randomUUID(),
                "ORDER-CARD-123",
                "CARD",
                "vault_token_visa",
                new MoneyAmount("USD", new BigDecimal("100.00")),
                new MoneyAmount("INR", new BigDecimal("8300.00")),
                null
        );

        upiRequest = new CreatePaymentRequest(
                UUID.randomUUID(),
                "ORDER-UPI-456",
                "UPI",
                "upi_reference_identifier",
                new MoneyAmount("INR", new BigDecimal("500.00")),
                new MoneyAmount("INR", new BigDecimal("500.00")),
                null
        );

        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void testCreateInitialPaymentStateCardSuccess() {
        // Setup mock: order doesn't exist
        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());

        // Setup mock returns on saves
        when(intentRepository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentIntent result = orchestrationService.createInitialPaymentState(cardRequest, idempotencyKey);

        assertThat(result).isNotNull();
        assertThat(result.getMerchantOrderId()).isEqualTo("ORDER-CARD-123");
        assertThat(result.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(result.getTransactionCurrencyCode()).isEqualTo("USD");
        assertThat(result.getTransactionAmount()).isEqualTo(new BigDecimal("100.00"));
        assertThat(result.getSettlementCurrencyCode()).isEqualTo("INR");
        assertThat(result.getSettlementAmount()).isEqualTo(new BigDecimal("8300.00"));
        assertThat(result.getIdempotencyKey()).isEqualTo(idempotencyKey);

        // Verify attempt is nested bidirectionally and routed correctly to PSP_A
        assertThat(result.getAttempts()).hasSize(1);
        PaymentAttempt attempt = result.getAttempts().get(0);
        assertThat(attempt.getProviderName()).isEqualTo("PSP_A");
        assertThat(attempt.getPaymentMethodType()).isEqualTo(PaymentMethodType.CARD);
        assertThat(attempt.getPaymentTokenReference()).isEqualTo("vault_token_visa");
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.PROCESSING);
        assertThat(result.getActiveAttemptId()).isEqualTo(attempt.getAttemptId());

        // Verify saves on all repository layers
        verify(intentRepository, times(2)).saveAndFlush(any(PaymentIntent.class));
        verify(eventRepository, times(1)).save(any(PaymentEvent.class));
        verify(outboxRepository, times(1)).save(any(PaymentOutbox.class));
    }

    @Test
    void testCreateInitialPaymentStateUpiSuccess() {
        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());
        when(intentRepository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentIntent result = orchestrationService.createInitialPaymentState(upiRequest, idempotencyKey);

        assertThat(result).isNotNull();
        assertThat(result.getAttempts()).hasSize(1);
        PaymentAttempt attempt = result.getAttempts().get(0);
        // Verify routed correctly to PSP_B
        assertThat(attempt.getProviderName()).isEqualTo("PSP_B");
        assertThat(attempt.getPaymentMethodType()).isEqualTo(PaymentMethodType.UPI);
    }

    @Test
    void testCreateInitialPaymentStateDuplicateMerchantOrderConflict() {
        PaymentIntent existingIntent = new PaymentIntent();
        existingIntent.setIntentId(UUID.randomUUID());
        existingIntent.setMerchantOrderId("ORDER-CARD-123");

        // Mock that order already exists in database
        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.of(existingIntent));

        assertThatThrownBy(() -> orchestrationService.createInitialPaymentState(cardRequest, idempotencyKey))
                .isInstanceOf(DuplicateMerchantOrderException.class)
                .hasMessageContaining("Duplicate merchant order");

        // Verify no saves are executed
        verify(intentRepository, never()).saveAndFlush(any(PaymentIntent.class));
        verify(eventRepository, never()).save(any(PaymentEvent.class));
        verify(outboxRepository, never()).save(any(PaymentOutbox.class));
    }

    @Test
    void testCreateInitialPaymentStateInvalidMethodType() {
        CreatePaymentRequest badRequest = new CreatePaymentRequest(
                UUID.randomUUID(),
                "ORDER-BAD-999",
                "CRYPTO", // Unsupported
                "token",
                new MoneyAmount("USD", new BigDecimal("1.00")),
                new MoneyAmount("USD", new BigDecimal("1.00")),
                null
        );

        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrationService.createInitialPaymentState(badRequest, idempotencyKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported payment method type");

        verify(intentRepository, never()).saveAndFlush(any(PaymentIntent.class));
    }

    @Test
    void testTraceContextPersistenceFromMdc() {
        // Set MDC values simulating standard SecurityFilter trace context injection
        String expectedRequestId = "req-abc-789";
        String expectedCorrelationId = "corr-xyz-123";
        MDC.put("request_id", expectedRequestId);
        MDC.put("correlation_id", expectedCorrelationId);

        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());
        when(intentRepository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentIntent result = orchestrationService.createInitialPaymentState(cardRequest, idempotencyKey);

        // Verify entities capture the trace IDs from MDC context
        assertThat(result.getRequestId()).isEqualTo(expectedRequestId);
        assertThat(result.getCorrelationId()).isEqualTo(expectedCorrelationId);

        PaymentAttempt attempt = result.getAttempts().get(0);
        assertThat(attempt.getRequestId()).isEqualTo(expectedRequestId);
        assertThat(attempt.getCorrelationId()).isEqualTo(expectedCorrelationId);

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelationId);

        ArgumentCaptor<PaymentOutbox> outboxCaptor = ArgumentCaptor.forClass(PaymentOutbox.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getCorrelationId()).isEqualTo(expectedCorrelationId);
    }

    @Test
    void testFallbackTelemetryGeneration() {
        // With clear MDC context
        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());
        when(intentRepository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaymentIntent result = orchestrationService.createInitialPaymentState(cardRequest, idempotencyKey);

        // Fallbacks must be generated automatically
        assertThat(result.getRequestId()).startsWith("sys_");
        assertThat(result.getCorrelationId()).startsWith("corr_");
    }

    @Test
    void testTransactionalRollbackConfigurationAndExceptionPropagation() throws NoSuchMethodException {
        // Assert that the transactional boundary exists on the service class/method level
        Method method = PaymentOrchestrationServiceImpl.class.getMethod("createInitialPaymentState", CreatePaymentRequest.class, String.class);
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();

        // Verify exception propagation during transactional failure (simulating DB constraint rollback)
        when(intentRepository.findByMerchantIdAndMerchantOrderId(any(UUID.class), anyString()))
                .thenReturn(Optional.empty());
        
        // Mock a runtime database constraint error on event save
        when(intentRepository.saveAndFlush(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new RuntimeException("Database error saving event")).when(eventRepository).save(any(PaymentEvent.class));

        assertThatThrownBy(() -> orchestrationService.createInitialPaymentState(cardRequest, idempotencyKey))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database error saving event");
    }
}
