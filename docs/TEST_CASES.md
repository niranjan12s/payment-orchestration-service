# TEST_CASES.md — Payment Orchestration Service

Comprehensive test taxonomy for the Payment Orchestration Service.  
Each test case references the implementation path and specific assertions exercised.

---

## How to Run Tests

### Unit Tests (no Docker required)

```powershell
# Windows — using project-bundled toolchain
powershell -ExecutionPolicy Bypass -File .\build.ps1 test -DexcludedGroups=integration

# Run a specific test class
powershell -ExecutionPolicy Bypass -File .\build.ps1 "test -Dtest=ObservabilityHardeningTests"
```

### Integration Tests (requires Docker Desktop running)

```powershell
# Run Testcontainers integration suite
powershell -ExecutionPolicy Bypass -File .\build.ps1 "test -Dtest=ContainerIntegrationTests"

# Run all tests (unit + integration)
powershell -ExecutionPolicy Bypass -File .\build.ps1 test
```

> **Note:** `ContainerIntegrationTests` starts real PostgreSQL 15 and Redis 7 containers via Testcontainers.  
> Docker Desktop must be running. First run downloads container images (~200 MB total).

---

## Test Class Index

| Class | Category | Tests | Description |
|-------|----------|-------|-------------|
| `ContainerIntegrationTests` | Integration | 15 | Full end-to-end scenarios with real Postgres + Redis |
| `ObservabilityHardeningTests` | Unit | 12 | Health indicators, MDC propagation, metrics |
| `WebhookIngestionTests` | Unit | 4+ | Signature, dedup, state transitions, replay |
| `PaymentOrchestrationFlowTests` | Unit | 10+ | Core authorization flow, PSP responses |
| `ReconciliationWorkerTests` | Unit | 8+ | PENDING resolution, escalation, terminal protection |
| `RetryWorkerTests` | Unit | 8+ | Backoff, max attempts, DLQ, activeAttemptId |
| `SecurityValidationTests` | Unit | 10+ | HMAC, nonce, timestamp, signature |
| `IdempotencyServiceTests` | Unit | 6+ | Cache hit, conflict, expiry, hash |
| `PaymentLifecycleValidatorTests` | Unit | 10+ | All state transitions, illegal transitions |
| `RoutingEngineTests` | Unit | 4+ | CARD→PSP_A, UPI→PSP_B, circuit breaker |

---

## 1. Sanity Tests (S-XX) — Core Happy Path

| ID | Scenario | PSP Mode | Expected HTTP | Expected Intent Status | Expected Attempt Status | Notes |
|----|----------|----------|---------------|------------------------|-------------------------|-------|
| S-01 | CARD payment, valid headers, all fields correct | SUCCESS | 200 | AUTHORIZED | AUTHORIZED | provider_reference populated |
| S-02 | UPI payment, valid headers | SUCCESS | 200 | AUTHORIZED | AUTHORIZED | Routed to PSP_B |
| S-03 | PSP read timeout | TIMEOUT | 202 ACCEPTED | PENDING | PENDING | error_code=PSP_TIMEOUT |
| S-04 | Definitive PSP decline | FAILURE | 200 | FAILED | FAILED | error_code=CARD_DECLINED |
| S-05 | GET /payments/{intent_id} after authorization | — | 200 | AUTHORIZED | — | Full detail returned |
| S-06 | GET /payments/{intent_id}/status | — | 200 | AUTHORIZED | — | Status-only lightweight response |
| S-07 | Webhook AUTHORIZED event, valid HMAC | — | 200 | AUTHORIZED | AUTHORIZED | Idempotency entry acknowledged |
| S-08 | Outbox publisher cycle | — | — | — | — | PENDING outbox → PROCESSED, InMemoryPublisher captures event |

### Key Assertions for S-01

```
HTTP 200
body.status = "AUTHORIZED"
body.intent_id != null
body.provider_reference != null
DB: payment_intents.status = AUTHORIZED
DB: payment_attempts.status = AUTHORIZED
DB: payment_idempotency.status = COMPLETED
DB: payment_outbox rows ≥ 1 with status = PENDING
```

---

## 2. Regression Tests (R-XX) — Correctness Under Edge Cases

