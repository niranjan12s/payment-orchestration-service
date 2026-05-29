# Non-Functional Requirements — Payment Orchestration Service

**Version:** 1.0  
**Last Updated:** 2026-05-27  
**Status:** Baseline  
**Scope:** `payment-orchestrator` microservice — authorization lifecycle only  
**Stack:** Java 21 / Spring Boot 3.3 · PostgreSQL 15 · Redis 7 · Kafka  

> [!IMPORTANT]
> These requirements are binding architectural constraints. Any deviation requires explicit written approval referencing the specific NFR being modified. Phase-level implementation prompts may add scope but must not silently override these requirements.

---

## Table of Contents

1. [Performance](#1-performance)
2. [Reliability and Durability](#2-reliability-and-durability)
3. [Security](#3-security)
4. [Observability](#4-observability)
5. [Scalability](#5-scalability)
6. [Maintainability](#6-maintainability)
7. [Concurrency](#7-concurrency)
8. [Data Retention](#8-data-retention)
9. [Constraints and Assumptions](#9-constraints-and-assumptions)

---

## 1. Performance

These requirements govern latency, throughput, and resource utilization targets for the synchronous authorization path and all background workers. All latency figures exclude uncontrollable external variables unless explicitly noted.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-P01** | The Authorization API (`POST /payments`) must achieve P95 end-to-end latency **< 2 seconds**, measured at the service boundary, **excluding PSP network latency**. | Payment authorization is synchronous and customer-facing. Latency beyond 2 seconds degrades merchant checkout conversion and falls outside typical PSP gateway SLA budgets. | Load test at sustained 500 TPS with a mocked PSP stub (zero-latency connector). P95 measured over a 5-minute window must be ≤ 2000 ms. P99 must be reported alongside. |
| **NFR-P02** | PSP connector HTTP client timeout values must be **explicitly configured per provider** in `application.yml`. No connector may rely on JVM or HTTP client default timeouts. | Default timeouts (often 0 = infinite, or framework-specific values) produce indefinite connection hold under PSP degradation, exhausting thread pools and blocking all downstream requests. Per-provider budgets allow precise tuning. | Each `PspConnector` implementation reads its connect timeout and read timeout from a named provider configuration block (e.g., `orchestrator.providers.psp-a.connect-timeout-ms`). A missing config must prevent application startup via `@ConfigurationProperties` binding failure. |
| **NFR-P03** | The atomic 4-insert DB transaction on payment creation (`payment_intent` + `payment_attempt` + `payment_event` + `payment_outbox`) must complete in **< 50 ms** under normal load (≤ 500 TPS). | This transaction is on the synchronous critical path. Slow writes here directly add to end-user latency. Indexes on `idempotency_key`, `merchant_order_id`, and foreign key columns must be present to prevent full-table scans during constraint checks. | Integration test measures transaction duration via Spring micrometer timer. Performance test confirms p95 DB transaction time ≤ 50 ms at 500 TPS with all four tables indexed per §7.7 of `master_context.md`. |
| **NFR-P04** | The Outbox Poller must process a full batch of **100 `PENDING` outbox rows within the 1500 ms polling interval**, including Kafka publish acknowledgements. | The poller is the only mechanism ensuring at-least-once event delivery to downstream consumers. Falling behind creates an unbounded lag tail that delays reconciliation, webhooks, and retry workers. | Integration test publishes 100 synthetic outbox rows and measures time from batch-start to all rows marked `PROCESSED`. Must complete within 1500 ms on a representative test environment. Alert threshold: outbox lag > 5000 ms. |
| **NFR-P05** | The Reconciliation Worker must process a batch of **50 `PENDING` payment intents per cycle**. Each cycle must complete within the configured polling interval (default: 45 000 ms). | PENDING intents represent payments with uncertain outcomes. Processing delays extend the window in which funds may be authorized at the PSP but unconfirmed in the orchestrator, increasing reconciliation risk and degrading merchant-facing status accuracy. | Reconciliation worker processes 50 intents/cycle in load test. Worker must not skip cycles due to prior cycle overrun; it must log and emit a metric (`reconciliation.cycle.overrun.count`) when the previous cycle has not completed. |
| **NFR-P06** | The system must sustain **500 authorization requests per second (TPS)** without exceeding P95 latency targets or error rate above 0.1% for non-PSP errors. | Single-region deployment ceiling per system constraints (§21, `master_context.md`). Headroom above steady-state traffic protects against burst events such as flash sales or retry storms. | Sustained 500 TPS load test for ≥ 10 minutes. Error rate (5xx) ≤ 0.1%. Postgres connection pool, Redis connection pool, and thread pool must not reach saturation (≥ 80% utilization sustained). |
| **NFR-P07** | Idempotency key lookup must execute via an **indexed query on the `idempotency_key` column** of `payment_idempotency`. No full-table scan is permitted on this path. | Idempotency lookup is executed on every authenticated `POST /payments` request, placing it directly on the P95 latency critical path. An unindexed lookup degrades linearly with table size. | Flyway migration must include `CREATE UNIQUE INDEX` on `payment_idempotency(idempotency_key)`. Query plan for idempotency lookup (obtainable via `EXPLAIN ANALYZE`) must show `Index Scan` or `Index Only Scan` — never `Seq Scan`. Verified locally via integration test with ≥ 10 000 seeded rows. |

---

## 2. Reliability and Durability

These requirements define correctness guarantees that must hold under partial failure, process restart, and external system unavailability.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-R01** | **No PSP call may occur before the DB transaction commits.** The sequence `COMMIT → PSP call` is a zero-compromise correctness rule. | Calling the PSP before persisting state creates orphaned authorizations: the PSP may succeed, reserve funds, but the orchestrator has no record. This produces undetectable double charges on retry and irrecoverable reconciliation inconsistency. This is the single most critical ordering constraint in the system. | Code review gate: no `PspConnector` method call may appear within a `@Transactional` boundary in `PaymentOrchestrationService`. Enforced via ArchUnit rule: `noMethods().that().areAnnotatedWith(Transactional.class).should().callMethods().that().areDeclaredIn(PspConnector.class)`. |
| **NFR-R02** | Every state change on a `PaymentIntent` or `PaymentAttempt` must result in a corresponding row inserted into `payment_events`. No state transition is permitted without an accompanying audit event. | `payment_events` is the operational audit trail. Gaps in the event log break forensic analysis, reconciliation debugging, and regulatory audit readiness. State transitions without events are silent mutations that cannot be reconstructed post-hoc. | Integration tests for every state transition path (CREATED→PROCESSING, PROCESSING→AUTHORIZED, PROCESSING→FAILED, PROCESSING→PENDING, PENDING→AUTHORIZED, PENDING→FAILED) assert a corresponding `payment_events` row exists after the transition. |
| **NFR-R03** | The Outbox pattern must guarantee **at-least-once event delivery** to the `EventPublisher`. Events written to `payment_outbox` must be published even if the application restarts between DB commit and initial publish attempt. | Kafka (or any message broker) cannot be part of the same DB transaction. The outbox table is the durable buffer that decouples persistence from publication. Without at-least-once delivery, downstream consumers (reconciliation, retry, merchant notification) may never receive events from transient application failures. | Testcontainers integration test: insert outbox row, kill application before poller runs, restart application, assert event is published and row marked `PROCESSED`. Consumers must be idempotent to handle at-least-once delivery. |
| **NFR-R04** | The idempotency layer must guarantee **at-most-once business effect** for any given `Idempotency-Key` within its TTL window. Identical key + identical payload must return the cached response without creating new DB records. | Without idempotency enforcement, merchant retry logic (network timeout, HTTP 5xx retry) creates duplicate payments and double charges — the most severe class of payment error. At-most-once is the correctness contract the orchestrator must deliver to merchants. | Test R-01 (`same key + same payload`): assert no new `payment_intents` or `payment_attempts` rows are created on the second call. Assert response matches cached response exactly. Test R-02 (`same key + different payload`): assert `409 IDEMPOTENCY_CONFLICT`. |
| **NFR-R05** | The system must recover from a **Redis restart without opening a nonce replay window**. Nonce data must survive Redis restart via AOF or RDB persistence. | A Redis restart that clears the nonce store allows an attacker to replay requests that were valid within the last 10 minutes. This breaks replay protection entirely for the duration of the data loss window. | Redis deployed with AOF persistence enabled (`appendonly yes`). Testcontainers integration test: store nonce, restart Redis container, attempt nonce reuse, assert `401 NONCE_REUSED`. Integration test I-05 in `architecture.md`. |
| **NFR-R06** | The system must tolerate **PSP unavailability** without cascading failure. An open circuit breaker on a provider must result in the payment being routed to a fallback provider or transitioned to `PENDING` — never in a 500 response to the merchant due to PSP unavailability alone. | PSPs have variable availability SLAs. Propagating PSP failure directly to the merchant API destroys authorization availability for all merchants on that provider. The `PENDING` state exists precisely to represent uncertain outcomes without blocking the merchant. | Circuit breaker configured per provider with `failure-threshold`, `rolling-window-size`, and `half-open-after-ms`. Integration test I-04: with PSP_A unavailable and circuit open, CARD request either routes to fallback or returns `202 PENDING`. No `500` response emitted. |
| **NFR-R07** | **No payment data may be lost on application restart.** All critical payment state (`payment_intents`, `payment_attempts`, `payment_events`, `payment_outbox`) resides exclusively in PostgreSQL. No in-memory state structures are used for primary payment data. | An application restart must produce zero data loss. In-memory state (JVM heap maps, local queues) does not survive restarts. Durability is PostgreSQL's responsibility; the application layer must not assume it is the only participant. | Application restart test: create payment, kill JVM before response, restart, query DB — assert all four rows (`intent`, `attempt`, `event`, `outbox`) are present and consistent. |

---

## 3. Security

These requirements define the mandatory security controls for all API interactions, secrets management, and data handling.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-S01** | All payment API requests (`POST /payments`) must include a valid **HMAC-SHA256 signature** in the `X-Signature` header. Requests with absent, malformed, or invalid signatures must be rejected with `401 INVALID_SIGNATURE`. | HMAC signatures bind the request body, headers, and merchant identity into a tamper-evident envelope. Without signature verification, any party with the API endpoint can submit fraudulent payment requests. | Canonical string: `HTTP_METHOD + REQUEST_PATH + SHA256(canonical_body) + X-Timestamp + X-Nonce + merchant_id`. Signature: `Base64(HMAC_SHA256(merchant_secret, canonical_string))`. Test N-04: tampered body returns `401 INVALID_SIGNATURE`. |
| **NFR-S02** | **Nonce replay protection** must be enforced. Each `X-Nonce` must be unique per `merchant_id` within a **10-minute TTL window**. A reused nonce must be rejected with `401 NONCE_REUSED` regardless of signature validity. | Timestamp validation alone allows replay within the ±5-minute drift window. Nonce uniqueness closes this window: even a perfectly valid, freshly-timestamped request is rejected if its nonce was seen before. Redis key format: `nonce:{merchant_id}:{nonce}`. | Test N-05: reused nonce within 10-minute window returns `401 NONCE_REUSED`. Redis TTL on stored nonce keys is verified to be exactly 600 seconds. Nonce minimum length: 16 characters. |
| **NFR-S03** | The `X-Timestamp` header must be validated to be **within ±5 minutes of server time** (UTC). Requests outside this window must be rejected with `401 TIMESTAMP_INVALID`. | Timestamp validation bounds the maximum viable replay window. Without it, captured requests can be replayed indefinitely. The ±5-minute window accommodates realistic client clock skew without exposing a large attack surface. | Tests N-06 (`timestamp 6 minutes old`) and N-07 (`timestamp 6 minutes future`) both return `401 TIMESTAMP_INVALID`. Threshold configurable via `orchestrator.replay-protection.timestamp-window-minutes`. |
| **NFR-S04** | **No raw card credentials or PII may be stored** in any system table. The `payment_token_reference` field stores only an opaque vault token. The orchestrator must resolve tokens via the `PaymentInstrumentResolver` interface without ever touching the underlying credential. | PCI-DSS compliance requires that systems not handling card data fall outside PCI scope. Storing raw credentials expands PCI scope to the orchestrator, necessitating QSA audit, network segmentation, and key management overhead incompatible with the service's scope boundary. | Code review gate: no field in any entity or DTO may be named `card_number`, `cvv`, `pan`, or equivalent. `payment_events.event_payload` must not contain raw credential fields. Enforced via ArchUnit field naming rule. |
| **NFR-S05** | All merchant secrets and signing keys must be **stored in a KMS / secrets manager** in production. Secrets must never appear in `application.yml`, environment variable files committed to version control, or any config-server plaintext store. | Secrets in config files are exfiltrated by any process that can read the filesystem or environment. KMS-backed secrets provide audit trails, rotation support, and access control without in-process key storage. | Production profile (`spring.profiles.active=prod`) must source all secrets via a `SecretsProvider` interface backed by KMS. `application-prod.yml` must contain no inline secret values. Code check asserts no file matching `application*.yml` contains key patterns matching HMAC secret formats. |
| **NFR-S06** | **Sensitive fields must be masked in all log output.** Fields including `payment_token_reference`, `X-Signature`, HMAC keys, and any PSP credential must never appear in structured log lines. | Log aggregation pipelines (ELK, Splunk, Datadog) are often accessible to a wider audience than production systems. Credential leakage via logs is a frequent root cause of security incidents and is trivially exploitable. | Log output test: initiate a payment with a known token value, assert the token value does not appear in any log line written by the application. Implemented via Logback `PatternLayout` masking or custom `MaskingMessageConverter`. |
| **NFR-S07** | **Zero-downtime key rotation** must be supported. During a configurable grace period, the system must accept signatures validated against either the active key or the immediately preceding grace-period key. After the grace period expires, the old key must be invalidated. | Merchant secret rotation requires coordinating secret updates between the merchant's system and the orchestrator's validation. A grace period allows the merchant to deploy their new signing key without a coordinated simultaneous cutover, preventing any request rejection gap. | Validation sequence: (1) validate against active key; (2) if fails, validate against grace-period key; (3) if both fail, reject `401`. Grace period configured via `orchestrator.security.key-rotation.grace-period-minutes`. Integration tests I-06 and I-07 in `architecture.md`. |
| **NFR-S08** | **Webhook signatures must be validated** before any processing of the webhook payload occurs. Requests to `POST /webhooks/{provider}` with absent or invalid PSP signatures must be rejected with `401 INVALID_WEBHOOK_SIGNATURE`. | Unsigned webhooks allow any external party to inject forged state transitions (e.g., marking a `FAILED` payment as `AUTHORIZED`). Signature validation must precede all business logic including state transition evaluation. | Test N-21: webhook with invalid signature returns `401 INVALID_WEBHOOK_SIGNATURE`. Validation occurs in the webhook filter/interceptor layer, before `WebhookProcessor.process()` is invoked. |

---

## 4. Observability

These requirements define the mandatory instrumentation, logging, and health-check standards for production operation.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-O01** | All application log output must use **structured JSON format** via the Logstash Logback encoder. No plaintext or pattern-only log format is permitted in `dev` or `prod` profiles. | Structured JSON logs are machine-parseable without fragile regex extraction, enabling reliable log routing, alerting, and dashboarding in ELK/Datadog pipelines. Plaintext logs require brittle grok patterns and break on multi-line stack traces. | `logstash-logback-encoder` dependency present in `pom.xml`. `logback-spring.xml` configures `LogstashEncoder` for `dev` and `prod` profiles. Log output validated to parse as valid JSON locally. |
| **NFR-O02** | **MDC trace context** must be propagated on every request and cleared on completion. The following fields must be present in every log line within a request boundary: `request_id`, `correlation_id`, `internal_request_id`, `intent_id` (once created). Optional: `attempt_id`, `merchant_id`, `provider_name`. | Without correlated trace identifiers, production incident investigation requires manual log correlation across services. MDC ensures that any log line from any layer (controller, service, repository, worker) can be traced back to a specific payment request. | Unit tests assert MDC contains all mandatory fields after request context initialization. Integration tests verify JSON log output includes all mandatory fields. MDC must be cleared via `MDC.clear()` in a `finally` block or `OncePerRequestFilter.afterCompletion`. |
| **NFR-O03** | **Prometheus metrics** must be exposed at `/actuator/prometheus`. The following application-level metrics are mandatory: `authorization.success.count`, `authorization.failure.count`, `authorization.pending.count`, `psp.latency.ms` (histogram, per-provider tag), `psp.timeout.count` (per-provider), `retry.count`, `reconciliation.backlog.size`, `outbox.lag.ms`, `circuit.breaker.state` (per-provider). | Prometheus metrics are the primary signal for SLO alerting and capacity planning. Without per-provider latency histograms, PSP degradation is invisible until end-user impact is already occurring. | `micrometer-registry-prometheus` dependency present. All listed metrics verifiable via `GET /actuator/prometheus` in integration test. Each histogram metric must include `p50`, `p95`, `p99` quantile buckets. |
| **NFR-O04** | **Spring Boot Actuator health endpoints** must report component health for all critical infrastructure: PostgreSQL, Redis, and Kafka. `GET /actuator/health` must return a composite status reflecting the state of all three. | Health endpoints are the primary signal for load balancer routing decisions and deployment orchestration readiness gates. A degraded dependency that goes unreported causes silent request failures rather than clean traffic diversion. | Health contributors for `db` (DataSource), `redis` (RedisHealthIndicator), and `kafka` (KafkaHealthIndicator) must all be registered. `GET /actuator/health` returns `DOWN` when any critical dependency is unreachable. Verified in Testcontainers integration test by stopping each dependency container. |
| **NFR-O05** | **All background workers** (Outbox Poller, Reconciliation Worker, Retry Worker) must propagate a synthetic MDC context at the start of each cycle and clear it unconditionally at cycle end, regardless of success or failure. | Workers operate outside of HTTP request scope and have no automatic MDC lifecycle management. Without explicit MDC setup and teardown, log lines from background workers carry no correlation context and are undiagnosable. A leaked MDC from a previous cycle corrupts context for the next. | Unit tests for each worker assert MDC is populated at cycle start and empty after cycle completion (including exception paths). Log output from worker cycles must include `correlation_id` and a synthetic `internal_request_id`. |
| **NFR-O06** | Every **nonce reuse attempt** must produce an audit log entry. The entry must be written to a durable store (database or append-only log) — not only to the application log stream. | Nonce reuse is a security signal: it may indicate a replay attack, a misconfigured merchant client, or a security incident in progress. Ephemeral application logs are insufficient for forensic analysis; durable audit records survive log rotation and are queryable post-incident. | Test N-05: after a `401 NONCE_REUSED` response, assert a corresponding audit record exists in the designated audit store. Record must include: `merchant_id`, `nonce`, `timestamp`, `X-Request-Id`, client IP (if available). |

---

## 5. Scalability

These requirements define the constraints and mechanisms that allow the system to scale horizontally and handle increased load without architectural changes.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-SC01** | The application layer must be **fully stateless**. No payment state, session data, or in-flight request context may be stored in JVM heap structures that are not replicated across instances. All shared state must reside in PostgreSQL or Redis. | Stateful application layers prevent horizontal scaling: adding a second instance creates split-brain state. Statelessness means any instance can serve any request, enabling standard Kubernetes HPA or load balancer-based scale-out. | Code review gate: no `static` mutable state or instance-level caches in service or controller classes. In-memory `EventPublisher` is permitted only in `local`/`test` profiles, disabled in `dev` and `prod`. |
| **NFR-SC02** | All background worker batch queries against `payment_outbox` and `payment_intents` must use **`SELECT FOR UPDATE SKIP LOCKED`**. Workers must not use application-level locking, advisory locks, or leader-election for basic operation. | `SKIP LOCKED` is the standard PostgreSQL mechanism for safe multi-worker queue consumption. Without it, two instances processing the same batch create duplicate event publishes and duplicate PSP calls. Application-level locking introduces distributed coordination overhead that `SKIP LOCKED` handles natively. | Testcontainers integration test: start two application instances, both outbox pollers running. Assert each outbox row is published exactly once (no duplicate Kafka messages). Verified via message count assertion on the consumer side. |
| **NFR-SC03** | A **partial index** on `payment_outbox(status, created_at) WHERE status = 'PENDING'` must exist and be maintained by Flyway migration. The outbox poller query must only scan this partial index. | The outbox table accumulates `PROCESSED` rows at a 1:1 rate with payment volume. Without a partial index, the poller scans an ever-growing full table on every 1.5-second cycle, degrading performance proportionally to historical payment volume. | Flyway migration creates partial index: `CREATE INDEX idx_outbox_pending ON payment_outbox(created_at) WHERE status = 'PENDING'`. `EXPLAIN ANALYZE` on outbox poller query shows `Index Scan` on `idx_outbox_pending`. Verified in CI integration test. |
| **NFR-SC04** | The Redis nonce store must support **Redis Cluster mode** configuration. The application must not rely on single-node Redis semantics (e.g., Lua scripts or transactions that span multiple slots) for nonce operations. | Single-node Redis is a scalability and availability ceiling. At 500 TPS with a 10-minute nonce TTL, the nonce keyspace holds up to 300 000 entries. Redis Cluster mode allows the nonce store to scale horizontally without application changes and provides high availability through automatic failover. | Redis client configured via `spring.data.redis.cluster.*` when cluster mode is enabled. Nonce SET/GET operations use single-key commands compatible with Redis Cluster. Testcontainers integration test verifies nonce operations against a Redis Cluster topology. |

---

## 6. Maintainability

These requirements govern code quality, testability, and operational change management.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-M01** | All external system interactions must be accessed **exclusively via interfaces**: `PspConnector`, `EventPublisher`, `PaymentInstrumentResolver`, `SecretsProvider`. No service class may directly instantiate or reference concrete infrastructure classes. | Interface-driven design allows external systems to be swapped (e.g., Kafka → InMemoryPublisher, PSP_A → PSP_B stub) without modifying business logic. It is the only mechanism that makes unit testing possible without running infrastructure and integration testing possible without running production PSPs. | ArchUnit rule: service classes in `com.payments.orchestrator.service` must not reference classes in `com.payments.orchestrator.infrastructure` directly. All injected dependencies must be interface types. Enforced locally. |
| **NFR-M02** | All schema changes must be implemented via **Flyway versioned migrations**. No DDL may be applied directly to any environment (including `dev`) outside of a Flyway migration file. | Ad-hoc DDL changes applied outside Flyway create schema drift between environments and make rollback impossible to automate. Flyway ensures every environment (local, dev, prod) has an identical, reproducible schema history. | `spring.flyway.enabled=true` in all profiles. Local build execution runs `flyway:migrate` and asserts no pending migrations exist after test completion. No raw DDL in `src/main/resources` outside `db/migration/`. |
| **NFR-M03** | All timeouts, polling intervals, batch sizes, TTLs, and retry thresholds must be **externalized to `application.yml`** with named keys under the `orchestrator.*` namespace. No numeric literal (`magic number`) for these values may appear in application source code. | Magic numbers embedded in source code require a code change + redeploy to tune. Externalized configuration allows operational tuning (e.g., adjusting outbox poll interval under load) without code changes and makes the system's operational parameters visible and auditable. | ArchUnit or custom rule: no `int` or `long` literal constants for timeout/interval values in `*.java` files under the service package. All such values must be bound via `@ConfigurationProperties`. Reviewed in code review. |
| **NFR-M04** | Service layer classes must maintain **≥ 80% unit test coverage**, measured by line coverage. Coverage is enforced at build time via the Maven Surefire + JaCoCo pipeline. | The service layer contains all business logic: state transitions, idempotency enforcement, routing decisions, retry safety evaluation. Untested service logic is the primary source of production correctness bugs in payment systems. | JaCoCo `verify` goal configured with `minimum: 0.80` for `com.payments.orchestrator.service.*`. Build fails if coverage drops below threshold. Coverage report generated as build artifact. |
| **NFR-M05** | **Full integration test coverage** must exist for all 12 critical scenarios defined in `architecture.md` (5 Sanity, 7 Regression). Tests must use Testcontainers for PostgreSQL and Redis. No mocking of the database or Redis is permitted in integration tests. | Integration tests at the HTTP boundary, backed by real infrastructure containers, are the only mechanism that validates the full request pipeline — including validation ordering, transaction boundaries, and concurrent worker behavior. DB/Redis mocking introduces false confidence. | All test IDs S-01 through S-05 and R-01 through R-07 must have corresponding `@SpringBootTest` + Testcontainers test classes. Local test suite executes these tests. Test execution must complete within 10 minutes. |

---

## 7. Concurrency

These requirements define the mandatory mechanisms for handling concurrent access to shared mutable state.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-C01** | **Optimistic locking** via the `version` column must be used on all writes to `PaymentIntent` and `PaymentAttempt` entities. An `OptimisticLockException` (or JPA equivalent) must be caught and surfaced as a retryable operation error — never silently swallowed. | Webhook delivery, reconciliation, and retry workers can all attempt to update the same `PaymentIntent` concurrently. Without version-based optimistic locking, the last write silently wins: a later, stale update can overwrite a correct earlier state, producing corrupt payment state. | Both `payment_intents` and `payment_attempts` tables have `version BIGINT NOT NULL DEFAULT 0`. JPA entity classes carry `@Version`. Concurrent update test: two threads attempt simultaneous state transition on same intent, one succeeds, one throws `ObjectOptimisticLockingFailureException`. |
| **NFR-C02** | All background worker batch queries must use **`SELECT FOR UPDATE SKIP LOCKED`**. This applies to: outbox poller queries on `payment_outbox`, reconciliation worker queries on `payment_intents`, and retry worker queries on eligible attempts. | `SKIP LOCKED` prevents multiple worker instances from processing the same row. Without it, two workers picking up the same PENDING intent make concurrent PSP status API calls and may apply conflicting state transitions, producing non-deterministic final state. | SQL queries in all worker repositories use `FOR UPDATE SKIP LOCKED` clause. Verified via `@Query` annotation inspection. Concurrent worker integration test (NFR-SC02) confirms no row is processed twice. |
| **NFR-C03** | **Last-write-wins behavior is prohibited** on all mutable payment entities. Any code path that reads a `PaymentIntent` or `PaymentAttempt` and subsequently writes an update must either use optimistic locking (version check) or a `SELECT FOR UPDATE` pessimistic lock. Neither pattern may be omitted. | Last-write-wins is the default behavior of a naive `findById + save` JPA pattern. It allows stale reads to overwrite valid, newer state without any error signal. In payment systems, this can silently revert an `AUTHORIZED` payment to `PROCESSING` or `PENDING`. | ArchUnit rule: any method in `*Repository` that performs a write must be called from a path that either uses `@Version`-checked entity or an explicit `@Lock(LockModeType.PESSIMISTIC_WRITE)` query. Enforced in code review and local validation. |
| **NFR-C04** | The Webhook Processor must handle **concurrent delivery of the same webhook event idempotently**. Duplicate webhook event IDs must be detected and silently acknowledged without reprocessing. | PSPs deliver webhooks with at-least-once guarantees. Network issues, load balancer retries, and PSP retry logic mean the same event may arrive multiple times, potentially concurrently. Without deduplication, concurrent duplicates can trigger multiple state transitions on the same intent. | `processed_webhooks` table with `UNIQUE(provider_name, provider_event_id)` constraint. Concurrent test: same webhook event delivered twice simultaneously, assert only one state transition applied and one `payment_events` row created. Duplicate insert fails silently (catches `DataIntegrityViolationException`). |

---

## 8. Data Retention

These requirements define the lifecycle policies for all persistent data in the system.

| ID | Requirement | Rationale | Acceptance Criteria |
|---|---|---|---|
| **NFR-D01** | **Idempotency keys expire after 24 hours**. The `payment_idempotency` table must be purged of expired rows by a scheduled cleanup job. Expired keys must be treated as new by the idempotency lookup. | A 24-hour TTL covers all realistic merchant retry windows (automated retries typically operate within minutes to hours). Indefinite retention creates unbounded storage growth proportional to payment volume and complicates GDPR erasure compliance. | `payment_idempotency.expires_at` set to `NOW() + INTERVAL '24 hours'` on insert. Scheduled cleanup job runs at configurable interval and deletes rows where `expires_at < NOW()`. TTL configurable via `orchestrator.idempotency.ttl-hours`. Integration test: insert expired row, assert it is not matched by idempotency lookup. |
| **NFR-D02** | **Processed outbox rows are pruned after 7 days**. The cleanup job must target only rows with `status = 'PROCESSED'` and `processed_at < NOW() - INTERVAL '7 days'`. Rows with `status = 'PENDING'` or `status = 'FAILED'` must not be automatically deleted. | Processed outbox rows are no longer needed for delivery guarantees. Accumulating them indefinitely degrades the `idx_outbox_pending` partial index performance indirectly (via table bloat on shared pages) and wastes storage. 7 days provides sufficient buffer for post-incident analysis before pruning. | Cleanup SQL: `DELETE FROM payment_outbox WHERE status = 'PROCESSED' AND processed_at < NOW() - INTERVAL '7 days'`. Scheduled job logs count of deleted rows as metric: `outbox.pruned.count`. `PENDING` and `FAILED` rows untouched. |
| **NFR-D03** | `payment_events` rows are **immutable and append-only**. No `UPDATE` or `DELETE` operation on `payment_events` is permitted from any application code path. This constraint is enforced at both the application layer and, where possible, at the database layer via row-level security or revoked permissions. | `payment_events` is the forensic audit trail. Mutability destroys its evidentiary value: a modified or deleted event is indistinguishable from an original. Append-only semantics are a prerequisite for regulatory audit readiness and incident reconstruction. | ArchUnit rule: no call to any `*Repository.delete*` or `*Repository.save*` method where the entity type is `PaymentEvent`. No Flyway migration may contain `UPDATE payment_events` or `DELETE FROM payment_events`. DB-level: consider `GRANT INSERT ON payment_events` only, with `UPDATE` and `DELETE` revoked from the application role. |
| **NFR-D04** | **Audit events** (`payment_events` rows and nonce reuse audit records) must be retained for a duration compliant with applicable regulatory requirements. The specific retention period is outside this service's scope and must be specified by the platform compliance team before production go-live. | Payment authorization records are subject to financial regulation (e.g., PSD2, RBI guidelines, card scheme rules) that mandate multi-year retention. Premature deletion is a compliance violation. The retention period must be a configuration decision informed by legal counsel, not a default engineering choice. | A retention policy configuration key `orchestrator.audit.retention-days` must exist but must default to `null` (unset) in all profiles. Application must fail startup in `prod` profile if this value is not explicitly set. Enforced via `@ConfigurationProperties` validation. |

---

## 9. Constraints and Assumptions

The NFRs above are defined within the following system constraints. Any change to these constraints requires re-evaluation of the affected NFRs.

| Constraint | Value | Affected NFRs |
|---|---|---|
| Deployment topology | Single-region | NFR-SC01, NFR-R07 |
| PostgreSQL topology | Primary-write single node | NFR-R07, NFR-C01 |
| Transaction isolation | `READ COMMITTED` (default) | NFR-C01, NFR-C03 |
| Expected peak throughput | 500 TPS | NFR-P01, NFR-P06 |
| Settlement, ledger, refund | Explicitly out of scope | NFR-D04 |
| Multi-region consensus | Not implemented | NFR-SC01 |
| Event sourcing | Not implemented | NFR-R02, NFR-D03 |
| Redis topology (prod) | Cluster or Sentinel | NFR-R05, NFR-SC04 |
| PSP idempotency | PSPs must support idempotent `provider_reference` | NFR-R01, NFR-C04 |
| Reconciliation SLA | Non-real-time (minutes, not seconds) | NFR-P05 |

---

## Appendix A: NFR Cross-Reference by System Component

| Component | Applicable NFRs |
|---|---|
| `POST /payments` API | NFR-P01, NFR-P03, NFR-P06, NFR-P07, NFR-R01, NFR-R04, NFR-S01, NFR-S02, NFR-S03, NFR-S04, NFR-S05, NFR-S06, NFR-O01, NFR-O02 |
| `POST /webhooks/{provider}` | NFR-S08, NFR-C04, NFR-R02, NFR-O02 |
| Outbox Poller | NFR-P04, NFR-R03, NFR-SC02, NFR-SC03, NFR-O05, NFR-D02 |
| Reconciliation Worker | NFR-P05, NFR-R06, NFR-SC02, NFR-O05, NFR-C02 |
| Retry Worker | NFR-R06, NFR-SC02, NFR-C02, NFR-O05 |
| Redis Nonce Store | NFR-S02, NFR-R05, NFR-SC04, NFR-O06 |
| PostgreSQL | NFR-P03, NFR-P07, NFR-R07, NFR-C01, NFR-C02, NFR-C03, NFR-SC03, NFR-D01, NFR-D02, NFR-D03 |
| Kafka / EventPublisher | NFR-R03, NFR-SC02, NFR-O04, NFR-M01 |
| Secrets / KMS | NFR-S05, NFR-S07 |
| Actuator / Prometheus | NFR-O03, NFR-O04 |
| Flyway | NFR-M02, NFR-P07, NFR-SC03 |
| Test suite | NFR-M04, NFR-M05, NFR-R01 |

---

## Appendix B: NFR Verification Methods

| **Verification Method** | NFRs |
|---|---|
| **Load / performance test** (JMeter, Gatling, or k6) | NFR-P01, NFR-P03, NFR-P04, NFR-P05, NFR-P06 |
| **Integration test** (Testcontainers, `@SpringBootTest`) | NFR-P07, NFR-R01–R07, NFR-S01–S08, NFR-SC02–SC04, NFR-C01–C04, NFR-D01–D03, NFR-O04 |
| **Unit test** (JUnit 5, Mockito) | NFR-M04, NFR-O02, NFR-O05, NFR-O06 |
| **ArchUnit static analysis** | NFR-R01, NFR-M01, NFR-C03, NFR-S04, NFR-D03 |
| **Build validation gate** (JaCoCo, Flyway validate) | NFR-M02, NFR-M04 |
| **Code review** | NFR-P02, NFR-M03, NFR-S04, NFR-S05, NFR-S06 |
| **Operational config verification** | NFR-S05, NFR-M03, NFR-D04 |
| **`EXPLAIN ANALYZE`** | NFR-P07, NFR-SC03 |
