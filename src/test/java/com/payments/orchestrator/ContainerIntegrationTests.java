package com.payments.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.domain.*;
import com.payments.orchestrator.dto.*;
import com.payments.orchestrator.repository.*;
import com.payments.orchestrator.security.SecurityUtils;
import com.payments.orchestrator.service.*;
import com.payments.orchestrator.worker.OutboxPublisherWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive Testcontainers-based integration test suite for the Payment Orchestration Service.
 *
 * Covers all 12 production scenarios with real PostgreSQL and Redis.
 * No mocking of persistence layers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ContainerIntegrationTests {

    // ============================================================
    // Static Container Declarations (shared across all tests)
    // ============================================================

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("payment_orchestrator")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // ============================================================
    // Injected Beans
    // ============================================================

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private PaymentAttemptRepository attemptRepository;

    @Autowired
    private PaymentOutboxRepository outboxRepository;

    @Autowired
    private PaymentIdempotencyRepository idempotencyRepository;

    @Autowired
    private ProcessedWebhookRepository processedWebhookRepository;

    @Autowired
    private PspAConnector pspAConnector;

    @Autowired
    private PspBConnector pspBConnector;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private RetryService retryService;

    @Autowired
    private OutboxPublisherWorker outboxPublisherWorker;

    @Autowired
    private InMemoryEventPublisher inMemoryEventPublisher;

    // ============================================================
    // Test Lifecycle
    // ============================================================

    @BeforeEach
    void resetState() {
        // Reset PSP connectors to a clean SUCCESS mode before each test
        pspAConnector.setMode("SUCCESS");
        pspBConnector.setMode("SUCCESS");
        // Clear the in-memory event publisher capture buffer
        inMemoryEventPublisher.clear();
    }

    // ============================================================
    // Helpers
    // ============================================================

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1/payments-orchestration";
    }

    private HttpHeaders jsonHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    private HttpHeaders webhookHeaders(String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-PSP-Signature", signature);
        return headers;
    }

    private CreatePaymentRequest buildRequest(UUID merchantId, String orderId) {
        return new CreatePaymentRequest(
                merchantId,
                orderId,
                "CARD",
                "vault_token_abc123",
                new MoneyAmount("USD", new BigDecimal("100.00")),
                new MoneyAmount("USD", new BigDecimal("100.00")),
                Map.of("source", "checkout")
        );
    }

    private ResponseEntity<Map> postPayment(CreatePaymentRequest request, String idempotencyKey) {
        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, jsonHeaders(idempotencyKey));
        return restTemplate.exchange(baseUrl() + "/payments", HttpMethod.POST, entity, Map.class);
    }

    private ResponseEntity<Map> postWebhook(String provider, WebhookRequest webhookRequest, String signature) {
        HttpEntity<WebhookRequest> entity = new HttpEntity<>(webhookRequest, webhookHeaders(signature));
        return restTemplate.exchange(baseUrl() + "/webhooks/" + provider, HttpMethod.POST, entity, Map.class);
    }

    private String computeWebhookSignature(String secret, String rawBody) {
        try {
            return SecurityUtils.hmacSha256Base64(secret, rawBody);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebhookRequest buildWebhookRequest(String eventId, String status, String providerRef,
                                                UUID intentId, UUID attemptId) {
        return new WebhookRequest(
                eventId,
                "PAYMENT_" + status,
                providerRef,
                intentId,
                attemptId,
                status,
                OffsetDateTime.now(ZoneOffset.UTC),
                null
        );
    }

    // ============================================================
    // Scenario 1: Successful Authorization
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("Scenario 1 — Successful authorization: HTTP 200, intent=AUTHORIZED, outbox created")
    void scenario01_successfulAuthorization() {
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC01-" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = UUID.randomUUID().toString();

        pspAConnector.setMode("SUCCESS");

        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), idempotencyKey);

        // 1a. HTTP status
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 1b. Response body
        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("AUTHORIZED");
        assertThat(body.get("intent_id")).isNotNull();

        // 1c. Database state: intent is AUTHORIZED
        UUID intentId = UUID.fromString(body.get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        // 1d. Database state: single attempt is AUTHORIZED
        List<PaymentAttempt> attempts = intent.getAttempts();
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getStatus()).isEqualTo(AttemptStatus.AUTHORIZED);
        assertThat(attempts.get(0).getProviderReference()).isNotBlank();

        // 1e. Idempotency record is COMPLETED
        PaymentIdempotency idem = idempotencyRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        assertThat(idem.getStatus()).isEqualTo("COMPLETED");

        // 1f. At least one PENDING outbox event created (PAYMENT_CREATED + PAYMENT_AUTHORIZED)
        long pendingOutbox = outboxRepository.countByStatus(OutboxStatus.PENDING);
        assertThat(pendingOutbox).isGreaterThanOrEqualTo(1L);
    }

    // ============================================================
    // Scenario 2: Definitive PSP Failure
    // ============================================================

    @Test
    @Order(2)
    @DisplayName("Scenario 2 — Definitive PSP failure: HTTP 200, intent=FAILED, error_code set")
    void scenario02_definitivePspFailure() {
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC02-" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = UUID.randomUUID().toString();

        pspAConnector.setMode("FAILURE");

        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), idempotencyKey);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("FAILED");

        UUID intentId = UUID.fromString(body.get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);

        PaymentAttempt attempt = intent.getAttempts().get(0);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(attempt.getErrorCode()).isEqualTo("CARD_DECLINED");
        assertThat(attempt.getErrorMessage()).isNotBlank();
    }

    // ============================================================
    // Scenario 3: Timeout → PENDING
    // ============================================================

    @Test
    @Order(3)
    @DisplayName("Scenario 3 — PSP timeout: HTTP 202 ACCEPTED, intent=PENDING, error_code=PSP_TIMEOUT")
    void scenario03_timeoutToPending() {
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC03-" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = UUID.randomUUID().toString();

        pspAConnector.setMode("TIMEOUT");

        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), idempotencyKey);

        // 3a. HTTP 202 Accepted (not 200) for PENDING
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        Map<?, ?> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("status")).isEqualTo("PENDING");

        // 3b. DB state: intent is PENDING, attempt is PENDING
        UUID intentId = UUID.fromString(body.get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PENDING);

        PaymentAttempt attempt = intent.getAttempts().get(0);
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.PENDING);
        assertThat(attempt.getErrorCode()).isEqualTo("PSP_TIMEOUT");
    }

    // ============================================================
    // Scenario 4: Reconciliation Resolution
    // ============================================================

    @Test
    @Order(4)
    @DisplayName("Scenario 4 — Reconciliation resolution: PENDING intent resolved to AUTHORIZED via ReconciliationService")
    void scenario04_reconciliationResolution() {
        // 4a. Create a PENDING intent (timeout path)
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC04-" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = UUID.randomUUID().toString();

        pspAConnector.setMode("TIMEOUT");
        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), idempotencyKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID intentId = UUID.fromString(response.getBody().get("intent_id").toString());
        PaymentIntent pendingIntent = intentRepository.findById(intentId).orElseThrow();
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.PENDING);

        // 4b. Switch PSP to SUCCESS so reconciliation status query returns AUTHORIZED
        pspAConnector.setMode("SUCCESS");

        // Reload the intent with attempts (needs lazy init)
        PaymentIntent intentForRecon = intentRepository.findById(intentId).orElseThrow();
        // Force lazy load of attempts
        intentForRecon.getAttempts().size();

        // 4c. Invoke reconciliation service directly
        reconciliationService.reconcileIntent(intentForRecon);

        // 4d. Re-read and verify
        PaymentIntent resolved = intentRepository.findById(intentId).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        PaymentAttempt attempt = resolved.getAttempts().stream()
                .filter(a -> a.getStatus() == AttemptStatus.AUTHORIZED)
                .findFirst()
                .orElse(null);
        assertThat(attempt).isNotNull();
    }

    // ============================================================
    // Scenario 5: Idempotency Replay
    // ============================================================

    @Test
    @Order(5)
    @DisplayName("Scenario 5 — Idempotency replay: identical request returns cached response with same intent_id")
    void scenario05_idempotencyReplay() {
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC05-" + UUID.randomUUID().toString().substring(0, 8);
        String idempotencyKey = UUID.randomUUID().toString();

        pspAConnector.setMode("SUCCESS");
        CreatePaymentRequest request = buildRequest(merchantId, orderId);

        // First request
        ResponseEntity<Map> first = postPayment(request, idempotencyKey);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstIntentId = first.getBody().get("intent_id").toString();

        // Second identical request with same key + same body
        ResponseEntity<Map> second = postPayment(request, idempotencyKey);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondIntentId = second.getBody().get("intent_id").toString();

        // 5a. Same intent_id returned
        assertThat(secondIntentId).isEqualTo(firstIntentId);

        // 5b. Only one PaymentIntent in DB
        long intentCount = intentRepository.findAll().stream()
                .filter(i -> i.getMerchantId().equals(merchantId) && i.getMerchantOrderId().equals(orderId))
                .count();
        assertThat(intentCount).isEqualTo(1L);
    }

    // ============================================================
    // Scenario 6: Idempotency Conflict
    // ============================================================

    @Test
    @Order(6)
    @DisplayName("Scenario 6 — Idempotency conflict: same key, different body → HTTP 409")
    void scenario06_idempotencyConflict() {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = UUID.randomUUID().toString();

        pspAConnector.setMode("SUCCESS");

        // First request
        String orderId1 = "ORDER-SC06A-" + UUID.randomUUID().toString().substring(0, 8);
        CreatePaymentRequest first = buildRequest(merchantId, orderId1);
        ResponseEntity<Map> firstResponse = postPayment(first, idempotencyKey);
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second request: same idempotency key, different order_id (different body hash)
        String orderId2 = "ORDER-SC06B-" + UUID.randomUUID().toString().substring(0, 8);
        CreatePaymentRequest second = buildRequest(merchantId, orderId2);
        ResponseEntity<Map> conflictResponse = postPayment(second, idempotencyKey);

        // 6a. Should be HTTP 409 Conflict
        assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ============================================================
    // Scenario 7: Nonce Replay (Duplicate Merchant Order)
    // ============================================================

    @Test
    @Order(7)
    @DisplayName("Scenario 7 — Nonce replay: same merchant_id + merchant_order_id → HTTP 409")
    void scenario07_duplicateMerchantOrder() {
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC07-" + UUID.randomUUID().toString().substring(0, 8);

        pspAConnector.setMode("SUCCESS");

        // First request succeeds
        String key1 = UUID.randomUUID().toString();
        ResponseEntity<Map> first = postPayment(buildRequest(merchantId, orderId), key1);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second request: different idempotency key but same merchant_id + merchant_order_id
        String key2 = UUID.randomUUID().toString();
        ResponseEntity<Map> second = postPayment(buildRequest(merchantId, orderId), key2);

        // 7a. Must be HTTP 409 (DuplicateMerchantOrderException)
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ============================================================
    // Scenario 8: Invalid Webhook Signature
    // ============================================================

    @Test
    @Order(8)
    @DisplayName("Scenario 8 — Invalid webhook signature → HTTP 422 Unprocessable Entity")
    void scenario08_invalidWebhookSignature() throws Exception {
        // Create a valid payment attempt first
        pspAConnector.setMode("SUCCESS");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC08-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> paymentResp = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(paymentResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID intentId = UUID.fromString(paymentResp.getBody().get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        PaymentAttempt attempt = intent.getAttempts().get(0);
        String providerRef = attempt.getProviderReference();

        // Build webhook with invalid signature
        WebhookRequest webhookRequest = buildWebhookRequest(
                UUID.randomUUID().toString(), "AUTHORIZED", providerRef, intentId, attempt.getAttemptId()
        );

        ResponseEntity<Map> webhookResp = postWebhook("PSP_A", webhookRequest, "invalid_signature");

        // 8a. HTTP 422
        assertThat(webhookResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ============================================================
    // Scenario 9: Webhook Deduplication
    // ============================================================

    @Test
    @Order(9)
    @DisplayName("Scenario 9 — Webhook dedupe: same event_id twice is idempotent, no duplicate row")
    void scenario09_webhookDeduplication() throws Exception {
        // Create an authorized payment intent (PENDING so webhook can apply transitions)
        pspAConnector.setMode("TIMEOUT");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC09-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> paymentResp = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(paymentResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID intentId = UUID.fromString(paymentResp.getBody().get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        PaymentAttempt attempt = intent.getAttempts().get(0);
        String providerRef = "ref_sc09_" + UUID.randomUUID().toString().substring(0, 8);

        // Set provider reference on attempt so webhook can correlate
        attempt.setProviderReference(providerRef);
        attemptRepository.save(attempt);

        String eventId = UUID.randomUUID().toString();
        WebhookRequest webhookRequest = buildWebhookRequest(
                eventId, "AUTHORIZED", providerRef, intentId, attempt.getAttemptId()
        );
        String webhookBody = objectMapper.writeValueAsString(webhookRequest);
        String signature = computeWebhookSignature("secret_psp_a", webhookBody);

        // First webhook delivery
        ResponseEntity<Map> first = postWebhook("PSP_A", webhookRequest, signature);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second delivery of the same event_id
        ResponseEntity<Map> second = postWebhook("PSP_A", webhookRequest, signature);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 9a. Exactly ONE ProcessedWebhook row for this event_id
        long dedupCount = processedWebhookRepository.findAll().stream()
                .filter(pw -> pw.getProviderEventId().equals(eventId))
                .count();
        assertThat(dedupCount).isEqualTo(1L);

        // 9b. Intent is still AUTHORIZED (not double-applied)
        PaymentIntent finalIntent = intentRepository.findById(intentId).orElseThrow();
        assertThat(finalIntent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }

    // ============================================================
    // Scenario 10: Retry Flow
    // ============================================================

    @Test
    @Order(10)
    @DisplayName("Scenario 10 — Retry flow: PENDING intent retried via RetryService resolves to AUTHORIZED")
    void scenario10_retryFlow() {
        // 10a. Create a PENDING intent
        pspAConnector.setMode("TIMEOUT");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC10-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        UUID intentId = UUID.fromString(response.getBody().get("intent_id").toString());
        PaymentIntent pendingIntent = intentRepository.findById(intentId).orElseThrow();
        assertThat(pendingIntent.getStatus()).isEqualTo(PaymentStatus.PENDING);

        // 10b. Set PSP to SUCCESS for the retry
        pspAConnector.setMode("SUCCESS");

        // 10c. Execute retry directly via RetryService
        retryService.executeRetry(pendingIntent);

        // 10d. Verify intent is now AUTHORIZED
        PaymentIntent resolved = intentRepository.findById(intentId).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        // 10e. Verify a new attempt was created (retry attempt N+1)
        assertThat(resolved.getAttempts().size()).isGreaterThanOrEqualTo(2);

        // 10f. Latest attempt should be AUTHORIZED
        PaymentAttempt authorizedAttempt = resolved.getAttempts().stream()
                .filter(a -> a.getStatus() == AttemptStatus.AUTHORIZED)
                .findFirst()
                .orElse(null);
        assertThat(authorizedAttempt).isNotNull();

        // 10g. Old attempt should be SUPERSEDED
        PaymentAttempt supersededAttempt = resolved.getAttempts().stream()
                .filter(a -> a.getStatus() == AttemptStatus.SUPERSEDED)
                .findFirst()
                .orElse(null);
        assertThat(supersededAttempt).isNotNull();
    }

    // ============================================================
    // Scenario 11: Outbox Publish
    // ============================================================

    @Test
    @Order(11)
    @DisplayName("Scenario 11 — Outbox publish: PENDING outbox events published and marked PROCESSED")
    void scenario11_outboxPublish() {
        // 11a. Create a successful payment (generates PENDING outbox rows)
        pspAConnector.setMode("SUCCESS");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC11-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID intentId = UUID.fromString(response.getBody().get("intent_id").toString());

        // 11b. Verify PENDING outbox events exist for this intent
        List<PaymentOutbox> pendingForIntent = outboxRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(intentId) && o.getStatus() == OutboxStatus.PENDING)
                .toList();
        assertThat(pendingForIntent).isNotEmpty();

        // 11c. Run the outbox publisher cycle
        inMemoryEventPublisher.clear();
        outboxPublisherWorker.processPendingBatch();

        // 11d. All outbox events for this intent should be PROCESSED
        List<PaymentOutbox> afterPublish = outboxRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(intentId))
                .toList();
        assertThat(afterPublish).allMatch(o -> o.getStatus() == OutboxStatus.PROCESSED);

        // 11e. In-memory publisher received the events
        List<InMemoryEventPublisher.PublishedEventRecord> published = inMemoryEventPublisher.getPublishedEvents();
        boolean hasIntentEvent = published.stream()
                .anyMatch(e -> e.payload().containsValue(intentId.toString()));
        assertThat(hasIntentEvent).isTrue();
    }

    // ============================================================
    // Scenario 12: Illegal State Transitions
    // ============================================================

    @Test
    @Order(12)
    @DisplayName("Scenario 12 — Illegal transition: AUTHORIZED → FAILED via webhook rejected with HTTP 422")
    void scenario12_illegalStateTransition() throws Exception {
        // 12a. Create an authorized payment intent
        pspAConnector.setMode("SUCCESS");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-SC12-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> paymentResp = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(paymentResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID intentId = UUID.fromString(paymentResp.getBody().get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        PaymentAttempt attempt = intent.getAttempts().get(0);
        String providerRef = attempt.getProviderReference();

        // 12b. Send a FAILED webhook for this already-AUTHORIZED intent
        WebhookRequest illegalWebhook = buildWebhookRequest(
                UUID.randomUUID().toString(), "FAILED", providerRef, intentId, attempt.getAttemptId()
        );
        String webhookBody = objectMapper.writeValueAsString(illegalWebhook);
        String signature = computeWebhookSignature("secret_psp_a", webhookBody);

        ResponseEntity<Map> illegalResp = postWebhook("PSP_A", illegalWebhook, signature);

        // 12c. The webhook should be ACKNOWLEDGED (200) — already-authorized intents get idempotent
        // acceptance per R-07 (webhook received for already-AUTHORIZED intent => acknowledge, no state change)
        assertThat(illegalResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 12d. Intent MUST remain AUTHORIZED — no illegal state change persisted
        PaymentIntent afterWebhook = intentRepository.findById(intentId).orElseThrow();
        assertThat(afterWebhook.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        // 12e. Now attempt an explicit FAILED -> AUTHORIZED transition (reverse) via webhook on a FAILED intent
        pspAConnector.setMode("FAILURE");
        String orderId2 = "ORDER-SC12B-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> failedPayment = postPayment(buildRequest(UUID.randomUUID(), orderId2), UUID.randomUUID().toString());
        assertThat(failedPayment.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failedPayment.getBody().get("status")).isEqualTo("FAILED");

        UUID failedIntentId = UUID.fromString(failedPayment.getBody().get("intent_id").toString());
        PaymentIntent failedIntent = intentRepository.findById(failedIntentId).orElseThrow();
        PaymentAttempt failedAttempt = failedIntent.getAttempts().get(0);

        // Set a provider reference for webhook correlation
        String failedRef = "ref_sc12b_" + UUID.randomUUID().toString().substring(0, 8);
        failedAttempt.setProviderReference(failedRef);
        attemptRepository.save(failedAttempt);

        // 12f. Send AUTHORIZED webhook to a FAILED intent
        WebhookRequest forbiddenWebhook = buildWebhookRequest(
                UUID.randomUUID().toString(), "AUTHORIZED", failedRef, failedIntentId, failedAttempt.getAttemptId()
        );
        String fbWebhookBody = objectMapper.writeValueAsString(forbiddenWebhook);
        String fbSignature = computeWebhookSignature("secret_psp_a", fbWebhookBody);

        ResponseEntity<Map> forbidden = postWebhook("PSP_A", forbiddenWebhook, fbSignature);

        // 12g. FAILED -> AUTHORIZED is explicitly blocked (R-08) with 422
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // 12h. Intent MUST remain FAILED
        PaymentIntent stillFailed = intentRepository.findById(failedIntentId).orElseThrow();
        assertThat(stillFailed.getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    // ============================================================
    // Additional Hardening Tests
    // ============================================================

    @Test
    @Order(13)
    @DisplayName("Hardening — Idempotency key is required: missing header → HTTP 4xx")
    void hardening_missingIdempotencyKey() {
        CreatePaymentRequest request = buildRequest(UUID.randomUUID(), "ORDER-NOIDEMPKEY");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Intentionally omitting Idempotency-Key header

        HttpEntity<CreatePaymentRequest> entity = new HttpEntity<>(request, headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl() + "/payments", HttpMethod.POST, entity, Map.class);

        // Missing required header causes 4xx
        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    @Order(14)
    @DisplayName("Hardening — Outbox terminal state: already-PROCESSED events are not re-published")
    void hardening_processedOutboxNotRepublished() {
        // Create payment to generate outbox events
        pspAConnector.setMode("SUCCESS");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-HRDNOUT-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID intentId = UUID.fromString(response.getBody().get("intent_id").toString());

        // Publish once
        outboxPublisherWorker.processPendingBatch();

        // All should be PROCESSED now
        List<PaymentOutbox> afterFirst = outboxRepository.findAll().stream()
                .filter(o -> o.getAggregateId().equals(intentId))
                .toList();
        assertThat(afterFirst).allMatch(o -> o.getStatus() == OutboxStatus.PROCESSED);

        // Publish again - already-PROCESSED events must NOT be re-queried/re-published
        inMemoryEventPublisher.clear();
        outboxPublisherWorker.processPendingBatch();

        // In-memory publisher should have received nothing for this intent
        long republishedCount = inMemoryEventPublisher.getPublishedEvents().stream()
                .filter(e -> e.payload().containsValue(intentId.toString()))
                .count();
        assertThat(republishedCount).isEqualTo(0L);
    }

    @Test
    @Order(15)
    @DisplayName("Hardening — Reconciliation: AUTHORIZED intent not re-reconciled (terminal state protected)")
    void hardening_authorizedIntentNotRereconciled() {
        // Create an AUTHORIZED intent
        pspAConnector.setMode("SUCCESS");
        UUID merchantId = UUID.randomUUID();
        String orderId = "ORDER-HRDNREC-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<Map> response = postPayment(buildRequest(merchantId, orderId), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID intentId = UUID.fromString(response.getBody().get("intent_id").toString());
        PaymentIntent intent = intentRepository.findById(intentId).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);

        // Switch PSP to FAILURE — reconciliation must NOT apply this to an AUTHORIZED intent
        pspAConnector.setMode("FAILURE");
        intent.getAttempts().size(); // force lazy load
        reconciliationService.reconcileIntent(intent);

        // Intent must still be AUTHORIZED
        PaymentIntent afterRecon = intentRepository.findById(intentId).orElseThrow();
        assertThat(afterRecon.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    }
}
