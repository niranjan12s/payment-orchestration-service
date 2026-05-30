# Bug Fix Tracking

This document tracks the bugs found during the repository review and the changes made to address them.

## Fixed Bugs

### 1. Webhooks blocked by merchant payment security validation

**Problem:** `SecurityFilter` used `path.contains("/payments")` to decide whether to enforce merchant payment HMAC headers. The webhook URL is `/api/v1/payments-orchestration/webhooks/{provider}`. Because the service prefix contains `payments-orchestration`, the substring check matched webhook requests too.

**Impact:** Valid PSP webhooks could be rejected before reaching `WebhookController` because they usually send `X-PSP-Signature`, not merchant headers such as `X-Timestamp`, `X-Nonce`, `X-Signature`, and a `merchant_id` body field.

**Fix:** Changed the filter to require an exact match on `POST /api/v1/payments-orchestration/payments`.

**Files:**
- `src/main/java/com/payments/orchestrator/security/SecurityFilter.java`

### 2. Nonce replay protection race

**Problem:** `SecurityValidator` checked Redis with `hasKey` and then wrote the nonce with `set`. Two concurrent requests could both observe the nonce as absent.

**Impact:** Replay protection could be bypassed under concurrency.

**Fix:** Replaced the check-then-set sequence with atomic Redis `setIfAbsent` and a 10-minute TTL.

**Files:**
- `src/main/java/com/payments/orchestrator/security/SecurityValidator.java`

### 3. Signature comparison timing leakage

**Problem:** Merchant and webhook HMAC signatures were compared with plain `String.equals`.

**Impact:** Plain equality can return early, leaking small timing differences.

**Fix:** Added `SecurityUtils.constantTimeEquals` backed by `MessageDigest.isEqual`, and used it for merchant and PSP webhook signatures.

**Files:**
- `src/main/java/com/payments/orchestrator/security/SecurityUtils.java`
- `src/main/java/com/payments/orchestrator/security/SecurityValidator.java`
- `src/main/java/com/payments/orchestrator/service/WebhookServiceImpl.java`

### 4. Idempotency insert race

**Problem:** A first request checked for an existing idempotency row and then inserted. Two concurrent first requests for the same key could both see no record.

**Impact:** One request could hit the database unique constraint and surface as an unexpected server error.

**Fix:** The idempotency claim now flushes immediately and translates concurrent unique-key claims into `IdempotencyConflictException`.

**Files:**
- `src/main/java/com/payments/orchestrator/service/IdempotencyServiceImpl.java`

### 5. Fallback attempt changed payment method type

**Problem:** Fallback attempt creation inferred payment method type from fallback provider, mapping `PSP_A` to `CARD` and `PSP_B` to `UPI`.

**Impact:** A CARD payment failing over to `PSP_B` could become a UPI attempt, corrupting payment semantics.

**Fix:** Fallback attempts now preserve the original attempt payment method type.

**Files:**
- `src/main/java/com/payments/orchestrator/service/PaymentOrchestrationServiceImpl.java`

### 6. Webhook correlation ambiguity

**Problem:** Webhooks were correlated by `provider_reference` alone, while the database only had a non-unique index on that column.

**Impact:** Duplicate references across PSPs or bad/mock data could correlate a webhook to the wrong attempt.

**Fix:** Webhook lookup now uses `(provider_name, provider_reference)`, and a Flyway migration adds a partial unique index for non-null provider references.

**Files:**
- `src/main/java/com/payments/orchestrator/repository/PaymentAttemptRepository.java`
- `src/main/java/com/payments/orchestrator/service/WebhookServiceImpl.java`
- `src/main/resources/db/migration/V3__harden_webhook_provider_reference.sql`

### 7. Repeated Micrometer gauge registration

**Problem:** Workers registered gauges by passing a numeric value each scheduler cycle.

**Impact:** This can create stale or duplicate gauge registrations instead of updating one stable metric.

**Fix:** Workers now register `AtomicLong` gauges once at startup and update the value each cycle.

**Files:**
- `src/main/java/com/payments/orchestrator/worker/OutboxPublisherWorker.java`
- `src/main/java/com/payments/orchestrator/worker/RetryWorker.java`
- `src/main/java/com/payments/orchestrator/worker/ReconciliationWorker.java`

## Already Addressed In The Codebase

### JSONB mapping

The walkthrough flagged JSONB entity mappings as a risk. On inspection, `PaymentIdempotency`, `PaymentEvent`, and `PaymentOutbox` already use Hibernate 6 JSON annotations with `@JdbcTypeCode(SqlTypes.JSON)`, so no additional patch was needed.

**Files checked:**
- `src/main/java/com/payments/orchestrator/domain/PaymentIdempotency.java`
- `src/main/java/com/payments/orchestrator/domain/PaymentEvent.java`
- `src/main/java/com/payments/orchestrator/domain/PaymentOutbox.java`

## Remaining Design Considerations

### Outbox delivery semantics

