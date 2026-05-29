package com.payments.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.domain.PaymentIdempotency;
import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.IdempotencyResult;
import com.payments.orchestrator.dto.MoneyAmount;
import com.payments.orchestrator.dto.PaymentAuthorizedResponse;
import com.payments.orchestrator.exception.IdempotencyConflictException;
import com.payments.orchestrator.repository.PaymentIdempotencyRepository;
import com.payments.orchestrator.security.SecurityUtils;
import com.payments.orchestrator.service.IdempotencyServiceImpl;
import com.payments.orchestrator.worker.IdempotencyPruningScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTests {

    @Mock
    private PaymentIdempotencyRepository repository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private IdempotencyServiceImpl idempotencyService;

    @InjectMocks
    private IdempotencyPruningScheduler pruningScheduler;

    private String idempotencyKey;
    private String rawBody;
    private String canonicalBody;
    private String expectedHash;

    @BeforeEach
    void setUp() throws Exception {
        idempotencyKey = UUID.randomUUID().toString();
        // Unsorted JSON body
        rawBody = "{\"b\":200,\"a\":\"test_value\"}";
        canonicalBody = "{\"a\":\"test_value\",\"b\":200}";
        expectedHash = SecurityUtils.sha256Hex(canonicalBody);
    }

    @Test
    void testRecursiveJsonSortingProducesIdenticalHashes() throws Exception {
        String body1 = "{\"merchant_id\":\"550e8400-e29b-41d4-a716-446655440000\",\"amount\":100}";
        String body2 = "{\"amount\":100,\"merchant_id\":\"550e8400-e29b-41d4-a716-446655440000\"}";

        String hash1 = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body1));
        String hash2 = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body2));

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void testCheckIdempotencyFirstRequestClaim() {
        when(repository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

        IdempotencyResult result = idempotencyService.checkIdempotency(idempotencyKey, rawBody);

        assertThat(result.getStatus()).isEqualTo(IdempotencyResult.Status.ABSENT);
        assertThat(result.getRequestHash()).isEqualTo(expectedHash);

        ArgumentCaptor<PaymentIdempotency> captor = ArgumentCaptor.forClass(PaymentIdempotency.class);
        verify(repository, times(1)).save(captor.capture());

        PaymentIdempotency saved = captor.getValue();
        assertThat(saved.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(saved.getRequestHash()).isEqualTo(expectedHash);
        assertThat(saved.getStatus()).isEqualTo("PROCESSING");
    }

    @Test
    void testCheckIdempotencyDuplicateRequestMatch() throws Exception {
        UUID intentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);

        // 1. Create a cached CreatePaymentResponse map matching the entity column
        PaymentAuthorizedResponse authResponse = new PaymentAuthorizedResponse(
                intentId,
                attemptId,
                "ORDER-OK",
                "PSP_A",
                "provider_tx_ok",
                new MoneyAmount("USD", new BigDecimal("100.00")),
                new MoneyAmount("INR", new BigDecimal("8300.00")),
                timestamp
        );
        CreatePaymentResponse createResponse = CreatePaymentResponse.authorized(authResponse);

        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = objectMapper.convertValue(createResponse, Map.class);

        // 2. Setup mock entity in DB representing completed idempotency
        PaymentIdempotency existing = new PaymentIdempotency();
        existing.setIdempotencyKey(idempotencyKey);
        existing.setRequestHash(expectedHash);
        existing.setStatus("COMPLETED");
        existing.setResponsePayload(payloadMap);
        existing.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));

        when(repository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        // 3. Trigger idempotency check
        IdempotencyResult result = idempotencyService.checkIdempotency(idempotencyKey, rawBody);

        assertThat(result.getStatus()).isEqualTo(IdempotencyResult.Status.MATCH);
        assertThat(result.isMatch()).isTrue();
        
        CreatePaymentResponse cached = result.getCachedResponse();
        assertThat(cached.getIntentId()).isEqualTo(intentId);
        assertThat(cached.getAttemptId()).isEqualTo(attemptId);
        assertThat(cached.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(cached.getProviderReference()).isEqualTo("provider_tx_ok");
    }

    @Test
    void testCheckIdempotencyConflictPayloadMismatch() {
        PaymentIdempotency existing = new PaymentIdempotency();
        existing.setIdempotencyKey(idempotencyKey);
        existing.setRequestHash("different_hash_value"); // payload mismatch
        existing.setStatus("COMPLETED");
        existing.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));

        when(repository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.checkIdempotency(idempotencyKey, rawBody))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request payload");
    }

    @Test
    void testCheckIdempotencyInFlightDuplicateRejection() {
        PaymentIdempotency existing = new PaymentIdempotency();
        existing.setIdempotencyKey(idempotencyKey);
        existing.setRequestHash(expectedHash);
        existing.setStatus("PROCESSING"); // In flight
        existing.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(2));

        when(repository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> idempotencyService.checkIdempotency(idempotencyKey, rawBody))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already being processed");
    }

    @Test
    void testCheckIdempotencyExpiredKeyReclaimed() {
        PaymentIdempotency expired = new PaymentIdempotency();
        expired.setIdempotencyKey(idempotencyKey);
        expired.setRequestHash(expectedHash);
        expired.setStatus("COMPLETED");
        // Expired 1 hour ago
        expired.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        when(repository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(expired));

        IdempotencyResult result = idempotencyService.checkIdempotency(idempotencyKey, rawBody);

        // Should delete expired record and save a fresh PROCESSING key claim
        verify(repository, times(1)).delete(expired);
        assertThat(result.getStatus()).isEqualTo(IdempotencyResult.Status.ABSENT);
    }

    @Test
    void testIdempotencyPruningSchedulerExecutesBulkPurge() {
        when(repository.deleteExpiredKeys(any(OffsetDateTime.class))).thenReturn(15);

        pruningScheduler.pruneExpiredIdempotencyKeys();

        verify(repository, times(1)).deleteExpiredKeys(any(OffsetDateTime.class));
    }
}