| ID | Scenario | Trigger | Expected Outcome | DB State After |
|----|----------|---------|-----------------|----------------|
| R-01 | Idempotency replay: same key + same body | Identical POST twice | HTTP 200 cached, same intent_id | 1 PaymentIntent row |
| R-02 | Idempotency conflict: same key + different body | POST with changed amount | HTTP 409 IDEMPOTENCY_CONFLICT | No new intent created |
| R-03 | Duplicate merchant order: same merchant_id + merchant_order_id, new idempotency key | Second payment by same merchant for same order | HTTP 409 DUPLICATE_ORDER_ID | No new intent created |
| R-04 | Reconciliation resolves PENDING → AUTHORIZED | PSP_A.mode=SUCCESS, reconcileIntent() called | intent=AUTHORIZED, attempt=AUTHORIZED | 1 reconciliation event in payment_events |
| R-05 | Reconciliation resolves PENDING → FAILED | PSP_A.mode=FAILURE, reconcileIntent() called | intent=FAILED, attempt=FAILED | PAYMENT_FAILED event written |
| R-06 | Retry flow: PENDING intent → RetryService | PSP_A.mode=TIMEOUT → SUCCESS | intent=AUTHORIZED, N+1 attempt AUTHORIZED | Old attempt=PENDING, active_attempt_id updated |
| R-07 | Retry max exceeded (5 total attempts) | 5 failed attempts | intent=FAILED | PAYMENT_FAILED+DLQ event, outbox entry |
| R-08 | Webhook for already-AUTHORIZED intent | Send AUTHORIZED webhook to AUTHORIZED intent | HTTP 200 acknowledged, no state change | intent remains AUTHORIZED |
| R-09 | Webhook FAILED→AUTHORIZED on FAILED intent | Send AUTHORIZED webhook to FAILED intent | HTTP 422 ILLEGAL_STATE_TRANSITION | intent remains FAILED |
| R-10 | Duplicate webhook event_id | Same event_id twice to /webhooks/PSP_A | HTTP 200 both times | 1 processed_webhook row |
| R-11 | Outbox not re-published after PROCESSED | processPendingBatch() twice | Second cycle publishes 0 events | outbox rows remain PROCESSED |
| R-12 | Reconciliation on AUTHORIZED intent | reconcileIntent() called on AUTHORIZED intent | No state change | intent remains AUTHORIZED |
| R-13 | PENDING critical alerting >48h | Intent created with created_at 49h ago | intent remains PENDING | Critical alert warning logged |
| R-14 | Operational alert >24h | Intent created with created_at 25h ago | Alert metric counter incremented | intent remains PENDING |

### R-01 Detail: Idempotency Replay

```
First request:
  POST /payments
  Idempotency-Key: abc-123
  Body: { merchant_id: "...", amount: 100.00, ... }
  → HTTP 200, intent_id: "uuid-1"

Second request (identical):
  POST /payments
  Idempotency-Key: abc-123
  Body: { merchant_id: "...", amount: 100.00, ... }  ← same canonical hash
  → HTTP 200, intent_id: "uuid-1"  ← SAME as first
  → No new DB row created
```

### R-09 Detail: FAILED → AUTHORIZED Blocked (Rule R-08)

```
State: PaymentIntent.status = FAILED

Incoming webhook:
  POST /webhooks/PSP_A
  body.status = "AUTHORIZED"

Response: HTTP 422
body.error_code = "ILLEGAL_STATE_TRANSITION"
body.message = "Cannot transition payment from FAILED to AUTHORIZED"

DB assertion: PaymentIntent.status still = FAILED
```

---

## 3. Security / Negative Tests (N-XX)

