# Final Alignment Updates for README, Swagger, and Architecture Documents

This document contains the final alignment fixes and production-hardening improvements that must be applied consistently across:

* `README.md`
* `MASTER_CONTEXT.md`
* `ARCHITECTURE.md`
* `swagger.yaml`

These updates resolve semantic inconsistencies, operational ambiguity, and concurrency edge cases.

---

# 1. Payment Status Correction

## CRITICAL FIX

`SUPERSEDED` MUST NOT exist in payment intent status enums.

`SUPERSEDED` applies ONLY to:

* `payment_attempts`
* attempt execution lifecycle

It MUST NEVER appear in:

* payment intent status
* public payment status APIs
* payment status response schemas

---

## FINAL PaymentIntent Status Enum

```text
CREATED
PROCESSING
AUTHORIZED
FAILED
PENDING
```

---

## FINAL PaymentAttempt Status Enum

```text
PROCESSING
AUTHORIZED
FAILED
PENDING
SUPERSEDED
```

---

# 2. Definitive PSP Failure Response Semantics

## REQUIRED CHANGE

Definitive payment failures MUST return:

```http
HTTP 200
```

with:

```json
{
  "status": "FAILED"
}
```

---

## RATIONALE

The orchestration request itself succeeded.

The payment outcome failed.

This is NOT:

* transport failure
* schema validation failure
* authentication failure
* business validation failure

Therefore:

```text
200 FAILED
```

is the correct semantic model.

---

## Swagger Additions

Add explicit examples for:

* insufficient funds
* issuer decline
* invalid payment instrument
* PSP hard decline

Example:

```yaml
PaymentFailureResponse:
  summary: Definitive PSP decline
  value:
    intent_id: "0f6b4c3f-12ab-4b6f-93f1-b92b4f1ef001"
    status: "FAILED"
    provider_reference: "psp_ref_001"
    failure_reason: "INSUFFICIENT_FUNDS"
    timestamp: "2026-05-27T12:00:00Z"
```

---

# 3. Webhook vs Reconciliation Precedence Rules

## REQUIRED SECTION

Add explicit precedence rules.

---

## FINAL RULES

| Existing State | Incoming State | Allowed | Result             |
| -------------- | -------------- | ------- | ------------------ |
| PENDING        | AUTHORIZED     | YES     | AUTHORIZED         |
| PENDING        | FAILED         | YES     | FAILED             |
| AUTHORIZED     | FAILED         | NO      | Ignore + audit log |
| FAILED         | AUTHORIZED     | NO      | Ignore + audit log |
| AUTHORIZED     | AUTHORIZED     | YES     | idempotent noop    |
| FAILED         | FAILED         | YES     | idempotent noop    |

---

## TERMINAL STATE RULE

Once an intent reaches:

```text
AUTHORIZED
```

or:

```text
FAILED
```

it becomes immutable.

Subsequent contradictory updates:

* must be ignored
* must be audit logged
* must emit operational alerts

---

# 4. PSP Idempotency Strategy

## REQUIRED ADDITION

Every outbound PSP authorization request MUST contain:

```text
provider_idempotency_key
```

---

## Construction

Recommended format:

```text
merchant_id + merchant_order_id + attempt_id
```

or:

```text
intent_id + retry_count
```

The value MUST be deterministic.

---

## Purpose

Prevents:

* duplicate authorization
* duplicate PSP-side transaction creation
* retry duplication

---

## REQUIRED CONTRACT UPDATE

Add to PSP connector request object:

```java
String providerIdempotencyKey;
```

---

# 5. Reconciliation Query Locking

## REQUIRED SQL CHANGE

All reconciliation polling queries MUST use:

```sql
FOR UPDATE SKIP LOCKED
```

---

## FINAL QUERY

```sql
SELECT *
FROM payment_intents
WHERE status = 'PENDING'
ORDER BY updated_at ASC
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

---

## RATIONALE

Prevents:

* multi-worker collisions
* duplicate reconciliation execution
* race conditions
* backlog amplification

---

# 6. Timeout Ownership Model

## REQUIRED DOCUMENTATION

The orchestrator owns timeout enforcement.

PSP timeout behavior is NOT delegated to providers.

---

## FINAL RULE

If no PSP response is received within:

```yaml
psp.timeout-ms
```

then:

```text
1. mark attempt PENDING
2. persist timeout event
3. return 202 ACCEPTED
4. release request thread
5. rely on webhook/reconciliation
```

---

# 7. Reconciliation Escalation Policy

## REQUIRED ADDITION

PENDING cannot remain indefinite.

---

## FINAL POLICY

| Duration          | Action                  |
| ----------------- | ----------------------- |
| < 24h             | continue reconciliation |
| >= 24h unresolved | emit operational warning alert |
| >= 48h unresolved | emit critical escalation alert |

---

# 8. Circuit Breaker Mechanics

## REQUIRED CONFIGURATION

```yaml
circuit-breaker:
  failure-threshold: 5
  rolling-window-size: 20
  half-open-after-ms: 30000
  half-open-max-calls: 3
