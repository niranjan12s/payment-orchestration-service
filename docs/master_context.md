# Payment Orchestration System — Master Context Document

> **Instructions for the AI coding agent**
>
> Load this document at the beginning of every development session.
> This document is the authoritative source of truth for:
>
> - Architecture
> - Domain modeling
> - Transaction semantics
> - State management
> - Security rules
> - Retry behavior
> - Persistence guarantees
> - API contracts
> - Worker behavior
> - Testing expectations
> - Operational constraints
>
> Phase prompts may ADD scope.
> They must NEVER silently override this document.
>
> Any architectural deviation requires explicit human approval referencing
> the exact section being modified.

---

# 1. Project Identity

| Field | Value |
|---|---|
| Project Name | `payment-orchestrator` |
| Language | Java 21 |
| Framework | Spring Boot 3.x |
| Build Tool | Maven |
| Database | PostgreSQL 15 |
| Cache / Replay Store | Redis 7 |
| Queue Abstraction | Kafka (prod) / InMemoryPublisher (local/test) |
| Migration Tool | Flyway |
| Testing | JUnit 5, Mockito, Testcontainers |
| API Contract | OpenAPI 3.0 |
| Containerization | Docker + Docker Compose |

Root package:

```text
com.payments.orchestrator
```

---

# 2. Scope Boundary

## IN SCOPE

- Payment authorization orchestration
- PSP routing
- Retry handling
- Failover handling
- Idempotency enforcement
- Reconciliation
- Outbox pattern
- Webhook processing
- Observability
- Security validation
- Async recovery workflows

## OUT OF SCOPE

Do NOT implement:

- Settlement engines
- Ledger systems
- Refunds
- Capture flows
- Acquirer infrastructure
- Card vault/tokenization systems
- Real card credential storage
- Multi-region distributed consensus
- Event sourcing framework

---

# 3. High-Level Architecture

```text
Client
   ↓
API Layer
   ↓
Validation Pipeline
   ↓
Persistence Transaction
(Intent + Attempt + Event + Outbox)
   ↓
COMMIT
   ↓
PSP Authorization Call
   ↓
┌───────────────────────────────┐
│ Success → AUTHORIZED          │
│ Failure → FAILED              │
│ Timeout → PENDING             │
└───────────────────────────────┘
   ↓
Async Recovery
(Reconciliation / Webhooks / Retry Worker)
```

---

# 4. Architectural Principles

## 4.1 Persist Before PSP Call

The single most important correctness guarantee.

```text
Persist state first.
Call PSP only after commit succeeds.
```

Never violate this rule.

Incorrect order causes:
- orphaned authorizations
- duplicate charges
- reconciliation inconsistency

---

## 4.2 Timeout Does NOT Mean Failure

A PSP timeout is ambiguous.

The PSP may:
- have processed successfully
- have authorized funds
- have failed only on response transmission

Therefore:

```text
Timeout → PENDING
```

Never:
```text
Timeout → FAILED
```

---

## 4.3 AUTHORIZED ≠ SETTLED

AUTHORIZED:
- issuer reserved funds

SETTLED:
- money movement finalized through network rails

Settlement is OUT OF SCOPE.

---

## 4.4 Retries Must Be Safe

Retries are allowed ONLY when:
- provider confirms request was NOT processed
OR
- reconciliation proves authorization never occurred

Never blindly retry ambiguous operations.

---

## 4.5 Events Are Immutable

`payment_events` is append-only.

No UPDATE operations allowed.

---

# 5. Payment Lifecycle

## Intent Lifecycle

```text
CREATED
   ↓
PROCESSING
   ↓
AUTHORIZED
```

Failure paths:

```text
PROCESSING → FAILED
PROCESSING → PENDING
PENDING → AUTHORIZED
PENDING → FAILED
```

---

## Attempt Lifecycle

Each retry creates a NEW attempt row.

Old attempts become:

```text
SUPERSEDED
```

Never reuse attempt rows.

---

# 6. State Transition Rules