| ID | Scenario | Missing/Invalid Input | Expected HTTP | Error Code |
|----|----------|----------------------|---------------|------------|
| N-01 | Missing X-Request-Id header | No header | 400 | MISSING_REQUEST_ID |
| N-02 | Malformed X-Request-Id (not UUID) | `X-Request-Id: not-a-uuid` | 400 | VALIDATION_ERROR |
| N-03 | Missing X-Signature header | No signature header | 400 | VALIDATION_ERROR |
| N-04 | Invalid HMAC signature (tampered body) | Wrong signature | 401 | INVALID_SIGNATURE |
| N-05 | Reused nonce within 10-minute window | Same nonce on second request | 401 | NONCE_REUSED |
| N-06 | Timestamp 6 minutes in the past | X-Timestamp: now - 6min | 401 | TIMESTAMP_INVALID |
| N-07 | Timestamp 6 minutes in the future | X-Timestamp: now + 6min | 401 | TIMESTAMP_INVALID |
| N-08 | Missing Idempotency-Key header | No idempotency key | 400 | VALIDATION_ERROR |
| N-09 | merchant_id not a UUID | `"merchant_id": "not-uuid"` | 400 | VALIDATION_ERROR |
| N-10 | transaction_amount = 0 | `"amount": 0` | 400 | VALIDATION_ERROR |
| N-11 | transaction_amount = -50 | `"amount": -50` | 400 | VALIDATION_ERROR |
| N-12 | currency_code = "US" (2 characters) | `"currency_code": "US"` | 400 | VALIDATION_ERROR |
| N-13 | currency_code = "usd" (lowercase) | `"currency_code": "usd"` | 400 | VALIDATION_ERROR |
| N-14 | payment_method_type = "CRYPTO" | `"payment_method_type": "CRYPTO"` | 400 | VALIDATION_ERROR |
| N-15 | GET /payments/{id} with nonexistent intent_id | Valid UUID, no matching record | 404 | PAYMENT_NOT_FOUND |
| N-16 | GET /payments/{id} with malformed UUID | `"intent_id": "not-a-uuid"` | 400 | VALIDATION_ERROR |
| N-17 | Webhook with invalid PSP signature | `X-PSP-Signature: invalid_signature` | 422 | INVALID_WEBHOOK_SIGNATURE |
| N-18 | Webhook referencing unknown provider_reference | No matching attempt in DB | 404 | PAYMENT_NOT_FOUND |
| N-19 | Late webhook for inactive attempt | Target attempt matches inactive provider_ref | HTTP 200 | Targeted attempt authorized, parent intent resolved |
| N-20 | Webhook from unknown provider | POST /webhooks/UNKNOWN_PSP | 404 | PAYMENT_NOT_FOUND |
| N-21 | Empty request body | `{}` | 400 | VALIDATION_ERROR |
| N-22 | Nonce reuse audit trail | N-05 reuse scenario | audit event in payment_events | Forensic log present |

### N-05 Detail: Nonce Reuse Audit

```
First request:
  X-Nonce: nonce-abc-123
  → HTTP 200 (nonce stored in Redis with 600s TTL)

Second request within window:
  X-Nonce: nonce-abc-123 (same nonce)
  → HTTP 401
  → body.error_code = "NONCE_REUSED"
  → Audit log entry written to payment_events OR log output (SECURITY AUDIT)
```

---

## 4. Integration Tests (I-XX) — Testcontainers End-to-End

All I-XX tests run against real PostgreSQL 15 and Redis 7 containers.  
Test class: [`ContainerIntegrationTests.java`](../src/test/java/com/payments/orchestrator/ContainerIntegrationTests.java)

| ID | Scenario | Test Method | Key Assertions |
|----|----------|-------------|----------------|
| I-01 | Successful authorization | `scenario01_successfulAuthorization` | HTTP 200, intent=AUTHORIZED, idempotency=COMPLETED, outbox PENDING created |
| I-02 | Definitive PSP failure | `scenario02_definitivePspFailure` | HTTP 200, intent=FAILED, error_code=CARD_DECLINED |
| I-03 | PSP timeout → PENDING | `scenario03_timeoutToPending` | HTTP 202, intent=PENDING, error_code=PSP_TIMEOUT |
| I-04 | Reconciliation resolution | `scenario04_reconciliationResolution` | PENDING → AUTHORIZED via reconcileIntent() |
| I-05 | Idempotency replay | `scenario05_idempotencyReplay` | Same intent_id returned, 1 DB row |
| I-06 | Idempotency conflict | `scenario06_idempotencyConflict` | HTTP 409 on body mismatch |
| I-07 | Nonce / duplicate order replay | `scenario07_duplicateMerchantOrder` | HTTP 409 DUPLICATE_ORDER_ID |
| I-08 | Invalid webhook signature | `scenario08_invalidWebhookSignature` | HTTP 422 on "invalid_signature" header |
| I-09 | Webhook deduplication | `scenario09_webhookDeduplication` | 1 ProcessedWebhook row for same event_id |
| I-10 | Retry flow | `scenario10_retryFlow` | N+1 attempt=AUTHORIZED, old=PENDING, active_attempt_id updated |
| I-11 | Outbox publish | `scenario11_outboxPublish` | Outbox PENDING → PROCESSED, InMemoryPublisher has events |
| I-12 | Illegal state transitions | `scenario12_illegalStateTransition` | AUTHORIZED→FAILED idempotent; FAILED→AUTHORIZED 422 |
| I-13 | Missing idempotency key | `hardening_missingIdempotencyKey` | HTTP 4xx |
| I-14 | Processed outbox not re-published | `hardening_processedOutboxNotRepublished` | 0 events on second publish cycle |
| I-15 | AUTHORIZED intent not re-reconciled | `hardening_authorizedIntentNotRereconciled` | intent remains AUTHORIZED after reconcileIntent() |

