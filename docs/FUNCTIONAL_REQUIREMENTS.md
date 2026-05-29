# Functional Requirements — Payment Orchestration Service

**Document ID:** FUNC-REQ-001  
**Version:** 1.0.0  
**Date:** 2026-05-27  
**Status:** Approved  
**Scope:** Authorization lifecycle only. Settlement, capture, refund, ledgering, and acquiring workflows are explicitly out of scope.

---

## Table of Contents

1. [FR-001–010: Payment Authorization](#fr-001--fr-010-payment-authorization)
2. [FR-011–015: Idempotency](#fr-011--fr-015-idempotency)
3. [FR-016–022: Security & Replay Protection](#fr-016--fr-022-security--replay-protection)
4. [FR-023–030: Payment State Machine](#fr-023--fr-030-payment-state-machine)
5. [FR-031–037: Reconciliation Worker](#fr-031--fr-037-reconciliation-worker)
6. [FR-038–043: Retry Worker](#fr-038--fr-043-retry-worker)
7. [FR-044–050: Webhook Ingestion](#fr-044--fr-050-webhook-ingestion)
8. [FR-051–055: Outbox Publisher](#fr-051--fr-055-outbox-publisher)
9. [FR-056–060: Observability](#fr-056--fr-060-observability)
10. [Summary Table](#summary-table)

---

## FR-001 – FR-010: Payment Authorization

This section specifies the synchronous authorization path: request ingestion, persistence guarantees, PSP dispatch, and response semantics.

> [!IMPORTANT]
> The single most critical correctness rule: **persist state first, call PSP only after commit**. Violating this ordering causes orphaned authorizations, duplicate charges, and irrecoverable reconciliation inconsistency.

---

### FR-001 — Authorization Endpoint

The system **shall** accept payment authorization requests via:

```
POST /api/v1/payments-orchestration/payments
```

The request body **shall** conform to the OpenAPI 3.0 schema defined in `swagger.yaml`. All required fields must be present and valid before the request proceeds past schema validation.

**Required request body fields:**

| Field | Type | Constraint |
|---|---|---|
| `merchant_id` | UUID v4 | Non-null, registered merchant |
| `merchant_order_id` | String (≤255) | Unique per merchant |
| `payment_method_type` | Enum | `CARD` or `UPI` |
| `payment_token_reference` | String | Non-null, non-empty |
| `transaction_amount.amount` | Decimal | Positive, above minimum threshold |
| `transaction_amount.currency_code` | String | ISO 4217, 3-letter uppercase |
| `settlement_currency_code` | String | ISO 4217, 3-letter uppercase |

---

### FR-002 — Idempotency-Key Header

Every request to `POST /api/v1/payments-orchestration/payments` **shall** include an `Idempotency-Key` header.

| Constraint | Value |
|---|---|
| Format | UUID v4 |
| Absence | `400 VALIDATION_ERROR` |
| Malformed value | `400 VALIDATION_ERROR` |
| TTL | 24 hours from first use |

The idempotency key is scoped per merchant. Two different merchants may use the same key value independently without conflict.

---

### FR-003 — PSP Routing by Payment Method

The system **shall** route payment authorization requests to the correct Payment Service Provider based on `payment_method_type`:

| Payment Method | Target PSP |
|---|---|
| `CARD` | `PSP_A` |
| `UPI` | `PSP_B` |

Routing decisions additionally consider:
- Provider health scores
- Circuit breaker state (OPEN → route to fallback or reject)
- Configured priority weights
- Currency and geography compatibility

The orchestrator **shall never** handle raw card credentials or payment instrument data directly. All instrument resolution occurs through the `PaymentInstrumentResolver` interface, keeping vault and tokenization concerns decoupled from orchestration logic.

---

### FR-004 — Atomic Pre-PSP Persistence

Before any PSP call is made, the system **shall** atomically persist all four records within a single database transaction:

```sql
BEGIN TRANSACTION;
  INSERT INTO payment_intents   (...);
  INSERT INTO payment_attempts  (...);
  INSERT INTO payment_events    (...);
  INSERT INTO payment_outbox    (...);
COMMIT;
```

If any insert fails, the entire transaction **shall** be rolled back. The PSP **shall not** be called if the transaction does not commit successfully.

This guarantees that every PSP call is traceable to a persisted intent. Without this guarantee, a PSP could authorize funds while the orchestrator loses state, creating irrecoverable orphaned charges.

---

### FR-005 — PSP Call After Commit Only

The PSP authorization call **shall** occur only after the database transaction from FR-004 has successfully committed.

The PSP call **shall never** be made:
- Inside the transaction boundary
- Before all four records are durably written
- As a speculative call before persistence

This is a non-negotiable correctness rule. It is the system's primary defense against duplicate charge scenarios.

---

### FR-006 — Successful Authorization Response

When the PSP returns a definitive authorization success, the system **shall** respond:

```
HTTP 200 OK
{
  "status": "AUTHORIZED",
  "intent_id": "<uuid>",
  "provider_reference": "<psp_ref>"
}
```

The intent status in the database **shall** be updated to `AUTHORIZED`. The attempt status **shall** be updated to `AUTHORIZED`. A `PAYMENT_AUTHORIZED` event **shall** be written to `payment_events`.

---

### FR-007 — Definitive PSP Failure Response

When the PSP returns a definitive, unrecoverable decline (e.g., `INSUFFICIENT_FUNDS`, `INVALID_INSTRUMENT`, `ISSUER_DECLINE`, `DO_NOT_HONOR`), the system **shall** respond:

```
HTTP 200 OK
{
  "status": "FAILED",
  "intent_id": "<uuid>",
  "failure_reason": "<PSP_ERROR_CODE>"
}
```

> [!NOTE]
> HTTP 200 is correct here. The orchestration request succeeded; the payment outcome failed. This is not a transport error, schema error, or authentication failure. Using a non-2xx code for a processed business outcome would misrepresent the transaction semantics to the caller.

The intent status **shall** be updated to `FAILED`. A `PAYMENT_FAILED` event **shall** be written to `payment_events`.

---

### FR-008 — PSP Timeout Response

When the PSP does not respond within the configured `psp.timeout-ms`, the system **shall**:

1. Mark the `payment_attempt` status as `PENDING`
2. Mark the `payment_intent` status as `PENDING`
3. Persist a `PROVIDER_TIMEOUT` event to `payment_events`
4. Release the request thread
5. Respond:

```
HTTP 202 Accepted
{
  "status": "PENDING",
  "intent_id": "<uuid>"
}
```

The caller **shall** be expected to poll `GET /api/v1/payments-orchestration/payments/{intent_id}/status` or await a webhook notification for final resolution.

---

### FR-009 — No Second PSP Call on Ambiguous Timeout

When a PSP call results in a timeout (FR-008), the system **shall not** make a second PSP call for the same attempt until reconciliation or webhook processing has conclusively confirmed that no authorization occurred.

> [!CAUTION]
> Immediate retry on timeout risks double-charging the customer. The PSP may have authorized funds and lost only the response transmission. `PENDING` is the only safe model for distributed payment timeout.

Failover to an alternate PSP is also prohibited for the same reason. Ambiguous outcomes require confirmation before any further execution.

---

### FR-010 — Payment Method to PSP Connector Routing

Each payment method **shall** be processed by its designated PSP connector:

| Payment Method | PSP Connector | Protocol |
|---|---|---|
| `CARD` | `PSP_A` connector | Provider-specific REST/ISO 8583 |
| `UPI` | `PSP_B` connector | Provider-specific UPI rails |

Connector selection **shall** be determined by the routing engine before the PSP call. The routing engine **shall** be isolated behind an interface so connectors are replaceable without modifying orchestration logic.

---

## FR-011 – FR-015: Idempotency

This section specifies idempotency enforcement, payload hashing, conflict detection, and TTL behavior.

---

### FR-011 — Identical Key + Identical Payload Returns Cached Response

When the system receives a request where:
- `Idempotency-Key` matches a non-expired record in `payment_idempotency`, **and**
- The SHA-256 hash of the canonicalized request body matches the stored `request_hash`

The system **shall**:
- Return the cached `response_payload` directly
- **Not** insert any new rows into `payment_intents`, `payment_attempts`, `payment_events`, or `payment_outbox`
- **Not** make any PSP call

This guarantees safe retries from clients experiencing network failures without creating duplicate payments.

---

### FR-012 — Same Key + Different Payload Returns HTTP 409

When the system receives a request where:
- `Idempotency-Key` matches a non-expired record in `payment_idempotency`, **and**
- The SHA-256 hash of the canonicalized request body **does not** match the stored `request_hash`

The system **shall** respond:

```
HTTP 409 Conflict
{
  "error_code": "IDEMPOTENCY_CONFLICT",
  "message": "Idempotency key already used with a different request payload."
}
```

No payment processing **shall** occur. No new records **shall** be created.

---

### FR-013 — Expired Keys Treated as New Requests

When the system receives a request with an `Idempotency-Key` that exists in `payment_idempotency` but whose `expires_at` timestamp has passed, the system **shall** treat the request as a completely new payment authorization.

The expired record **shall** not be reused. A new `payment_idempotency` record **shall** be created for the new request. Standard processing **shall** proceed from FR-004 onward.

Expired rows **shall** be cleaned up by a scheduled background job to prevent unbounded table growth.

---

### FR-014 — SHA-256 of Canonicalized JSON

The idempotency payload hash **shall** be computed as:

```
SHA-256(canonicalized_json_body)
```

Canonicalization rules **shall** enforce:
- Lexicographically sorted keys (recursive, including nested objects)
- No extraneous whitespace
- Deterministic UTF-8 serialization
- No floating-point normalization variations

The system **shall not** hash raw JSON bytes directly. Raw JSON byte-level hashing produces false conflicts when field ordering varies across serializers.

**Example — these two payloads must produce the same hash:**
```json
{"amount": 100, "currency": "USD"}
{"currency": "USD", "amount": 100}
```

---

### FR-015 — Idempotency TTL is 24 Hours

The `expires_at` timestamp for each `payment_idempotency` record **shall** be set to:

```
created_at + 24 hours
```

After expiry:
- Records **shall** be ineligible for cache hits (FR-013)
- Records **shall** be cleaned up by a scheduled purge job

This TTL covers all realistic merchant retry windows while preventing unbounded storage growth.

---

## FR-016 – FR-022: Security & Replay Protection

This section specifies header validation, HMAC signature verification, replay attack prevention, key rotation, and sensitive data handling.

> [!IMPORTANT]
> Validation **shall** execute in this strict order: Schema → Authentication → Replay Protection → Idempotency → Business Validation. Each layer fails fast before the next executes.

---

### FR-016 — X-Request-Id Header Required

Every request to `POST /api/v1/payments-orchestration/payments` **shall** include an `X-Request-Id` header.

| Constraint | Behavior |
|---|---|
| Absence | `400 MISSING_REQUEST_ID` |
| Not UUID v4 format | `400 VALIDATION_ERROR` |
| Present and valid | Echoed in all response bodies as `request_id` |

When `X-Request-Id` is absent, the system **shall** generate a system-prefixed fallback ID (`sys_<uuid>`) for log correlation, but **shall** still reject the request with `400`.

---

### FR-017 — X-Timestamp Must Be Within ±5 Minutes

The `X-Timestamp` header **shall** contain an ISO 8601 UTC timestamp. The system **shall** validate that this timestamp falls within a ±5-minute window of the server's current clock at the time of request processing.

| Condition | Response |
|---|---|
| Within ±5 minutes | Proceed to next validation layer |
| More than 5 minutes in the past | `401 TIMESTAMP_INVALID` |
| More than 5 minutes in the future | `401 TIMESTAMP_INVALID` |
| Absent or malformed | `400 VALIDATION_ERROR` |

This window limits the maximum replay exposure to 10 minutes of clock drift.

> [!NOTE]
> The system assumes NTP-synchronized infrastructure. Clock drift beyond the ±5-minute window is treated as an attack surface, not a configuration issue.

---

### FR-018 — X-Nonce Must Be Unique Per Merchant Within 10 Minutes

The `X-Nonce` header **shall** be a minimum-16-character opaque string. The system **shall** enforce uniqueness of the nonce per merchant within a 10-minute rolling window.

**Implementation:**
- On first use: write `nonce:{merchant_id}:{nonce}` to Redis with a 10-minute TTL
- On reuse within window: reject with `401 NONCE_REUSED` (see FR-020)

| Condition | Response |
|---|---|
| First use within window | Accepted; write to Redis |
| Reuse within window | `401 NONCE_REUSED` |
| Use after window expiry | Treated as new; accepted |
| Absent | `400 VALIDATION_ERROR` |

Redis **shall** have persistence enabled (AOF or RDB) and replicated topology to prevent replay windows from opening during Redis restarts or failovers.

---

### FR-019 — X-Signature Must Be Valid HMAC-SHA256

Every request **shall** include an `X-Signature` header containing a base64-encoded HMAC-SHA256 signature.

**Canonical string for signature computation:**

```
HTTP_METHOD\n
REQUEST_PATH\n
SHA256(canonical_request_body)\n
X-Timestamp\n
X-Nonce\n
merchant_id
```

**Signature:**
```
Base64(HMAC_SHA256(merchant_secret, canonical_string))
```

| Condition | Response |
|---|---|
| Signature valid against active key | Proceed |
| Signature valid against grace-period key | Proceed (key rotation) |
| Signature invalid against all keys | `401 INVALID_SIGNATURE` |
| Header absent | `400 VALIDATION_ERROR` |

---

### FR-020 — Nonce Reuse Must Be Audit-Logged

Every nonce reuse attempt **shall** be written to the audit log, regardless of whether the request was otherwise valid.

The audit log entry **shall** include:
- `merchant_id`
- `nonce` value (masked or hashed)
- `X-Request-Id`
- `X-Timestamp`
- Server-side timestamp of the reuse attempt
- Source IP (where available)

The audit trail **shall** be written to persistent storage (database), not solely to Redis, so it survives cache failures.

A `NONCE_REPLAY_ATTEMPT` event **shall** be published to the outbox for downstream fraud and security analysis consumers.

---

### FR-021 — Key Rotation With Configurable Grace Period

The system **shall** support zero-downtime merchant secret key rotation with a configurable grace period.

**Validation order during rotation:**
1. Validate incoming signature against the **active** (newest) key
2. If active key validation fails, validate against the **previous** (grace-period) key
3. If both fail, reject with `401 INVALID_SIGNATURE`

| Configuration | Default | Description |
|---|---|---|
| `security.key-rotation.grace-period-minutes` | 60 | Duration the old key remains valid after rotation |

After the grace period expires, the old key **shall** be invalidated and removed from the validation chain. Outbound signatures **shall** always use the newest key only.

Merchant secrets **shall** be stored in a KMS or secrets manager in production. They **shall never** be stored in `application.yml`, version control, or unencrypted environment files.

---

### FR-022 — Sensitive Data Must Be Masked in Logs

The following fields **shall never** appear unmasked in any log output, regardless of log level:

- `payment_token_reference`
- HMAC secrets and signing keys
- `X-Signature` header values
- Raw `Authorization` headers
- PSP credentials and API keys
- Full card numbers or CVVs (even in error contexts)

Masking **shall** use truncation with asterisk substitution:
```
payment_token_reference = tok_****8fa1
```

This requirement applies to:
- Application logs
- Exception stack traces
- HTTP access logs
- Audit logs
- Outbox event payloads

---

## FR-023 – FR-030: Payment State Machine

This section defines the complete state transition model for `PaymentIntent`, including allowed transitions, terminal states, and error handling for illegal transitions.

---

### FR-023 — Happy Path: CREATED → PROCESSING → AUTHORIZED

The standard authorization flow **shall** follow this state progression:

```
CREATED → PROCESSING → AUTHORIZED
```

| Transition | Trigger |
|---|---|
| `CREATED → PROCESSING` | Pre-PSP persistence committed; intent updated before PSP call |
| `PROCESSING → AUTHORIZED` | PSP returns definitive authorization success |

---

### FR-024 — Definitive Failure: PROCESSING → FAILED

When the PSP returns a definitive, unrecoverable decline response, the system **shall** transition:

```
PROCESSING → FAILED
```

`FAILED` is a terminal state. No further transitions are permitted from `FAILED` (see FR-028).

Error codes that trigger this transition include: `INSUFFICIENT_FUNDS`, `ISSUER_DECLINE`, `DO_NOT_HONOR`, `INVALID_INSTRUMENT`, and all PSP hard-decline codes classified as non-retryable.

---

### FR-025 — Ambiguous Timeout: PROCESSING → PENDING

When the PSP call times out without a definitive response, the system **shall** transition:

```
PROCESSING → PENDING
```

`PENDING` indicates that the outcome is uncertain. The system **shall not** treat this as a failure. Async resolution via reconciliation (FR-033) or webhook (FR-047) is required to determine the final outcome.

---

### FR-026 — Reconciliation Success: PENDING → AUTHORIZED

When reconciliation or webhook processing confirms that the PSP authorized the payment, the system **shall** transition:

```
PENDING → AUTHORIZED
```

`AUTHORIZED` is a terminal state. The reconciliation worker or webhook processor **shall** write a `RECONCILIATION_RESOLVED` or `WEBHOOK_RECEIVED` event to `payment_events`.

---

### FR-027 — Reconciliation Failure: PENDING → FAILED

When reconciliation or webhook processing confirms that the PSP did not authorize the payment, the system **shall** transition:

```
PENDING → FAILED
```

`FAILED` is a terminal state (see FR-028). A corresponding reconciliation event **shall** be written to `payment_events`.

---

### FR-028 — AUTHORIZED and FAILED Are Terminal States

Once a `PaymentIntent` reaches `AUTHORIZED` or `FAILED`, it **shall** not accept any further state transitions.

| Terminal State | Incoming Transition | Behavior |
|---|---|---|
| `AUTHORIZED` | Any further update | Ignored; audit-logged; operational alert emitted |
| `FAILED` | `AUTHORIZED` | Ignored; audit-logged; operational alert emitted |
| `AUTHORIZED` | `AUTHORIZED` (duplicate) | Idempotent no-op |
| `FAILED` | `FAILED` (duplicate) | Idempotent no-op |

---

### FR-029 — Illegal Transitions Throw IllegalStateTransitionException

Any attempt to perform a state transition not defined in the allowed transition table (FR-023–FR-027) **shall** throw an `IllegalStateTransitionException`.

This exception **shall** be mapped to:

```
HTTP 422 Unprocessable Entity
{
  "error_code": "ILLEGAL_STATE_TRANSITION",
  "from_state": "<current_state>",
  "to_state": "<attempted_state>"
}
```

**Full allowed transition table:**

| From | Allowed Transitions |
|---|---|
| `CREATED` | `PROCESSING` |
| `PROCESSING` | `AUTHORIZED`, `FAILED`, `PENDING` |
| `PENDING` | `AUTHORIZED`, `FAILED` |
| `AUTHORIZED` | *(terminal — none)* |
| `FAILED` | *(terminal — none)* |

> [!NOTE]
> `SUPERSEDED` applies only to `PaymentAttempt` records, not to `PaymentIntent` status. It **shall never** appear in public API status enums or payment status response schemas.

---

### FR-030 — PENDING Escalates to MANUAL_REVIEW After 48h

When a `PaymentIntent` remains in `PENDING` state for more than 48 hours without resolution, the system **shall** escalate it to an internal `MANUAL_REVIEW` status.

| Duration | Action |
|---|---|
| < 24h | Continue reconciliation polling |
| ≥ 24h unresolved | Emit operational alert |
| ≥ 48h unresolved | Transition to `MANUAL_REVIEW` |

`MANUAL_REVIEW` **shall** be internal only. It **shall not** appear in public API status enums or be returned to external callers.

---

## FR-031 – FR-037: Reconciliation Worker

This section specifies the behavior of the background reconciliation worker, which resolves `PENDING` intents by querying PSP status APIs.

---

### FR-031 — Configurable Polling Interval

The reconciliation worker **shall** poll for `PENDING` intents on a configurable interval.

| Configuration Key | Default | Description |
|---|---|---|
| `workers.reconciliation.interval-ms` | `45000` | Time between reconciliation cycles (ms) |

The worker **shall** use a scheduled executor and **shall** propagate MDC context across thread boundaries (FR-057).

---

### FR-032 — Query PSP Status Per Pending Intent

For each `PENDING` intent retrieved by the polling query, the reconciliation worker **shall**:
1. Identify the active `PaymentAttempt` associated with the intent
2. Use the `provider_reference` on the attempt to query the PSP's status API
3. Parse the PSP response to determine the current authorization state

The worker **shall** handle PSP status API errors gracefully: transient failures **shall** be retried on the next reconciliation cycle; the intent **shall** remain in `PENDING`.

---

### FR-033 — Resolve PENDING Based on PSP Response

Based on the PSP status query response, the reconciliation worker **shall** apply the following resolution logic:

| PSP Status Response | Intent Transition | Event Written |
|---|---|---|
| Authorized / confirmed | `PENDING → AUTHORIZED` | `RECONCILIATION_RESOLVED` |
| Declined / not found | `PENDING → FAILED` | `RECONCILIATION_RESOLVED` |
| Still processing / unknown | No change; retry next cycle | None |

All state updates **shall** be persisted atomically. Events **shall** be written to `payment_events` and `payment_outbox`.

---

### FR-034 — SELECT FOR UPDATE SKIP LOCKED for Concurrency Safety

The reconciliation worker's polling query **shall** use `FOR UPDATE SKIP LOCKED` to prevent multiple worker instances from processing the same intent simultaneously:

```sql
SELECT *
FROM payment_intents
WHERE status = 'PENDING'
ORDER BY updated_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

This prevents:
- Multi-worker collisions on the same intent
- Duplicate PSP status queries for the same record
- Backlog amplification from concurrent retries on a locked row

---

### FR-035 — Alert on Intents Unresolved > 24h

When a `PaymentIntent` has remained in `PENDING` state for more than 24 hours, the reconciliation worker **shall** emit an operational alert.

The alert **shall** include:
- `intent_id`
- `merchant_id`
- `created_at`
- Duration in `PENDING`
- Last reconciliation attempt timestamp

---

### FR-036 — Escalate to MANUAL_REVIEW After 48h

When a `PaymentIntent` has remained in `PENDING` state for more than 48 hours (after the operational alert in FR-035 has already fired), the reconciliation worker **shall** transition the intent to `MANUAL_REVIEW` (see FR-030).

The intent **shall** be removed from the active reconciliation queue after escalation to prevent unbounded polling.

---

### FR-037 — Reconciliation Cannot Transition Terminal States

The reconciliation worker **shall** skip any intent that has already reached `AUTHORIZED` or `FAILED` at the time of processing.

If a race condition causes a terminal-state intent to appear in the polling batch, the worker **shall**:
- Detect the terminal state before applying any transition
- Log the skip with intent ID and current state
- **Not** throw an exception or retry the skip

This prevents `IllegalStateTransitionException` from propagating in the background worker and disrupting the reconciliation cycle.

---

## FR-038 – FR-043: Retry Worker

This section specifies the safe retry strategy, including trigger conditions, attempt creation, backoff behavior, and maximum retry limits.

---

### FR-038 — Retries Allowed Only After Confirmed No-Auth

The retry worker **shall** trigger a new PSP authorization attempt only when reconciliation or webhook processing has conclusively confirmed that:
- The PSP did **not** process the original request, **and**
- The PSP has confirmed the attempt is in a retry-safe state (e.g., `NOT_FOUND` at the PSP, absent from PSP idempotency store)

The retry worker **shall never** retry on an ambiguous timeout without this confirmation. Double-charge prevention takes precedence over recovery speed.

---

### FR-039 — New PaymentAttempt Row Per Retry

Each retry **shall** create a new `payment_attempts` row.

| Field | Value on New Retry Attempt |
|---|---|
| `attempt_id` | New UUID |
| `intent_id` | Same as original |
| `retry_count` | Incremented by 1 from previous attempt |
| `status` | `PROCESSING` (initial state of new attempt) |
| `provider_reference` | Null until PSP responds |

Attempt rows **shall** never be mutated to represent a different retry. Each retry is a distinct execution record.

---

### FR-040 — Previous Attempt Marked SUPERSEDED

When a retry creates a new `PaymentAttempt`, the previous active attempt **shall** be updated to `SUPERSEDED` before the new PSP call is made.

`SUPERSEDED` is a terminal state for attempts. A `SUPERSEDED` attempt **shall** not be used for routing, status queries, or further retries.

---

### FR-041 — Exponential Backoff Formula

The retry worker **shall** calculate the delay before each retry attempt using:

```
delay = base_backoff_ms * (2 ^ retry_count)
```

Capped at `max_backoff_ms` to prevent unbounded delays.

| Configuration Key | Default | Description |
|---|---|---|
| `workers.retry.base-backoff-ms` | `1000` | Base delay in milliseconds |
| `workers.retry.max-backoff-ms` | `300000` | Maximum delay cap (5 minutes) |

**Example schedule:**

| Retry Count | Delay (base=1000ms) | Capped Delay |
|---|---|---|
| 1 | 2,000 ms | 2,000 ms |
| 2 | 4,000 ms | 4,000 ms |
| 3 | 8,000 ms | 8,000 ms |
| 4 | 16,000 ms | 16,000 ms |
| 5 | 32,000 ms | 32,000 ms |

---

### FR-042 — Maximum 5 Retry Attempts

The retry worker **shall** permit a maximum of 5 retry attempts per `PaymentIntent`.

When `retry_count` reaches 5 and the payment remains unresolved, the system **shall**:
- Transition the `PaymentIntent` to `FAILED`
- Write a final `PAYMENT_FAILED` event to `payment_events`
- Emit an operational alert

No further retry attempts **shall** be created after this point.

---

### FR-043 — Retry-Safe Error Code Classification

The retry worker **shall** only retry when the PSP error code is classified as retry-safe. The following error conditions are retry-safe:

| Error Code / Condition | Retry Safe |
|---|---|
| `NOT_FOUND` at PSP | ✅ Yes |
| `RETRY_SAFE_DECLINE` (explicit PSP classification) | ✅ Yes |
| `CONNECT_TIMEOUT` (pre-request transport failure) | ✅ Yes |
| `DNS_FAILURE` | ✅ Yes |
| `TCP_RESET_BEFORE_REQUEST` | ✅ Yes |
| Read timeout (ambiguous) | ❌ No — requires reconciliation |
| `PSP_5XX_AMBIGUOUS` | ❌ No — requires reconciliation |
| `INSUFFICIENT_FUNDS` | ❌ No — definitive decline |
| `INVALID_INSTRUMENT` | ❌ No — definitive decline |
| `MALFORMED_REQUEST` | ❌ No — non-retryable error |

The retry-safe classification **shall** be configurable and extensible without code changes.

---

## FR-044 – FR-050: Webhook Ingestion

This section specifies the PSP callback (webhook) ingestion pipeline, including signature verification, deduplication, correlation, and state transition rules.

---

### FR-044 — Webhook Endpoint

The system **shall** receive PSP asynchronous callbacks at:

```
POST /api/v1/payments-orchestration/webhooks/{provider}
```

Where `{provider}` is the PSP identifier (e.g., `psp_a`, `psp_b`). Requests to an unrecognized `{provider}` value **shall** return `404`.

---

### FR-045 — Webhook Signature Verification

Every incoming webhook **shall** have its signature verified using HMAC-SHA256 with the provider-specific webhook secret.

| Condition | Response |
|---|---|
| Signature valid | Proceed to deduplication |
| Signature invalid | `401 INVALID_WEBHOOK_SIGNATURE` |
| Signature header absent | `401 INVALID_WEBHOOK_SIGNATURE` |

Provider webhook secrets **shall** be stored in the secrets manager. They **shall never** appear in application configuration files or version control.

---

### FR-046 — Duplicate Event ID Is Idempotent

When a webhook event arrives with a `event_id` + `provider` combination that already exists in the `processed_webhooks` table, the system **shall**:
- Return `200 OK` (acknowledged)
- **Not** reprocess the event
- **Not** apply any state transition
- **Not** write any new records to `payment_events` or `payment_outbox`

This ensures webhook replays from PSPs do not cause duplicate state transitions.

---

### FR-047 — Webhook Correlates Attempt via provider_reference

Incoming webhooks **shall** be correlated to the correct `PaymentAttempt` using the `provider_reference` field present in both the webhook payload and the `payment_attempts` table.

| Condition | Response |
|---|---|
| `provider_reference` found in active attempt | Proceed to state transition |
| `provider_reference` not found | `404 PAYMENT_NOT_FOUND` |
| `provider_reference` maps to `SUPERSEDED` attempt | `422 ATTEMPT_SUPERSEDED` |

---

### FR-048 — Webhook for Already-AUTHORIZED Intent Is a No-Op

When a webhook arrives for a `PaymentIntent` that is already in `AUTHORIZED` state and the webhook also reports authorization success, the system **shall**:
- Return `200 OK` (acknowledged)
- Apply no state change
- Log the idempotent acknowledgement

This is the idempotent same-state case from the terminal state precedence matrix.

---

### FR-049 — FAILED → AUTHORIZED Webhook Transition Blocked

When a webhook arrives reporting authorization success for a `PaymentIntent` that is already in `FAILED` state, the system **shall**:
- Reject the transition
- Return `422 ILLEGAL_STATE_TRANSITION`
- Write an audit log entry
- Emit an operational alert

`FAILED` is a terminal state. A `FAILED → AUTHORIZED` transition is semantically impossible in this domain model. Accepting it would corrupt the payment record.

---

### FR-050 — Webhook Events Persisted to payment_events and payment_outbox

Upon successful webhook processing (after deduplication and correlation), the system **shall** write:
1. A `WEBHOOK_RECEIVED` event to `payment_events` (append-only)
2. A corresponding outbox entry to `payment_outbox` for downstream event publication

Both writes **shall** occur within the same database transaction as the intent/attempt state update. If any write fails, the entire transaction **shall** be rolled back, allowing the PSP to retry the webhook delivery.

---

## FR-051 – FR-055: Outbox Publisher

This section specifies the behavior of the transactional outbox polling publisher, which bridges durable database records to the event bus (Kafka or in-memory equivalent).

---

### FR-051 — Polls payment_outbox Every 1500ms

The outbox publisher worker **shall** poll the `payment_outbox` table for `PENDING` records at a fixed interval.

| Configuration Key | Default | Description |
|---|---|---|
| `workers.outbox-publisher.interval-ms` | `1500` | Polling interval in milliseconds |

The polling interval **shall** be configurable without code changes. A 1–2 second lag between DB commit and event publication is acceptable for all async recovery use cases in this system.

---

### FR-052 — SELECT FOR UPDATE SKIP LOCKED

The outbox publisher's polling query **shall** use `FOR UPDATE SKIP LOCKED` to prevent concurrent publisher instances from processing the same outbox row:

```sql
SELECT *
FROM payment_outbox
WHERE status = 'PENDING'
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

This guarantees at-most-once delivery per polling cycle across all running publisher instances.

---

### FR-053 — Publishes to Kafka (Prod) or InMemoryEventPublisher (Local/Test)

The outbox publisher **shall** use the `EventPublisher` interface to decouple the publication target from the polling logic.

| Active Profile | EventPublisher Implementation |
|---|---|
| `prod` | `KafkaEventPublisher` |
| `local` | `InMemoryEventPublisher` |
| `test` | `InMemoryEventPublisher` |

Switching publication targets **shall** require only a profile change. No orchestration logic changes **shall** be required.

On successful publication, the outbox row `status` **shall** be updated to `PROCESSED` and `processed_at` set to the current timestamp.

---

### FR-054 — Dead-Letters Events After 5 Failed Publish Attempts

When an outbox row fails to publish to the event bus, the retry count **shall** be incremented. After 5 consecutive failed attempts, the system **shall**:
- Set the outbox row `status` to `FAILED`
- Emit an operational alert identifying the dead-lettered event
- Stop attempting to republish the row

> [!WARNING]
> Dead-lettered outbox events represent a gap in downstream event delivery. Operational runbooks must include procedures for manually inspecting and redriving `FAILED` outbox rows.

---

### FR-055 — PROCESSED Outbox Rows Pruned After 7 Days

A scheduled maintenance job **shall** delete outbox rows that:
- Have `status = 'PROCESSED'`, **and**
- Have `processed_at` older than 7 days

```sql
DELETE FROM payment_outbox
WHERE status = 'PROCESSED'
AND processed_at < NOW() - INTERVAL '7 days';
```

This prevents table bloat from degrading the polling query performance over time. The partial index on `(status, created_at)` **shall** remain effective as long as this cleanup job runs.

---

## FR-056 – FR-060: Observability

This section specifies structured logging, MDC context propagation, metrics exposure, health indicators, and sensitive data masking requirements.

---

### FR-056 — Structured Log Context Fields

Every log line emitted by the system **shall** include the following correlation fields in the MDC (Mapped Diagnostic Context):

| Field | Source | Description |
|---|---|---|
| `request_id` | `X-Request-Id` header | Client-provided request identifier |
| `correlation_id` | `X-Correlation-Id` header or generated | Cross-system trace identifier |
| `internal_request_id` | System-generated | Internal observability ID; never exposed externally |
| `intent_id` | Resolved from DB | Payment intent being processed |
| `attempt_id` | Resolved from DB | Active payment attempt |
| `merchant_id` | Request payload | Merchant context |
| `provider_name` | Routing engine | PSP being called |

`internal_request_id` **shall** never appear in API responses. It is for internal tracing pipelines only.

---

### FR-057 — MDC Context Propagated Across Worker Threads

All background worker threads (reconciliation worker, retry worker, outbox publisher, webhook processor) **shall** propagate the full MDC context from their triggering context into the worker thread's execution scope.

This **shall** be implemented by:
- Capturing the MDC map at task submission time
- Restoring the MDC map at the start of each task execution
- Clearing the MDC map at task completion to prevent context bleed between tasks

Log lines emitted by background workers **shall** be correlatable to the original payment intent that triggered the async processing.

---

### FR-058 — Metrics Exposed at /actuator/prometheus

The system **shall** expose Prometheus-format metrics at:

```
GET /actuator/prometheus
```

**Required metrics:**

| Metric Name | Type | Description |
|---|---|---|
| `payment_authorization_success_total` | Counter | Total AUTHORIZED outcomes |
| `payment_authorization_failure_total` | Counter | Total FAILED outcomes |
| `payment_authorization_pending_total` | Counter | Total PENDING outcomes (timeouts) |
| `psp_call_duration_ms` | Histogram | P50/P95/P99 per provider |
| `psp_timeout_total` | Counter | PSP call timeouts per provider |
| `retry_attempt_total` | Counter | Retry executions per provider |
| `reconciliation_backlog_size` | Gauge | Count of PENDING intents |
| `outbox_lag_ms` | Gauge | Age of oldest PENDING outbox row |
| `circuit_breaker_state` | Gauge | 0=CLOSED, 1=HALF_OPEN, 2=OPEN per provider |

---

### FR-059 — Health Indicators at /actuator/health

The system **shall** expose a health check endpoint at:

```
GET /actuator/health
```

**Required health indicators:**

| Indicator | Description |
|---|---|
| `db` | PostgreSQL connectivity and query round-trip |
| `redis` | Redis connectivity and write/read round-trip |
| `kafka` | Kafka producer connectivity (prod profile only) |
| `diskSpace` | Application host disk space |

A degraded dependency **shall** be reflected as `DOWN` in the composite health response. The endpoint **shall** return HTTP 503 when any critical indicator is `DOWN`.

---

### FR-060 — Sensitive Data Masked in All Log Output

Consistent with FR-022, the observability stack **shall** enforce data masking at every output boundary.

**Masking applies to:**
- SLF4J/Logback appender output
- Exception message strings
- HTTP access logs (masked via request filter)
- Structured JSON log payloads
- Audit log entries

**Masking format:**
```
Full value:   tok_abc123def456gh78fa1
Masked value: tok_****8fa1
```

Masking configuration **shall** be centralized (e.g., a Logback `PatternLayout` or custom serializer) so new sensitive fields can be added without modifying every log call site.

---

## Summary Table

Complete index of all functional requirements with their area, status, and priority.

| FR ID | Title | Area | Priority |
|---|---|---|---|
| FR-001 | Authorization endpoint | Payment Authorization | P0 |
| FR-002 | Idempotency-Key header required | Payment Authorization | P0 |
| FR-003 | PSP routing by payment method | Payment Authorization | P0 |
| FR-004 | Atomic pre-PSP persistence | Payment Authorization | P0 |
| FR-005 | PSP call after commit only | Payment Authorization | P0 |
| FR-006 | Successful authorization response | Payment Authorization | P0 |
| FR-007 | Definitive failure response (HTTP 200 FAILED) | Payment Authorization | P0 |
| FR-008 | PSP timeout response (HTTP 202 PENDING) | Payment Authorization | P0 |
| FR-009 | No second PSP call on ambiguous timeout | Payment Authorization | P0 |
| FR-010 | Payment method to PSP connector routing | Payment Authorization | P0 |
| FR-011 | Identical key + payload returns cached response | Idempotency | P0 |
| FR-012 | Same key + different payload → HTTP 409 | Idempotency | P0 |
| FR-013 | Expired keys treated as new requests | Idempotency | P1 |
| FR-014 | SHA-256 of canonicalized JSON | Idempotency | P0 |
| FR-015 | Idempotency TTL is 24 hours | Idempotency | P1 |
| FR-016 | X-Request-Id required | Security | P0 |
| FR-017 | X-Timestamp within ±5 minutes | Security | P0 |
| FR-018 | X-Nonce unique per merchant per 10 minutes | Security | P0 |
| FR-019 | X-Signature valid HMAC-SHA256 | Security | P0 |
| FR-020 | Nonce reuse must be audit-logged | Security | P0 |
| FR-021 | Key rotation with configurable grace period | Security | P1 |
| FR-022 | Sensitive data masked in logs | Security | P0 |
| FR-023 | CREATED → PROCESSING → AUTHORIZED | State Machine | P0 |
| FR-024 | PROCESSING → FAILED on definitive failure | State Machine | P0 |
| FR-025 | PROCESSING → PENDING on timeout | State Machine | P0 |
| FR-026 | PENDING → AUTHORIZED on reconciliation success | State Machine | P0 |
| FR-027 | PENDING → FAILED on reconciliation failure | State Machine | P0 |
| FR-028 | AUTHORIZED and FAILED are terminal states | State Machine | P0 |
| FR-029 | Illegal transitions → IllegalStateTransitionException → 422 | State Machine | P0 |
| FR-030 | PENDING escalates to MANUAL_REVIEW after 48h | State Machine | P1 |
| FR-031 | Reconciliation worker configurable interval | Reconciliation | P1 |
| FR-032 | Query PSP status per pending intent | Reconciliation | P0 |
| FR-033 | Resolve PENDING based on PSP response | Reconciliation | P0 |
| FR-034 | SELECT FOR UPDATE SKIP LOCKED | Reconciliation | P0 |
| FR-035 | Alert on intents unresolved > 24h | Reconciliation | P1 |
| FR-036 | Escalate to MANUAL_REVIEW after 48h | Reconciliation | P1 |
| FR-037 | Reconciliation cannot transition terminal states | Reconciliation | P0 |
| FR-038 | Retry only after confirmed no-auth | Retry Worker | P0 |
| FR-039 | New PaymentAttempt row per retry | Retry Worker | P0 |
| FR-040 | Previous attempt marked SUPERSEDED | Retry Worker | P0 |
| FR-041 | Exponential backoff: base * 2^retry_count | Retry Worker | P1 |
| FR-042 | Maximum 5 retry attempts | Retry Worker | P1 |
| FR-043 | Retry-safe error code classification | Retry Worker | P0 |
| FR-044 | Webhook endpoint | Webhook Ingestion | P0 |
| FR-045 | Webhook signature verification | Webhook Ingestion | P0 |
| FR-046 | Duplicate event_id is idempotent | Webhook Ingestion | P0 |
| FR-047 | Webhook correlates via provider_reference | Webhook Ingestion | P0 |
| FR-048 | Webhook for already-AUTHORIZED intent is no-op | Webhook Ingestion | P0 |
| FR-049 | FAILED → AUTHORIZED webhook blocked → 422 | Webhook Ingestion | P0 |
| FR-050 | Webhook events persisted to events + outbox | Webhook Ingestion | P0 |
| FR-051 | Outbox publisher polls every 1500ms | Outbox Publisher | P1 |
| FR-052 | SELECT FOR UPDATE SKIP LOCKED | Outbox Publisher | P0 |
| FR-053 | Publishes to Kafka (prod) or InMemory (local/test) | Outbox Publisher | P1 |
| FR-054 | Dead-letters after 5 failed attempts | Outbox Publisher | P1 |
| FR-055 | PROCESSED rows pruned after 7 days | Outbox Publisher | P2 |
| FR-056 | Structured log context fields in all log lines | Observability | P0 |
| FR-057 | MDC propagated across worker threads | Observability | P1 |
| FR-058 | Metrics at /actuator/prometheus | Observability | P1 |
| FR-059 | Health indicators at /actuator/health | Observability | P1 |
| FR-060 | Sensitive data masked in all log output | Observability | P0 |

**Priority Legend:**

| Priority | Description |
|---|---|
| P0 | Blocking — system cannot operate correctly without this |
| P1 | High — required for production readiness |
| P2 | Medium — required for operational maturity |

---

## Out of Scope

The following capabilities are explicitly **not** covered by this specification and **shall not** be implemented within this service:

| Capability | Rationale |
|---|---|
| Settlement / clearing | Downstream acquirer rail concern |
| Capture workflows | Separate post-authorization lifecycle |
| Refunds and reversals | Separate financial operation |
| Ledger / accounting | Separate financial system |
| Card vault / tokenization | PCI-scoped system boundary |
| Real card credential storage | Explicit PCI exclusion |
| Multi-region distributed consensus | Out of scope for single-region deployment |
| Full event sourcing | Replaced by three-entity domain model |
| Merchant lifecycle management | External merchant platform concern |

---

*This document is derived from `master_context.md`, `architecture.md`, `reconciliation.md`, and `swagger.yaml`. Any architectural deviation requires explicit approval referencing the specific section being modified.*