| From | Allowed |
|---|---|
| CREATED | PROCESSING |
| PROCESSING | AUTHORIZED, FAILED, PENDING |
| PENDING | AUTHORIZED, FAILED |
| AUTHORIZED | terminal |
| FAILED | terminal |
| SUPERSEDED | terminal |

Illegal transitions must throw:

```text
IllegalStateTransitionException
```

Mapped to:

```http
422 ILLEGAL_STATE_TRANSITION
```

---

# 7. Database Design

---

# 7.1 payment_intents

Represents business-level payment lifecycle.

```sql
CREATE TABLE payment_intents (
    intent_id UUID PRIMARY KEY,

    merchant_id UUID NOT NULL,
    merchant_order_id VARCHAR(255) NOT NULL,

    correlation_id VARCHAR(255),
    request_id VARCHAR(255),

    idempotency_key VARCHAR(255),

    transaction_currency_code VARCHAR(10),
    transaction_amount NUMERIC(18,2),

    settlement_currency_code VARCHAR(10),
    settlement_amount NUMERIC(18,2),

    status VARCHAR(50) NOT NULL
        CHECK (status IN (
            'CREATED',
            'PROCESSING',
            'AUTHORIZED',
            'FAILED',
            'PENDING'
        )),

    final_attempt_id UUID,

    version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_merchant_order
        UNIQUE (merchant_id, merchant_order_id)
);
```

---

# 7.2 payment_attempts

Operational execution tracking.

```sql
CREATE TABLE payment_attempts (
    attempt_id UUID PRIMARY KEY,

    intent_id UUID NOT NULL
        REFERENCES payment_intents(intent_id),

    correlation_id VARCHAR(255),
    request_id VARCHAR(255),

    provider_name VARCHAR(100) NOT NULL,

    provider_reference VARCHAR(255),

    payment_method_type VARCHAR(50) NOT NULL,

    payment_token_reference VARCHAR(255) NOT NULL,

    status VARCHAR(50) NOT NULL
        CHECK (status IN (
            'PROCESSING',
            'AUTHORIZED',
            'FAILED',
            'PENDING',
            'SUPERSEDED'
        )),

    retry_count INT NOT NULL DEFAULT 0,

    error_code VARCHAR(100),
    error_message TEXT,

    version BIGINT NOT NULL DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```

---

# 7.3 payment_events

Immutable audit trail.

```sql
CREATE TABLE payment_events (
    event_id UUID PRIMARY KEY,

    intent_id UUID NOT NULL,

    attempt_id UUID,

    correlation_id VARCHAR(255),

    event_type VARCHAR(100) NOT NULL,

    event_payload JSONB,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```

## IMPORTANT

`event_payload` MUST contain:
- metadata
- compact audit information

It must NOT contain:
- raw card data
- full PSP payload dumps
- sensitive credentials
- massive unbounded JSON

Large provider payloads belong in:
- object storage
OR
- dedicated archival table

---

# 7.4 payment_idempotency

```sql
CREATE TABLE payment_idempotency (
    id BIGSERIAL PRIMARY KEY,

    idempotency_key VARCHAR(255) NOT NULL UNIQUE,

    request_hash VARCHAR(512) NOT NULL,

    response_payload JSONB NOT NULL,

    status VARCHAR(50) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
```

TTL:
```text
24 hours
```

Expired rows cleaned by scheduled job.

---

# 7.5 payment_outbox

```sql
CREATE TABLE payment_outbox (
    outbox_id UUID PRIMARY KEY,

    aggregate_id UUID NOT NULL,

    aggregate_type VARCHAR(50) NOT NULL,

    correlation_id VARCHAR(255),

    event_type VARCHAR(100) NOT NULL,

    payload JSONB NOT NULL,

    status VARCHAR(50) NOT NULL
        CHECK (status IN (
            'PENDING',
            'PROCESSED',
            'FAILED'
        )),

    retry_count INT NOT NULL DEFAULT 0,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    processed_at TIMESTAMP WITH TIME ZONE
);
```

---

# 7.6 processed_webhooks

Dedicated webhook deduplication table.

```sql
CREATE TABLE processed_webhooks (
    id BIGSERIAL PRIMARY KEY,

    provider_name VARCHAR(100) NOT NULL,

    provider_event_id VARCHAR(255) NOT NULL,

    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_provider_event
        UNIQUE(provider_name, provider_event_id)
);
```