```

---

## REQUIRED BEHAVIOR

### CLOSED

Normal traffic.

### OPEN

Traffic blocked.

Requests routed to fallback provider.

### HALF_OPEN

Limited probe traffic allowed.

If successful:

```text
HALF_OPEN → CLOSED
```

If failed:

```text
HALF_OPEN → OPEN
```

---

# 9. Outbox Event Taxonomy

## REQUIRED EVENT CATALOG

| Event Type                 | Purpose                |
| -------------------------- | ---------------------- |
| PAYMENT_CREATED            | initial persistence    |
| ATTEMPT_STARTED            | PSP call initiated     |
| PAYMENT_AUTHORIZED         | authorization success  |
| PAYMENT_FAILED             | definitive failure     |
| PAYMENT_PENDING            | timeout ambiguity      |
| PROVIDER_TIMEOUT           | PSP timeout            |
| RETRY_SCHEDULED            | retry queued           |
| RETRY_EXECUTED             | retry started          |
| RECONCILIATION_RESOLVED    | pending resolved       |
| WEBHOOK_RECEIVED           | webhook ingestion      |
| NONCE_REPLAY_ATTEMPT       | replay attack detected |
| INVALID_SIGNATURE_RECEIVED | auth failure audit     |

---

# 10. Provider Failure Classification

## REQUIRED MATRIX

| Failure Type             | Immediate Retry | Failover | PENDING |
| ------------------------ | --------------- | -------- | ------- |
| connect timeout          | YES             | YES      | NO      |
| DNS failure              | YES             | YES      | NO      |
| TCP reset before request | YES             | YES      | NO      |
| read timeout             | NO              | NO       | YES     |
| PSP 5xx ambiguous        | NO              | NO       | YES     |
| issuer decline           | NO              | NO       | NO      |
| invalid token            | NO              | NO       | NO      |
| malformed request        | NO              | NO       | NO      |
| malformed PSP response   | NO              | NO       | YES     |

---

# 11. Merchant Configuration Assumptions

## REQUIRED DOCUMENTATION

Merchant onboarding/configuration is assumed external.

This system assumes merchant metadata exists in:

* external merchant service
  OR
* upstream configuration platform

---

## Merchant Validation Assumptions

The orchestrator may validate:

* merchant active state
* supported payment methods
* supported currencies
* provider routing eligibility

without owning merchant lifecycle management.

---

# 12. Clock Synchronization Assumptions

## REQUIRED ADDITION

Timestamp validation assumes:

```text
NTP-synchronized infrastructure
```

Allowed drift:

```text
±5 minutes
```

---

# 13. Logging and PII Rules

## REQUIRED SECURITY SECTION

The following MUST NEVER be logged:

* payment tokens
* provider secrets
* HMAC signatures
* raw authorization headers
* PSP credentials

---

## REQUIRED MASKING RULES

Sensitive fields must be:

```text
masked
truncated
hashed
```

before logging.

---

## EXAMPLE

```text
payment_token_reference=tok_****8fa1
```

---

# 14. Eventual Consistency Clarification

## REQUIRED SWAGGER NOTE

GET APIs are eventually consistent for:

```text
PENDING flows
```

because reconciliation/webhook processing is asynchronous.

---

## REQUIRED DESCRIPTION

A payment returned as:

```text
PENDING
```

may later transition to:

```text
AUTHORIZED
```

or:

```text
FAILED
```

without further client action.

---

# 15. Canonical JSON Hashing Clarification

## REQUIRED ADDITION

Request hashing MUST use canonicalized JSON.

Canonicalization rules:

* stable field ordering
* normalized whitespace
* deterministic serialization
* UTF-8 encoding

before:

```text
SHA-256 hashing
```

---

# 16. Optimistic Locking Requirements

## REQUIRED IMPLEMENTATION RULE

Mutable entities MUST use optimistic locking.

Add:

```java
@Version
private Long version;
```

for:

* PaymentIntent
* PaymentAttempt

---

## PURPOSE

Prevents:

* lost updates
* webhook/reconciliation races
* retry collision overwrites

---

# 17. Dead Letter Queue Strategy

## REQUIRED FAILURE TERMINATION

Permanent processing failures MUST terminate.

Never allow infinite retry loops.

---

## FINAL POLICY

| Component             | Max Retries  |
| --------------------- | ------------ |
| outbox publisher      | 10           |
| reconciliation worker | configurable |
| webhook processor     | 5            |

After threshold:

```text
status = FAILED
```

and:

```text
emit operational alert
```

---

# 18. Swagger Response Alignment

## REQUIRED RESPONSE MATRIX

| Scenario             | HTTP | Payment Status |
| -------------------- | ---- | -------------- |
| success              | 200  | AUTHORIZED     |
| definitive decline   | 200  | FAILED         |
| timeout ambiguity    | 202  | PENDING        |
| validation error     | 400  | N/A            |
| auth failure         | 401  | N/A            |
| idempotency conflict | 409  | N/A            |
| illegal transition   | 422  | N/A            |
| internal error       | 500  | N/A            |

---

# 19. README Architecture Diagram Update

## REQUIRED FLOW

```text
Client
  ↓
Validation Layer
  ↓
Idempotency Layer
  ↓
Persistence Transaction
(Intent + Attempt + Event + Outbox)
  ↓
COMMIT
  ↓
PSP Call
  ↓
┌─────────────────────┐
│ AUTHORIZED → 200    │
│ FAILED → 200        │
│ TIMEOUT → 202       │
└─────────────────────┘
  ↓
Webhook / Reconciliation
  ↓
Final Resolution
```

---

# 20. Final Architecture Positioning

## REQUIRED SUMMARY

This architecture prioritizes:

* correctness
* replay safety
* deterministic state transitions
* auditability
* operational recoverability
* controlled async recovery

over:

* premature distributed complexity
* aggressive failover
* low-value architectural abstraction
* unsafe retry behavior

The system intentionally treats:

```text
payment correctness
```

as more important than:

```text
minimal latency
```

because duplicate financial execution is more damaging than delayed resolution.

Which is the kind of sentence payment engineers write after enough production incidents erode their remaining optimism about humanity.