The outbox worker publishes while the database transaction is active and marks the row processed afterward. If publish succeeds but the transaction later rolls back, the event may be published again. This is acceptable only if consumers are idempotent. A larger redesign could claim rows, publish outside the transaction, and mark completion in a separate transaction.

### Failed response idempotency caching

Failed responses are cached as completed idempotency results. This is correct for business declines, but infrastructure failures such as `SYSTEM_ERROR` may deserve a shorter-lived or retryable idempotency state.

---

## Code Quality Fixes (Post-Walkthrough)

### 8. Naming clarity enforcement — abbreviations and opaque variables eliminated

**Problem:** Variable names across the codebase used abbreviations and single-letter identifiers:
- All `catch (XxxException e)` blocks used the opaque name `e`
- Short locals: `hash`, `pi`, `record`, `node`, `body`, `path`, `secret`, `isDuplicate`, `ignoreIntentTransition`, `retrySafe`, `cbTriggeredFailover`, `reqId`, `msg`, etc.
- Loop variables: `int i`, `byte b`

**Fix:** All abbreviated and opaque names replaced with full role-descriptive identifiers across every Java source file. Selected examples:

| Old | New | File |
|-----|-----|------|
| `catch (Exception e)` | `catch (Exception databaseProbeFailure)` | `DatabaseHealthIndicator` |
| `catch (Exception e)` | `catch (Exception requestBodyReadException)` | `SecurityFilter` |
| `catch (DataIntegrityViolationException e)` | `catch (DataIntegrityViolationException concurrentInsertException)` | `IdempotencyServiceImpl` |
| `catch (PspTimeoutException e)` | `catch (PspTimeoutException pspTimeoutException)` | `FlowManagerImpl`, `RetryServiceImpl` |
| `catch (CallNotPermittedException e)` | `catch (CallNotPermittedException circuitBreakerException)` | `FlowManagerImpl`, `RetryServiceImpl` |
| `boolean cbTriggeredFailover` | `boolean circuitBreakerTriggeredFailover` | `FlowManagerImpl` |
| `boolean isSafe` | `boolean isSafeTransportError` | `FlowManagerImpl` |
| `String msg` | `String exceptionMessage` | `FlowManagerImpl` |
| `boolean isDuplicate` | `boolean isWebhookAlreadyProcessed` | `WebhookServiceImpl` |
| `boolean ignoreIntentTransition` | `boolean isIntentTransitionContradictory` | `WebhookServiceImpl` |
| `String secret` | `String providerWebhookSecret` | `WebhookServiceImpl` |
| `boolean retrySafe` | `boolean isErrorCodeSafeForRetry` | `RetryServiceImpl` |
| `String hash` | `String requestBodyHash` | `IdempotencyServiceImpl` |
| `PaymentIdempotency pi` | `PaymentIdempotency newIdempotencyRecord` | `IdempotencyServiceImpl` |
| `List<String> secrets` | `List<String> merchantSecrets` | `SecurityValidator` |
| `boolean signatureMatches` | `boolean isSignatureValid` | `SecurityValidator` |
| `byte[] hash` | `byte[] sha256DigestBytes` | `SecurityUtils` |
| `for (byte b : hash)` | `for (byte digestByte : sha256DigestBytes)` | `SecurityUtils` |
| `String reqId` | `String resolvedRequestId` | `GlobalExceptionHandler` |
| `for (int i = 0; ...)` | `for (int partIndex = 0; ...)` | `GlobalExceptionHandler` |

**Verified:** `mvn clean compile` — 89 sources — **BUILD SUCCESS**

---

### 9. Reconciliation backlog gauge used full table scan (BUG-NEW-1)

**Problem:** `ReconciliationWorker` computed the PENDING backlog gauge via `intentRepository.findAll()`, loading every entity into memory on every 45-second cycle.

**Fix:** Replaced with `intentRepository.countByStatus(PaymentStatus.PENDING)`.

**Files:**
- `src/main/java/com/payments/orchestrator/worker/ReconciliationWorker.java`
- `src/main/java/com/payments/orchestrator/repository/PaymentIntentRepository.java`

---

### 10. Retry lag gauge fetched 1000 full entities for a count (BUG-NEW-2)

**Problem:** `RetryWorker` computed lag via `findTopPendingForRetry(..., PageRequest.of(0, 1000)).size()`, hydrating full entity graphs just to get a count.

**Fix:** Replaced with `intentRepository.countByStatus(PaymentStatus.PENDING)`.

**Files:**
- `src/main/java/com/payments/orchestrator/worker/RetryWorker.java`

---

### 11. Intent persisted as CREATED in TX-1, invisible to recovery workers on crash (BUG-NEW-3)

**Problem:** `createInitialPaymentState` persisted `PaymentIntent` with `status = CREATED`. On a crash between TX-1 and TX-2, the intent was permanently orphaned — all recovery workers poll only `PENDING` intents.

**Fix:** Initial status changed to `PROCESSING` in TX-1.

**Files:**
- `src/main/java/com/payments/orchestrator/service/PaymentOrchestrationServiceImpl.java`