---

# 7.7 Required Indexes

```sql
CREATE INDEX idx_intent_status
ON payment_intents(status);

CREATE INDEX idx_attempt_intent
ON payment_attempts(intent_id);

CREATE INDEX idx_attempt_provider_reference
ON payment_attempts(provider_reference);

CREATE INDEX idx_outbox_pending
ON payment_outbox(status, created_at);

CREATE INDEX idx_events_intent
ON payment_events(intent_id);
```

---

# 8. Concurrency Strategy

Concurrency is expected from:
- webhook processor
- reconciliation worker
- retry worker

To prevent lost updates:

## REQUIRED

Use:
- optimistic locking via `version`
OR
- `SELECT FOR UPDATE`

on mutable entities.

Never allow:
```text
last write wins
```

behavior.

---

# 9. Transaction Boundary

```text
BEGIN TRANSACTION

INSERT payment_intent
INSERT payment_attempt
INSERT payment_event
INSERT payment_outbox

COMMIT
```

PSP CALL HAPPENS ONLY AFTER COMMIT.

Never inside transaction boundary.

---

# 10. API Contract

Base path:

```text
/api/v1/payments-orchestration
```

---

# 10.1 Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/payments` | Create payment |
| GET | `/payments/{intent_id}` | Full payment detail |
| GET | `/payments/{intent_id}/status` | Lightweight status |
| POST | `/webhooks/{provider}` | PSP callback |

---

# 10.2 Headers

| Header | Required |
|---|---|
| X-Request-Id | YES |
| X-Correlation-Id | OPTIONAL |
| X-Timestamp | YES |
| X-Nonce | YES |
| X-Signature | YES |
| Idempotency-Key | YES |

---

# 10.3 Validation Order

Strict ordering:

```text
1. Schema validation
2. Authentication validation
3. Replay protection
4. Idempotency validation
5. Business validation
6. Persistence
7. PSP call
```

Fail fast.

---

# 10.4 Idempotency Rules

| Scenario | Outcome |
|---|---|
| same key + same canonical payload | cached response |
| same key + different payload | 409 |
| expired key | treated as new |

---

# 10.5 Request Hashing

DO NOT hash raw JSON bytes directly.

JSON must first be canonicalized:
- sorted fields
- normalized formatting
- deterministic serialization

Then:
```text
SHA-256(canonical_json)
```

This prevents false idempotency conflicts.

Because:

```json
{"a":1,"b":2}
```

and

```json
{"b":2,"a":1}
```

must be considered identical.

Humanity invented distributed systems and still cannot agree on object field ordering. Remarkable species.

---

# 11. Security

---

# 11.1 HMAC Signature

Canonical string:

```text
HTTP_METHOD
REQUEST_PATH
SHA256(canonical_body)
X-Timestamp
X-Nonce
merchant_id
```

Signature:

```text
Base64(HMAC_SHA256(secret, canonical_string))
```

---

# 11.2 Key Rotation

Validation logic:

```text
1. Validate against active key
2. If fail:
   validate against grace-period key
3. If both fail:
   reject request
```

Secrets:
- KMS in prod
- in-memory store in local/test

Never store secrets in:
- application.yml
- git
- plain environment files

---

# 11.3 Replay Protection

Redis key:

```text
nonce:{merchant_id}:{nonce}
```

TTL:
```text
10 minutes
```

Reuse:
```http
401 NONCE_REUSED
```

Every replay attempt must be audit logged.

---

# 12. Domain Model

---

# 12.1 PaymentIntent

Business-level payment state.

Source of truth for:
- GET payment
- payment status
- merchant-facing lifecycle

---

# 12.2 PaymentAttempt

Operational execution state.

Each:
- retry
- reconciliation retry
- failover execution

creates NEW row.

---

# 12.3 PaymentEvent

Immutable audit log.

Insert-only.

---

# 12.4 PaymentOutbox

Durable event publication buffer.

Used ONLY for:
- async publication
- recovery workflows

Never queried by business APIs.

---

