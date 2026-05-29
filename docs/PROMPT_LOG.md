# PROMPT_LOG.md — AI-Assisted Development History

> This document records the verbatim prompts submitted for each development phase,
> the deliverables produced, and the validation outcome.
> It serves as an engineering audit trail for AI-assisted development sessions.

> [!IMPORTANT]
> This entire codebase was developed using the **free tier of [Antigravity](https://antigravity.dev)** —
> an agentic AI coding assistant by Google DeepMind.
> The primary model used throughout was **Gemini 3.5 Flash (Low)**, with occasional assistance from
> **Claude Sonnet 4.5** for specific phases. No paid compute tier was used at any point.

---

## How to Read This Log

Each phase entry contains:

- **Prompt** — The exact prompt submitted to the AI model, verbatim.
- **Deliverables Built** — The concrete files, classes, and configurations produced.
- **Validated** — The build or test command run and its observed outcome.

Phases are in chronological order. Each phase builds on prior phases without breaking existing contracts.

> [!NOTE]
> Several phases involved a two-step flow: a detailed specification prompt followed by a short confirmation post review of the implementation plan (e.g. *"continue"*, *"proceed"*). These confirmations carried no additional engineering information and have not been included in this log. Only the substantive specification prompts are recorded.

---

## Phase 0: Repository Bootstrap and Dependency Setup

**Prompt:**

```
You are building a production-grade payment orchestration system in Java 21 using Spring Boot 3.x.

You MUST follow:
- MASTER_CONTEXT.md
- ARCHITECTURE.md
- swagger.yaml
- README.md

Do not deviate from the documented architecture.

Your goal in this phase is ONLY repository scaffolding and infrastructure setup.

Tasks:
1. Create Maven project structure exactly as defined in MASTER_CONTEXT.md
2. Configure pom.xml with:
   - Spring Boot starter web
   - Spring Boot validation
   - Spring Data JPA
   - PostgreSQL driver
   - Redis starter
   - Flyway
   - Micrometer + Prometheus
   - Spring Boot actuator
   - JUnit 5
   - Mockito
   - Testcontainers
   - Jackson
   - Resilience4j
3. Configure application.yml, application-local.yml, application-test.yml
4. Configure Docker Compose for:
   - PostgreSQL
   - Redis
5. Configure Flyway
6. Configure base package structure
7. Configure logback-spring.xml with structured JSON logging
8. Configure GitHub Actions CI workflow:
   - mvn clean verify
9. Add .gitignore
10. Ensure project builds successfully

DO NOT:
- implement business logic
- create controllers
- create entities
- implement PSP logic

Deliverable:
Compilable bootstrapped project.
```

**Deliverables Built:**
- Maven wrapper, `.gitignore`, and `pom.xml` with all specified starter dependencies and version locks.
- `docker-compose.yml` for local PostgreSQL 15 and Redis 7.
- `src/main/resources/application.yml` (default/active local profiles), `application-local.yml`, and `application-test.yml` containing connection parameters and resource limits.
- `src/main/resources/logback-spring.xml` utilizing `LogstashEncoder` for structured JSON log formatting.
- `com.payments.orchestrator` base package structure.

**Validated:** `.\build.ps1 "clean compile"` — **BUILD SUCCESS**

---

## Phase 1: Database Schema and Flyway

**Prompt:**

```
Using the provided architecture documents and MASTER_CONTEXT.md:

Implement ONLY the persistence schema layer.

Tasks:
1. Create Flyway migrations for:
   - payment_intents
   - payment_attempts
   - payment_events
   - payment_idempotency
   - payment_outbox
2. Add:
   - indexes
   - constraints
   - foreign keys
3. Add optimistic locking columns where required
4. Ensure all enums are VARCHAR-based
5. Add cleanup indexes for outbox and idempotency TTL jobs
6. Validate schema consistency against swagger.yaml and MASTER_CONTEXT.md
7. Generate JPA entities:
   - PaymentIntent
   - PaymentAttempt
   - PaymentEvent
   - PaymentIdempotency
   - PaymentOutbox
8. Add repositories

Rules:
- payment_events is insert-only
- SUPERSEDED exists only for attempts
- No business logic yet
- No controllers yet

Deliverable:
Working Flyway migrations + entities + repositories.
```

**Deliverables Built:**
- Flyway SQL migrations (`V1__init_schema.sql` and `V2__add_manual_review_status.sql`) in `src/main/resources/db/migration/`.
- Indexes created for `idempotency_key` (unique), `merchant_order_id` (unique check), partial index on `payment_outbox` (`status = 'PENDING'`), and foreign key columns.
- JPA entities created: `PaymentIntent`, `PaymentAttempt`, `PaymentEvent`, `PaymentIdempotency`, and `PaymentOutbox` with dynamic mapping, JPA `@Version` optimistic locking, and `@Enumerated(EnumType.STRING)`.
- Core Spring Data JPA repositories: `PaymentIntentRepository`, `PaymentAttemptRepository`, `PaymentEventRepository`, `PaymentIdempotencyRepository`, and `PaymentOutboxRepository`.

**Validated:** `.\build.ps1 "clean test-compile"` — **BUILD SUCCESS**

---

## Phase 2: API Contracts and DTO Layer

**Prompt:**

```
Implement ONLY the API DTO and contract layer.

Tasks:
1. Generate request/response DTOs from swagger.yaml
2. Implement:
   - CreatePaymentRequest
   - CreatePaymentResponse
   - PaymentStatusResponse
   - ErrorResponse
3. Add Jakarta Bean Validation annotations
4. Add enum validation
5. Add OpenAPI annotations
6. Ensure:
   - field names exactly match swagger.yaml
   - response examples match architecture docs
7. Implement global exception response models
8. Add validation groups if useful

DO NOT:
- implement controllers
- implement services
- implement PSP logic
- implement DB writes

Focus ONLY on:
- contracts
- validation
- serialization consistency

Deliverable:
Complete DTO and API schema layer.
```

**Deliverables Built:**
- Custom, validation-ready DTO classes under `com.payments.orchestrator.dto`:
  - `CreatePaymentRequest.java` (using `@NotNull`, `@Size`, `@Pattern` UUID validations).
  - `CreatePaymentResponse.java`.
  - `PaymentStatusResponse.java` and internal sub-elements.
  - `ErrorResponse.java` structured with error envelope fields (`error_code`, `message`, `timestamp`).
- Added Jakarta validation constraints and enum value serialization helpers.
- Exception handling response structures built to represent standard contract models.

**Validated:** `.\build.ps1 "clean compile"` — **BUILD SUCCESS**

---

## Phase 3: Security Layer (HMAC, Nonce, Timestamp)

**Prompt:**

```
Implement the security validation layer.

Requirements from MASTER_CONTEXT.md are mandatory.

Tasks:
1. Implement:
   - HMAC signature validator
   - Nonce validator
   - Timestamp validator
   - Merchant secret resolver abstraction
2. Implement canonical request builder:
   METHOD + PATH + SHA256(body) + timestamp + nonce + merchant_id
3. Add Redis-backed nonce replay protection
4. Implement nonce TTL logic
5. Implement grace-period key rotation validation
6. Implement:
   - SecurityFilter
   - Request wrapping for body hashing
7. Add MDC propagation:
   - request_id
   - correlation_id
   - internal_request_id
8. Implement audit event generation for:
   - nonce replay
   - invalid signature
9. Add unit tests for:
   - valid signature
   - invalid signature
   - expired timestamp
   - nonce reuse
   - grace-period keys

DO NOT:
- implement PSP logic
- implement orchestration
- implement payment lifecycle

Deliverable:
Fully working security validation layer.
```

**Deliverables Built:**
- `SecurityUtils.java` for computing canonical HMAC-SHA256 signatures, request body SHA-256 hashes, and canonical payloads.
- `CachedBodyHttpServletRequest.java` enabling request stream caching for body hashing in `SecurityFilter.java`.
- `SecurityValidator.java` incorporating Redis-backed atomic nonce checking (`SETNX`), clock drift timestamp checking, and merchant secret resolving.
- `SecurityFilter.java` executing validation on direct payment API paths, establishing MDC context (`request_id`, `correlation_id`, `internal_request_id`), and returning structured JSON security declines.
- Verification tests in `SecurityTests.java` covering all security check bounds.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.security.*"` — **ALL TESTS PASS**

---

## Phase 4: Idempotency Layer

**Prompt:**

```
Implement payment idempotency handling.

Tasks:
1. Implement request hashing using canonicalized JSON
2. Implement idempotency lookup service
3. Implement:
   - same key + same payload => cached response
   - same key + different payload => 409
4. Persist responses into payment_idempotency table
5. Implement TTL expiry handling
6. Add scheduled cleanup job
7. Ensure idempotency occurs:
   AFTER auth
   BEFORE business validation
8. Add tests for:
   - duplicate requests
   - conflict requests
   - expired keys
   - replayed requests

Rules:
- request hash must use deterministic JSON serialization
- never hash raw object toString()

Deliverable:
Production-grade idempotency implementation.
```

**Deliverables Built:**
- `IdempotencyServiceImpl.java` implementing SHA-256 canonical hashing (sorting JSON keys deterministically using Jackson ObjectMapper).
- Idempotency lookup logic evaluating active keys, returning cached HTTP response parameters on exact match, or raising `IdempotencyConflictException` (resulting in `HTTP 409`) on payload changes.
- `IdempotencyPruningScheduler.java` scheduling off-peak purging of records older than 24 hours.
- Automated tests in `IdempotencyTests.java` asserting strict compliance.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.service.IdempotencyServiceImplTest"` — **ALL TESTS PASS**

---

## Phase 5: Core Domain and State Machine

**Prompt:**

```
Implement the payment lifecycle state machine.

Tasks:
1. Implement:
   - PaymentStatus enum
   - AttemptStatus enum
2. Implement explicit state transition validator
3. Enforce:
   CREATED -> PROCESSING
   PROCESSING -> AUTHORIZED | FAILED | PENDING
   PENDING -> AUTHORIZED | FAILED
4. Prevent invalid transitions
5. Implement IllegalStateTransitionException
6. Implement immutable terminal states
7. Implement webhook/reconciliation precedence rules
8. Add exhaustive unit tests for all transitions

Rules:
- AUTHORIZED and FAILED are terminal
- contradictory webhook updates must be ignored and audit logged
- SUPERSEDED applies ONLY to attempts

Deliverable:
Fully validated lifecycle state machine.
```

**Deliverables Built:**
- Enums: `PaymentStatus` (`CREATED`, `PROCESSING`, `AUTHORIZED`, `FAILED`, `PENDING`, `MANUAL_REVIEW`) and `AttemptStatus` (`INITIATED`, `AUTHORIZED`, `FAILED`, `PENDING`, `SUPERSEDED`).
- `PaymentLifecycleValidator.java` acting as the authoritative engine enforcing legal state path transitions and preserving immutable terminal states (`AUTHORIZED`, `FAILED`).
- Defined custom `IllegalStateTransitionException.java` leading to structured `HTTP 422 Unprocessable Entity` responses.
- Exhaustive validation transition scenarios in `PaymentLifecycleValidatorTest.java`.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.state.*"` — **ALL TESTS PASS**

---

## Phase 6: Payment Persistence Transaction Boundary

**Prompt:**

```
Implement the core persistence transaction flow.

Tasks:
1. Implement service that:
   - creates payment intent
   - creates payment attempt
   - creates payment event
   - creates outbox event
2. Ensure ALL inserts occur in ONE transaction
3. PSP calls MUST NOT happen inside transaction
4. Implement:
   @Transactional service boundary
5. Add:
   - merchant order uniqueness checks
   - correlation persistence
   - request_id persistence
6. Add tests validating rollback behavior

Rules:
- no PSP calls before commit
- persistence correctness is highest priority

Deliverable:
Correct transactional persistence implementation.
```

**Deliverables Built:**
- `PaymentOrchestrationServiceImpl.java` implementing the `@Transactional` boundary for initiating transactions.
- Writes four dependent rows (`payment_intents`, `payment_attempts`, `payment_events`, `payment_outbox`) in a single SQL transactional commit.
- Performs checks ensuring unique `merchant_order_id` values and logs exceptions.
- Unit tests in `PaymentPersistenceTests.java` asserting transactional rollbacks upon validation failures.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.persistence.*"` — **ALL TESTS PASS**

---

## Phase 7: PSP Routing and Connector Abstractions

**Prompt:**

```
Implement PSP abstraction and routing.

Tasks:
1. Create:
   - PspConnector interface
   - RoutingEngine interface
   - PaymentInstrumentResolver interface
2. Implement:
   - PspAConnector (stub)
   - PspBConnector (stub)
3. Add configurable stub modes:
   SUCCESS
   FAILURE
   TIMEOUT
4. Implement provider routing rules
5. Implement provider idempotency key generation
6. Add provider response models
7. Implement provider timeout ownership
8. Add circuit breaker integration using Resilience4j

Rules:
- no raw credentials
- timeout => PENDING
- no immediate failover on ambiguous timeout

Deliverable:
Working PSP abstraction layer.
```

**Deliverables Built:**
- Abstractions: `PspConnector.java`, `RoutingEngine.java`, and `PaymentInstrumentResolver.java`.
- Connectors: `PspAConnector.java` and `PspBConnector.java` with configurable modes (`SUCCESS`, `FAILURE`, `TIMEOUT`) in `application.yml` and circuit breaker annotations (`@CircuitBreaker`).
- `RoutingEngineImpl.java` enforcing CARD payments $\rightarrow$ PSP A, UPI payments $\rightarrow$ PSP B.
- Provider response mappings and unit tests in `PspRoutingTests.java`.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.routing.*"` — **ALL TESTS PASS**

---

## Phase 8: Synchronous Authorization Flow

**Prompt:**

```
Implement synchronous payment authorization orchestration.

Tasks:
1. Implement create payment orchestration flow
2. Execute:
   validation -> auth -> idempotency -> persistence -> PSP call
3. Handle:
   - AUTHORIZED => HTTP 200
   - FAILED => HTTP 200
   - TIMEOUT => HTTP 202 + PENDING
4. Persist:
   - provider reference
   - attempt result
   - events
5. Implement immediate retry policy ONLY for safe transport failures
6. Add provider failure classification matrix
7. Add metrics:
   - PSP latency
   - timeout count
   - success/failure counters

Rules:
- read timeout is ambiguous
- ambiguous timeout MUST NOT failover immediately

Deliverable:
End-to-end synchronous payment flow.
```

**Deliverables Built:**
- `PaymentOrchestrationFlowManagerImpl.java` coordinating the sequential execution: Security check $\rightarrow$ Idempotency $\rightarrow$ DB Persistence $\rightarrow$ Out-of-transaction PSP Call $\rightarrow$ Status Resolution.
- Classifies failures based on `PspErrorClassifier.java` to support immediate retries for safe connection errors only.
- Registers Micrometer timers and counters for latencies, timeouts, and success statuses.
- End-to-end routing flow tested in `SynchronousAuthFlowTests.java`.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.flow.*"` — **ALL TESTS PASS**

---

## Phase 9: Outbox Publisher and Queue Abstraction

**Prompt:**

```
Implement outbox publishing infrastructure.

Tasks:
1. Implement:
   - EventPublisher interface
   - KafkaEventPublisher
   - InMemoryEventPublisher
2. Implement outbox poller
3. Use:
   FOR UPDATE SKIP LOCKED
4. Implement:
   - retry_count
   - publish retries
   - cleanup retention
5. Add metrics:
   - outbox lag
   - publish failures
6. Add dead-letter handling strategy

Rules:
- outbox exists for async recovery and observability
- business logic must NEVER read from outbox

Deliverable:
Reliable outbox publishing system.
```

**Deliverables Built:**
- Abstraction `EventPublisher.java` with two implementations: `KafkaEventPublisher.java` (production) and `InMemoryEventPublisher.java` (testing/local profiles).
- `OutboxPublisherWorker.java` running on a 1500ms schedule using `SELECT FOR UPDATE SKIP LOCKED` to fetch pending outbox events.
- Tracks `retry_count` per event, routing events to a dead-letter state after 5 consecutive failures.
- Unit and batch-execution tests in `OutboxPublisherWorkerTest.java`.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.worker.OutboxPublisherWorkerTest"` — **ALL TESTS PASS**

---

## Phase 10: Reconciliation Worker

**Prompt:**

```
Implement reconciliation processing.

Tasks:
1. Poll PENDING intents
2. Query PSP status APIs
3. Resolve:
   - AUTHORIZED
   - FAILED
4. Persist reconciliation events
5. Add reconciliation retry policies
6. Add escalation policy:
   - >24h alert
   - >48h MANUAL_REVIEW
7. Implement:
   FOR UPDATE SKIP LOCKED
8. Add metrics:
   - reconciliation backlog
   - reconciliation resolutions

Rules:
- reconciliation cannot violate terminal states
- contradictory updates must be ignored and audit logged

Deliverable:
Production-grade reconciliation worker.
```

**Deliverables Built:**
- `ReconciliationServiceImpl.java` implementing the PSP status query check, lifecycle status update, and alert dispatch.
- `ReconciliationWorker.java` polling PENDING payments using `FOR UPDATE SKIP LOCKED` and applying metrics (`payment.reconciliation.backlog`).
- Escalation rules: flags alert at $>24$ hours; transitions status to `MANUAL_REVIEW` at $>48$ hours.
- Verified terminal state immutability checks in `ReconciliationWorkerTest.java`.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.worker.ReconciliationWorkerTest"` — **ALL TESTS PASS**

---

## Phase 11: Retry Worker

**Prompt:**

```
Implement async retry processing.

Tasks:
1. Retry ONLY when:
   - reconciliation confirms no authorization occurred
   - provider confirms retry safety
2. Create new PaymentAttempt row per retry
3. Mark old attempts SUPERSEDED on success
4. Implement exponential backoff
5. Enforce max retry count
6. Add metrics and audit events

Rules:
- retries must never risk duplicate charge
- retries are operationally conservative

Deliverable:
Safe retry execution framework.
```

**Deliverables Built:**
- `RetryServiceImpl.java` managing transaction-isolated state splits (TX-1: transition old attempt status to `SUPERSEDED` $\rightarrow$ PSP execution $\rightarrow$ TX-2: resolve outcomes).
- `RetryWorker.java` scheduled to poll candidates using exponential backoff:
  $$\text{backoff} = \text{base} \times 2^{\text{retry\_count}}$$
- Implements safety checks (does not retry if original attempt authorized card).
- Tests in `RetryWorkerTest.java` verifying attempt superseding.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.worker.RetryWorkerTest"` — **ALL TESTS PASS**

---

## Phase 12: Webhook Processing

**Prompt:**

```
Implement PSP webhook ingestion.

Tasks:
1. Implement webhook controller
2. Validate webhook signatures
3. Deduplicate event_id
4. Resolve provider references
5. Apply state transitions
6. Persist webhook events
7. Add audit logging
8. Add tests:
   - duplicate webhook
   - invalid signature
   - contradictory update
   - replay webhook

Rules:
- terminal states immutable
- duplicate webhooks must be idempotent

Deliverable:
Reliable webhook processing flow.
```

**Deliverables Built:**
- `WebhookController.java` exposing `POST /api/v1/payments-orchestration/webhooks/{provider}` paths.
- `WebhookServiceImpl.java` validating provider signatures (HMAC-SHA256), checking `processed_webhooks` for unique `event_id` records, and verifying parent attempt references.
- Webhook test cases in `WebhookIngestionTests.java` asserting signature exclusions, deduplication caches, and blocked `FAILED` $\rightarrow$ `AUTHORIZED` transition overrides.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.webhook.*"` — **ALL TESTS PASS**

---

## Phase 13: Observability and Operational Hardening

**Prompt:**

```
Implement observability and operational hardening.

Tasks:
1. Implement structured JSON logging
2. Implement all Micrometer metrics
3. Add Prometheus integration
4. Add health indicators:
   - DB
   - Redis
   - Kafka
5. Add MDC propagation everywhere
6. Add sensitive data masking
7. Add request tracing consistency
8. Add operational alerts

Deliverable:
Production-grade observability stack.
```

**Deliverables Built:**
- Custom Actuator indicators: `DatabaseHealthIndicator.java` (Timer-based latency probes), `RedisHealthIndicator.java` (exposing engine info parameters), and `KafkaHealthIndicator.java`.
- `MaskingUtils.java` implementing standard character masks for keys, signatures, and secrets.
- MDC context propagation (`request_id`, `correlation_id`, `internal_request_id`, `intent_id`) mapped throughout all three worker execution threads.
- Robust tests in `ObservabilityHardeningTests.java`.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.observability.*"` — **ALL TESTS PASS**

---

## Phase 14: Integration Tests

**Prompt:**

```
Implement integration tests using Testcontainers.

Required scenarios:
1. Successful authorization
2. Definitive PSP failure
3. Timeout -> PENDING
4. Reconciliation resolution
5. Idempotency replay
6. Idempotency conflict
7. Nonce replay
8. Invalid signature
9. Webhook dedupe
10. Retry flow
11. Outbox publish
12. Illegal transitions

Rules:
- tests must use real Postgres + Redis containers
- avoid mocking persistence

Deliverable:
Comprehensive integration test suite.
```

**Deliverables Built:**
- `ContainerIntegrationTests.java` incorporating Testcontainers to boot real `postgres:15-alpine` and `redis:7-alpine` container environments.
- Implements 15 exhaustive integration test scenarios matching the exact transaction state paths, idempotency replays, nonce checks, duplicate webhooks, and worker recovery behaviors under dynamic Spring properties mapping.

**Validated:** `.\build.ps1 "test --tests com.payments.orchestrator.integration.ContainerIntegrationTests"` — **ALL INTEGRATION TESTS PASS**

---

## Phase 15: Documentation Finalization

**Prompt:**

```
Finalize repository documentation.

Tasks:
1. Update README.md
2. Generate architecture diagrams
3. Complete:
   - FUNCTIONAL_REQUIREMENTS.md
   - NON_FUNCTIONAL_REQUIREMENTS.md
   - TEST_CASES.md
   - PERFORMANCE.md
   - PROMPT_LOG.md
4. Validate consistency across:
   - README
   - swagger.yaml
   - MASTER_CONTEXT.md
   - ARCHITECTURE.md
5. Add setup instructions
6. Add local development guide
7. Add API examples

Deliverable:
Final production-quality repository documentation.
```

**Deliverables Built:**
- `README.md` at root directory containing structural diagramming, local setup guide, and configuration mappings.
- `ARCHITECTURE.md` including state transitions, entity relationship details, and ADR logs.
- Folder `/docs` filled with:
  - `FUNCTIONAL_REQUIREMENTS.md` (FR-001 through FR-060 specifications).
  - `NON_FUNCTIONAL_REQUIREMENTS.md` (NFR requirements covering performance, reliability, scalability).
  - `TEST_CASES.md` (detailed verification suites).
  - `PERFORMANCE.md` (latency budgeting and optimization details).
  - `PROMPT_LOG.md` (this historic record).

**Validated:** All markdown links and cross-references verified.

---

## Phase 16: Naming Clarity Refactoring & Document Alignment

**Prompt:**

> every variable name we use should be fully clarified, no config instead of confiuguration , stuff like that, same applies for class names, and even variables inside methods should make sense, no it, and short variable names, use ones that explain the role of that variable in a method, I want this addressed across the app, and the required updates to be made to the docs where necessary

**Deliverables Built:**
- Applied precise refactoring across 14 Java source files to replace abbreviated, opaque, or short variable names (including catch block exception variables, loop index variables, and local parameters/fields) with fully descriptive, role-based names:
  - `SecurityUtils.java`
  - `DatabaseHealthIndicator.java`
  - `RedisHealthIndicator.java`
  - `SecurityFilter.java`
  - `SecurityValidator.java`
  - `IdempotencyServiceImpl.java`
  - `FlowManagerImpl.java`
  - `WebhookServiceImpl.java`
  - `RetryServiceImpl.java`
  - `ReconciliationWorker.java`
  - `RetryWorker.java`
  - `OutboxPublisherWorker.java`
  - `IdempotencyPruningScheduler.java`
  - `GlobalExceptionHandler.java`
  - `OutboxPruningScheduler.java`
- Enforced naming standard: e.g., catch variable `e` → `databaseAccessException` or `parseException`, `byte b` → `digestByte`, `reqId` → `resolvedRequestId`.
- Updated `BUG_FIX_TRACKING.md` (entries 8–11) to document naming clarity refactoring and newly resolved bug cases (idempotency status classification, optimistic lock retries, and outbox failure dead-letter triggers).
- Aligned documentation across `PROMPT_LOG.md` and walkthrough files to maintain perfect consistency with the refactored identifiers.

**Validated:** `powershell -ExecutionPolicy Bypass -File .\build.ps1 "clean compile"` — **BUILD SUCCESS** (all 89 Java sources compile cleanly)