### Container Configuration

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
    .withDatabaseName("payment_orchestrator")
    .withUsername("testuser")
    .withPassword("testpass");

@Container
static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
    .withExposedPorts(6379);

@DynamicPropertySource
static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
}
```

### PSP Mode Control in Tests

```java
@BeforeEach
void resetState() {
    pspAConnector.setMode("SUCCESS");  // reset to clean state
    pspBConnector.setMode("SUCCESS");
    inMemoryEventPublisher.clear();
}

// Within a specific test:
pspAConnector.setMode("TIMEOUT");     // simulate read timeout
pspAConnector.setMode("FAILURE");     // simulate definitive decline
```

---

## 5. Performance / Stress Test Scenarios (P-XX)

> These are not automated in the unit/integration test suite. Run with an external tool (k6, Gatling, wrk).

| ID | Scenario | Target | Tool |
|----|----------|--------|------|
| P-01 | Authorization throughput baseline | 500 TPS, P95 < 2s (ex-PSP latency) | k6 |
| P-02 | Idempotency lookup under load | 10,000 concurrent idempotency keys | k6 |
| P-03 | Outbox lag under sustained write load | Outbox lag < 2s at 500 TPS | Prometheus |
| P-04 | Reconciliation backlog under burst | <50 intents per 45s cycle | Micrometer gauge |
| P-05 | Redis nonce throughput | 500 writes/sec without timeout | Redis INFO stats |
| P-06 | Circuit breaker failover under load | PSP_A failure → PSP_B in < 100ms | k6 + circuit breaker metrics |

---

## 6. Consistency Validation Checklist

The following cross-document consistency was verified as part of Phase 7:

| Check | Source A | Source B | Status |
|-------|----------|----------|--------|
| API base path `/api/v1/payments-orchestration` | `master_context.md §10` | `swagger.yaml`, `PaymentController` | ✅ Consistent |
| State transitions | `master_context.md §6` | `PaymentLifecycleValidator` | ✅ Consistent |
| Idempotency TTL 24h | `master_context.md §7.4` | `IdempotencyServiceImpl` | ✅ Consistent |
| Nonce window 10 minutes | `architecture.md §Security` | `SecurityFilter` Redis TTL 600s | ✅ Consistent |
| Outbox poll interval 1500ms | `master_context.md §15.1` | `OutboxPublisherWorker @Scheduled` | ✅ Consistent |
| Max retry attempts 5 | `master_context.md §14.3` | `RetryServiceImpl` threshold check | ✅ Consistent |
| PENDING on timeout | `master_context.md §4.2` | `PaymentOrchestrationFlowManagerImpl` | ✅ Consistent |
| FAILED→AUTHORIZED blocked | `architecture.md §API Reference` | `WebhookServiceImpl` R-08 rule | ✅ Consistent |
| SKIP LOCKED on workers | `master_context.md §7.7` | `PaymentOutboxRepository`, `PaymentIntentRepository` | ✅ Consistent |
| Webhook deduplication | `master_context.md §15.4` | `ProcessedWebhookRepository` unique constraint | ✅ Consistent |