# 13. PSP Routing

```text
CARD → PSP_A
UPI  → PSP_B
```

Routing factors:
- payment method
- provider health
- circuit breaker state
- configured weights

---

# 14. Retry Strategy

---

# 14.1 Immediate Retry

Allowed ONLY for:
- connection reset
- connect timeout
- transport-level pre-processing failure

Never for:
- read timeout
- ambiguous PSP timeout

---

# 14.2 Ambiguous Timeout

```text
Timeout → PENDING
```

No immediate failover.

Double-charge prevention is more important than low latency heroics.

---

# 14.3 Retry Worker

Triggered ONLY when:
- reconciliation confirms no authorization
AND
- provider explicitly confirms retry-safe state

Retry-safe examples:
- provider says NOT_FOUND
- request never reached processor
- idempotency lookup absent

Never retry ambiguous state blindly.

---

# 15. Async Workers

---

# 15.1 Outbox Publisher

Runs every:
```text
1500ms
```

Uses:

```sql
SELECT *
FROM payment_outbox
WHERE status='PENDING'
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED
```

---

# 15.2 Reconciliation Worker

Polls:
```text
PENDING intents
```

Queries PSP status.

Possible outcomes:
- AUTHORIZED
- FAILED
- still unknown

---

# 15.3 Retry Worker

Executes safe retries only.

Exponential backoff:

```text
base * 2^retry_count
```

capped at:
```text
max_backoff_ms
```

---

# 15.4 Webhook Processor

Responsibilities:
- validate signature
- deduplicate
- correlate attempt
- apply transition
- persist event

---

# 16. Dead Letter Strategy

Permanent failures must not loop forever.

Applies to:
- outbox failures
- malformed webhooks
- poison reconciliation records

After max retries:
```text
FAILED
```

and alert emitted.

---

# 17. Observability

Every log line MUST contain:

```text
request_id
correlation_id
internal_request_id
intent_id
attempt_id
merchant_id
provider_name
```

---

# 18. Metrics

Required metrics:

- authorization success
- authorization failure
- authorization pending
- PSP latency
- PSP timeout
- retry count
- reconciliation backlog
- outbox lag
- circuit breaker state

---

# 19. Circuit Breaker Policy

Must define:

```yaml
failure-threshold
rolling-window-size
half-open-after-ms
```

Behavior:
- OPEN → traffic blocked
- HALF_OPEN → limited probe traffic
- CLOSED → normal routing

---

# 20. Isolation Level Assumptions

Default isolation:
```text
READ COMMITTED
```

Reason:
- avoids unnecessary contention
- acceptable for orchestration semantics
- optimistic locking handles concurrency safety

---

# 21. Assumptions & Constraints

The architecture assumes:

- single-region deployment
- PostgreSQL primary-write topology
- eventual consistency acceptable for reconciliation
- Redis acceptable for replay protection
- PSPs support idempotent provider references
- merchant throughput moderate (<500 TPS expected)
- reconciliation SLA non-real-time
- Kafka optional in local/test

---

# 22. Profiles

| Profile | Purpose |
|---|---|
| local | developer execution |
| test | integration testing |
| dev | shared development |
| prod | production |

---

# 23. Non-Negotiable Rules

1. No PSP call before DB commit.
2. Timeout = PENDING.
3. No immediate failover on ambiguous timeout.
4. payment_events is append-only.
5. Never expose raw credentials.
6. X-Request-Id missing → reject immediately.
7. Replay attempts always audit logged.
8. PSP calls never inside transaction.
9. All external systems accessed via interfaces.
10. No magic numbers in code.
11. AUTHORIZED ≠ SETTLED.
12. Retries require explicit retry safety.
13. Mutable rows require concurrency protection.
14. Canonical JSON hashing required for idempotency.
15. Poison records must terminate into FAILED/DLQ flow.

---

# 24. Repository Structure

```text
payment-orchestrator/
├── README.md
├── docker-compose.yml
├── pom.xml
├── docs/
│   ├── architecture.md
│   ├── master_context.md
│   ├── reconciliation.md
│   ├── swagger.yaml
│   └── ...
├── src/
└── .github/
```