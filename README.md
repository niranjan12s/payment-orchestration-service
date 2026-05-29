# Payment Orchestration Service

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![Testcontainers](https://img.shields.io/badge/Testcontainers-✓-2496ED?logo=docker&logoColor=white)
![License](https://img.shields.io/badge/License-GNU-green)

A production-grade payment orchestration backend built with Spring Boot 3.3 and Java 21. The service exposes a REST API that routes payment authorizations to the appropriate Payment Service Provider (PSP) based on payment method type, enforces idempotency and replay protection at the API layer, persists a full audit event trail within a single ACID transaction, and delegates downstream event publishing to background workers via the Transactional Outbox pattern. Webhook callbacks from PSPs are ingested with HMAC signature verification and deduplicated using Redis. Resilience4j circuit breakers guard all outbound PSP calls, and Micrometer/Prometheus metrics expose operational visibility across the full payment lifecycle.

---

## Table of Contents

1. [Interactive Developer Console](#interactive-developer-console-dashboard)
2. [Architecture](#architecture)
3. [Tech Stack](#tech-stack)
4. [Prerequisites](#prerequisites)
5. [Local Development Setup](#local-development-setup)
6. [API Quick-Start](#api-quick-start)
7. [Configuration Reference](#configuration-reference)
8. [Background Workers](#background-workers)
9. [Actuator Endpoints](#actuator-endpoints)
10. [Project Structure](#project-structure)
11. [Testing](#testing)
12. [Key Design Decisions](#key-design-decisions)
13. [Further Documentation](#further-documentation)
14. [License](#license)

---

## Interactive Developer Console Dashboard

The repository includes a premium, interactive **Developer Console & API Playground** built to visualize, debug, and play with our payment orchestration system in real-time.

### What is inside:
1. **Interactive Architecture Flow**: Click nodes in the system pipeline to reveal structural JPA database rules, Redis nonce configurations, transactional boundaries (`@Transactional`), and security boundaries.
2. **API Playground (Developer Console)**: 
   - Monospace **JSON request editor** loaded with 8+ pre-set API variations (CARD Success, UPI Success, Timeouts, Nonce Replays, Webhook transitions).
   - **Batch execution engine ("Run All Variations")** executing test suites and verifying contract rules sequentially.
   - Dual output display: toggle between clean structured result cards or pretty-printed raw JSON values.
3. **Observability Scraper**: Simulates real-time Prometheus scrapers (`GET /actuator/prometheus`) and shows live Actuator health indicators and thread-propagated MDC traces in a log console.
4. **Lifecycle State Machine Simulator**: Test transition constraints (including terminal state protection rules) on the fly and verify exception handling models.

### Accessing the Dashboard:
To launch the console, simply start the application and navigate to:
* **[http://localhost:8081/dashboard/index.html](http://localhost:8081/dashboard/index.html)**

---

## Architecture

```
                          ┌────────────────────────────────────────────────────┐
                          │              payment-orchestration-service          │
                          │                                                    │
  ┌────────┐   HTTPS      │  ┌──────────────┐    ┌───────────────────────────┐ │
  │        │ ──────────►  │  │  REST Layer  │    │    Validation Pipeline    │ │
  │ Client │              │  │              │───►│                           │ │
  │        │              │  │ POST /pay    │    │  • Bean Validation (JSR)  │ │
  └────────┘              │  │ POST /webhook│    │  • Idempotency Check      │ │
                          │  └──────────────┘    │  • Replay Protection      │ │
                          │                      │  • Merchant Order Dedup   │ │
                          │                      └────────────┬──────────────┘ │
                          │                                   │                │
                          │                      ┌────────────▼──────────────┐ │
                          │                      │    DB Transaction (ACID)  │ │
                          │                      │                           │ │
                          │                      │  PaymentIntent            │ │
                          │                      │  PaymentAttempt           │ │
                          │                      │  PaymentEvent (audit)     │ │
                          │                      │  PaymentOutbox            │ │
                          │                      └────────────┬──────────────┘ │
                          │                                   │                │
                          │                      ┌────────────▼──────────────┐ │
                          │                      │      PSP Connectors        │ │
                          │                      │                           │ │
                          │                      │  PSP_A (CARD) ──────────► │ │
                          │                      │  PSP_B (UPI)  ──────────► │ │
                          │                      │  Resilience4j CB + Retry  │ │
                          │                      └────────────┬──────────────┘ │
                          │                                   │                │
                          │            ┌──────────────────────▼──────────────┐ │
                          │            │          Async Background Workers    │ │
                          │            │                                      │ │
                          │            │  OutboxPublisher   (1.5s poll)       │ │
                          │            │  ReconciliationWorker (45s poll)     │ │
                          │            │  RetryWorker          (2s poll)      │ │
                          │            └──────────────────────────────────────┘ │
                          └────────────────────────────────────────────────────┘
                                        │                  │
                              ┌─────────▼───┐     ┌────────▼──────┐
                              │ PostgreSQL  │     │    Redis      │
                              │     15      │     │      7        │
                              └─────────────┘     └───────────────┘
```

**Request flow summary:**

1. Client sends `POST /api/v1/payments-orchestration/payments` with an `Idempotency-Key` header.
2. The validation pipeline checks bean constraints, idempotency (Redis + DB), replay-protection timestamp/nonce windows, and merchant order uniqueness.
3. A single ACID transaction persists `PaymentIntent`, `PaymentAttempt`, `PaymentEvent`, and `PaymentOutbox` records atomically.
4. The routing engine selects the correct PSP connector (`CARD → PSP_A`, `UPI → PSP_B`); the connector is guarded by a Resilience4j circuit breaker.
5. Background workers asynchronously publish outbox events, reconcile pending intents, and execute exponential-backoff retries — all using `SELECT FOR UPDATE SKIP LOCKED` to prevent double-processing.

---

## Tech Stack

| Dimension   | Technology                                           |
|-------------|------------------------------------------------------|
| Language    | Java 21 (LTS)                                        |
| Framework   | Spring Boot 3.3, Spring Data JPA, Spring Validation  |
| Database    | PostgreSQL 15                                        |
| Cache       | Redis 7 (idempotency store, replay nonce cache)      |
| Queue       | Transactional Outbox → `InMemoryEventPublisher` (local/test); Kafka-ready interface |
| Migration   | Flyway (versioned SQL migrations in `db/migration/`) |
| Resilience  | Resilience4j 2.2 (circuit breaker, annotations)      |
| Testing     | JUnit 5, Spring Boot Test, Testcontainers            |
| Docs        | springdoc-openapi 2.5 (Swagger UI at `/swagger-ui.html`) |
| Observability | Micrometer + Prometheus, Logstash JSON logging     |

---

## Prerequisites

| Requirement | Version  | Notes                                                  |
|-------------|----------|--------------------------------------------------------|
| Docker      | 20+      | Required for PostgreSQL + Redis via Docker Compose, and for Testcontainers |
| Java 21     | 21 (LTS) | **Optional if using `build.ps1`** — the script bootstraps a local JDK |
| Maven       | 3.9+     | **Optional if using `build.ps1`** — the script bootstraps Maven 3.9.6 locally |

> **Windows users do not need Java or Maven installed system-wide.** The `build.ps1` script bootstraps Eclipse Temurin OpenJDK 21 and Apache Maven 3.9.6 into the `.tools/` directory on first run. This is the recommended approach for local development on Windows.

---

## Local Development Setup

### 1. Clone the Repository

```bash
git clone https://github.com/niranjan12s/payment-orchestration-service.git
cd payment-orchestration-service
```

### 2. Bootstrap the Toolchain (Windows)

The `setup_toolchain.ps1` script downloads and extracts Eclipse Temurin JDK 21 and Apache Maven 3.9.6 into `.tools/jdk` and `.tools/maven` respectively. This is idempotent — it is a no-op if the tools are already present.

```powershell
# One-time setup — downloads ~200 MB on first run
powershell -ExecutionPolicy Bypass -File .\setup_toolchain.ps1
```

Alternatively, install Java 21 and Maven 3.9+ manually and ensure they are on your `PATH`.

### 3. Start Infrastructure

```bash
# Start PostgreSQL 15 and Redis 7 in the background
docker-compose up -d postgres redis
```

Verify both containers are healthy:

```bash
docker-compose ps
```

The application's `local` profile connects to:
- PostgreSQL: `jdbc:postgresql://localhost:5432/payment_orchestrator`
- Redis: `localhost:6379`

Credentials are defined in `docker-compose.yml` and mirrored in `application-local.yml`.

### 4. Apply Database Migrations

Flyway migrations run automatically on application startup. No manual step is required. Migration scripts are located at:

```
src/main/resources/db/migration/
  V1__init_schema.sql
  V2__add_manual_review_status.sql
```

### 5. Run the Application

**On Windows (recommended — uses local toolchain):**

```powershell
# Default: runs mvn clean compile
powershell -ExecutionPolicy Bypass -File .\build.ps1

# To package and run:
powershell -ExecutionPolicy Bypass -File .\build.ps1 clean package -DskipTests
java -jar target/payment-orchestrator-0.0.1-SNAPSHOT.jar
```

The `build.ps1` script calls `setup_toolchain.ps1` first, then configures `JAVA_HOME` and `PATH` for the current PowerShell session, and forwards any arguments directly to `mvn`.

**On macOS / Linux (system Java + Maven required):**

```bash
mvn clean package -DskipTests
java -jar target/payment-orchestrator-0.0.1-SNAPSHOT.jar
```

The application starts with `spring.profiles.active=local` (set in `application.yml`). The service listens on `http://localhost:8081`.

---

## Interactive Developer Console Dashboard

Instead of executing manual raw curl commands, you can use our premium, interactive **Developer Console & API Playground** to test state scenarios, trigger payload variables, and trace security filters on the fly. 

To launch the dashboard, navigate to:
👉 **[http://localhost:8081/dashboard/index.html](http://localhost:8081/dashboard/index.html)**

---

### Create a Payment

> [!IMPORTANT]
> **Mandatory Security Headers**: When sending raw requests via curl, you must supply all **five security and validation headers**. The `SecurityFilter` will reject any request missing these elements with a `401 Unauthorized` response.
> - `Idempotency-Key`: Prevents multi-charging.
> - `X-Request-Id`: End-to-end trace correlation UUID.
> - `X-Timestamp`: Clock drift check (must be within $\pm$5 minutes).
> - `X-Nonce`: Random uniqueness token (checked against Redis cache).
> - `X-Signature`: HMAC-SHA256 signature calculated over the canonical metadata block and body payload.
>
> *(Note: Our Interactive Developer Console Dashboard automatically calculates, hashes, and signs these headers in real time so you can experiment seamlessly in the browser.)*

```bash
curl -X POST http://localhost:8081/api/v1/payments-orchestration/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: a1b2c3d4-e5f6-7890-abcd-ef1234567890" \
  -H "X-Request-Id: 550e8400-e29b-41d4-a716-446655440000" \
  -H "X-Timestamp: 2026-05-29T04:20:00Z" \
  -H "X-Nonce: random_nonce_abc123" \
  -H "X-Signature: Calculated_HMAC_SHA256_Signature_Hex" \
  -d '{
    "merchant_id": "550e8400-e29b-41d4-a716-446655440000",
    "merchant_order_id": "ORDER-20260527-001",
    "payment_method_type": "CARD",
    "payment_token_reference": "vault_token_abc123",
    "transaction_amount": {
      "currency": "USD",
      "amount": "150.00"
    },
    "settlement_amount": {
      "currency": "INR",
      "amount": "12450.00"
    },
    "metadata": {
      "source": "checkout",
      "device_type": "mobile"
    }
  }'
```

**Response — 200 AUTHORIZED:**

```json
{
  "intent_id": "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b",
  "attempt_id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
  "merchant_order_id": "ORDER-20260527-001",
  "provider_name": "PSP_A",
  "provider_reference": "ref_pspa_9f8e7d6c",
  "status": "AUTHORIZED",
  "transaction_amount": { "currency": "USD", "amount": "150.00" },
  "settlement_amount": { "currency": "INR", "amount": "12450.00" },
  "timestamp": "2026-05-27T15:45:00Z"
}
```

**Response — 202 PENDING** (PSP timeout; reconciliation will resolve asynchronously):

```json
{
  "intent_id": "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b",
  "attempt_id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
  "provider_name": "PSP_A",
  "status": "PENDING",
  "message": "Payment outcome pending reconciliation",
  "timestamp": "2026-05-27T15:45:00Z"
}
```

The `Idempotency-Key` header is **required**. Replaying the same key within 24 hours returns the cached response without re-processing.

---

### Ingest a PSP Webhook

```bash
curl -X POST http://localhost:8080/api/v1/payments-orchestration/webhooks/PSP_A \
  -H "Content-Type: application/json" \
  -H "X-PSP-Signature: <hmac-sha256-signature>" \
  -d '{
    "event_id": "evt_001",
    "event_type": "PAYMENT_AUTHORIZED",
    "provider_reference": "ref_pspa_9f8e7d6c",
    "intent_id": "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b",
    "attempt_id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
    "status": "AUTHORIZED",
    "timestamp": "2026-05-27T15:45:30Z"
  }'
```

**Response — 200:**

```json
{
  "acknowledged": true,
  "event_id": "evt_001"
}
```

The `{provider}` path variable must match a configured PSP name (`PSP_A` or `PSP_B`). The `X-PSP-Signature` header is verified against the raw request body using the configured `webhook-secret` for the provider.

---

## Configuration Reference

All application-specific properties are namespaced under `orchestrator.*` in `application.yml`.

| Property | Default | Description |
|---|---|---|
| `orchestrator.idempotency.ttl-hours` | `24` | How long idempotency records are retained in Redis and the database. Replays within this window return the cached response. |
| `orchestrator.replay-protection.timestamp-window-minutes` | `5` | Maximum allowed age of the request timestamp. Requests older than this window are rejected to prevent replay attacks. |
| `orchestrator.replay-protection.nonce-window-minutes` | `10` | TTL for nonce entries in Redis. Duplicate nonces within this window are rejected. |
| `orchestrator.routing.CARD` | `PSP_A` | PSP assigned to route `CARD` payment method type. |
| `orchestrator.routing.UPI` | `PSP_B` | PSP assigned to route `UPI` payment method type. |
| `orchestrator.psp.psp-a.mode` | `SUCCESS` | Simulated PSP_A connector behaviour: `SUCCESS`, `FAILURE`, or `TIMEOUT`. |
| `orchestrator.psp.psp-b.mode` | `SUCCESS` | Simulated PSP_B connector behaviour: `SUCCESS`, `FAILURE`, or `TIMEOUT`. |
| `orchestrator.psp.psp-a.webhook-secret` | `secret_psp_a` | HMAC secret used to verify webhook signatures from PSP_A. |
| `orchestrator.psp.psp-b.webhook-secret` | `secret_psp_b` | HMAC secret used to verify webhook signatures from PSP_B. |
| `orchestrator.security.key-rotation.grace-period-minutes` | `60` | Grace period during key rotation, within which both old and new secrets are accepted. |
| `orchestrator.workers.outbox-publisher.interval-ms` | `1500` | Fixed delay between outbox polling cycles (milliseconds). |
| `orchestrator.workers.reconciliation.interval-ms` | `45000` | Fixed delay between reconciliation polling cycles (milliseconds). |
| `orchestrator.workers.retry.interval-ms` | `2000` | Fixed delay between retry polling cycles (milliseconds). |
| `orchestrator.workers.retry.base-backoff-ms` | `1000` | Base delay for exponential backoff in the retry worker. |
| `orchestrator.workers.retry.max-backoff-ms` | `300000` | Maximum cap for retry exponential backoff (5 minutes). |
| `orchestrator.workers.retry.max-attempts` | `5` | Maximum number of PSP retry attempts before marking a payment failed. |

To simulate different PSP outcomes without code changes, set the mode property in `application-local.yml`:

```yaml
orchestrator:
  psp:
    psp-a:
      mode: TIMEOUT   # Force PSP_A to always time out → triggers PENDING + reconciliation
```

---

## Background Workers

Three scheduled workers run in-process on fixed delays, all using `SELECT FOR UPDATE SKIP LOCKED` to safely distribute work across multiple instances.

| Worker | Class | Interval | Trigger | Batch Size |
|---|---|---|---|---|
| **Outbox Publisher** | `OutboxPublisherWorker` | 1,500 ms | Fixed delay after previous cycle completes | 50 records |
| **Reconciliation Worker** | `ReconciliationWorker` | 45,000 ms | Fixed delay; exponential backoff per intent (60s × 2^n, capped at 15 min) | 50 intents |
| **Retry Worker** | `RetryWorker` | 2,000 ms | Fixed delay; exponential backoff per attempt (`baseMs × 2^retryCount`, capped at 5 min) | 50 intents |

**Outbox Publisher** polls `payment_outbox` for `PENDING` entries and publishes them via the `EventPublisher` interface. In `local` and `test` profiles, `InMemoryEventPublisher` is used instead of a live Kafka broker. After 5 consecutive publish failures, the record is moved to `FAILED` (dead-letter) status and a `payment.outbox.dlq.count` counter is incremented.

**Reconciliation Worker** targets `PENDING` intents, queries the PSP for the current transaction status, and transitions the intent to `AUTHORIZED` or `FAILED` based on the response. Each intent's `updatedAt` is bumped on selection to push it to the back of the queue.

**Retry Worker** targets `PENDING` intents whose last attempt failed with a retry-safe error code (e.g., `CONNECT_TIMEOUT`, `PSP_TIMEOUT`, `DNS_FAILURE`). It re-invokes the PSP authorization after the applicable backoff elapses.

---

## Actuator Endpoints

The service exposes the following Spring Boot Actuator endpoints. All are accessible without authentication in the default configuration.

| Endpoint | URL | Description |
|---|---|---|
| Health | `GET /actuator/health` | Composite health status. Returns `UP`/`DOWN` per component. Includes custom indicators for Database (PostgreSQL ping + response latency), Redis (connection probe), and Kafka (simulated; always `UP` in local profile). |
| Prometheus Metrics | `GET /actuator/prometheus` | Micrometer metrics in Prometheus text format. Includes payment-specific gauges: `payment.outbox.lag`, `payment.retry.lag`, `payment.reconciliation.backlog`, and counters: `payment.outbox.publish.failure`, `payment.outbox.dlq.count`. |
| Info | `GET /actuator/info` | Application info (name, version from build properties). |

Sample health response:

```json
{
  "status": "UP",
  "components": {
    "database": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "SELECT 1",
        "responseTimeMs": 3
      }
    },
    "redis": { "status": "UP" },
    "kafkaHealthIndicator": {
      "status": "UP",
      "details": {
        "broker": "simulated-cluster",
        "topics": ["payment-events", "payment-outbox"]
      }
    }
  }
}
```

---

## Project Structure

```
payment-orchestration-service/
├── build.ps1                         # Windows build entrypoint; bootstraps JDK + Maven
├── setup_toolchain.ps1               # Downloads Temurin JDK 21 + Maven 3.9.6 to .tools/
├── run_tests.ps1                     # Convenience script: runs the full test suite
├── docker-compose.yml                # PostgreSQL 15 + Redis 7 service definitions
├── pom.xml                           # Maven POM (Spring Boot 3.3, Java 21)
├── src/
│
└── docs/
    ├── architecture.md                # Full architecture deep-dive
    ├── reconciliation.md              # Reconciliation subsystem design
    ├── swagger.yaml                   # OpenAPI 3.0 contract
    ├── master_context.md              # Authoritative system specification
    ├── FUNCTIONAL_REQUIREMENTS.md
    ├── NON_FUNCTIONAL_REQUIREMENTS.md
    ├── BUG_FIX_TRACKING.md
    ├── TEST_CASES.md
    └── PROMPT_LOG.md
    ├── main/
    │   ├── java/com/payments/orchestrator/
    │   │   ├── PaymentOrchestratorApplication.java
    │   │   ├── config/               # Spring beans, scheduling config
    │   │   ├── controller/
    │   │   │   ├── PaymentController.java   # POST /payments
    │   │   │   └── WebhookController.java   # POST /webhooks/{provider}
    │   │   ├── domain/               # JPA entities
    │   │   │   ├── PaymentIntent.java
    │   │   │   ├── PaymentAttempt.java
    │   │   │   ├── PaymentEvent.java        # Immutable audit trail
    │   │   │   ├── PaymentOutbox.java       # Transactional outbox record
    │   │   │   ├── PaymentIdempotency.java
    │   │   │   ├── ProcessedWebhook.java    # Webhook deduplication record
    │   │   │   └── [enums: IntentStatus, AttemptStatus, OutboxStatus, ...]
    │   │   ├── dto/                  # Request/response DTOs (Jackson + Swagger)
    │   │   ├── exception/            # Domain exceptions + GlobalExceptionHandler
    │   │   ├── health/
    │   │   │   ├── DatabaseHealthIndicator.java
    │   │   │   ├── RedisHealthIndicator.java
    │   │   │   └── KafkaHealthIndicator.java
    │   │   ├── repository/           # Spring Data JPA repositories (SKIP LOCKED queries)
    │   │   ├── security/
    │   │   │   ├── CachedBodyHttpServletRequest.java  # Raw body caching for HMAC
    │   │   │   └── InMemoryMerchantSecretResolver.java
    │   │   ├── service/
    │   │   │   ├── PaymentOrchestrationFlowManager[Impl].java
    │   │   │   ├── PaymentOrchestrationService[Impl].java
    │   │   │   ├── IdempotencyService[Impl].java
    │   │   │   ├── RoutingEngine[Impl].java
    │   │   │   ├── PspConnector.java           # Interface
    │   │   │   ├── PspAConnector.java           # CARD → PSP_A (mode: SUCCESS|FAILURE|TIMEOUT)
    │   │   │   ├── PspBConnector.java           # UPI  → PSP_B (mode: SUCCESS|FAILURE|TIMEOUT)
    │   │   │   ├── WebhookService[Impl].java
    │   │   │   ├── ReconciliationService[Impl].java
    │   │   │   ├── RetryService[Impl].java
    │   │   │   ├── EventPublisher.java          # Interface
    │   │   │   ├── InMemoryEventPublisher.java  # Active in local + test profiles
    │   │   │   └── KafkaEventPublisher.java     # Production target
    │   │   └── worker/
    │   │       ├── OutboxPublisherWorker.java   # 1.5s fixed-delay poll
    │   │       ├── ReconciliationWorker.java    # 45s fixed-delay poll
    │   │       ├── RetryWorker.java             # 2s fixed-delay poll
    │   │       ├── IdempotencyPruningScheduler.java
    │   │       └── OutboxPruningScheduler.java
    │   └── resources/
    │       ├── application.yml                  # Base config (profile: local)
    │       ├── application-local.yml            # Local DB + Redis coordinates
    │       ├── logback-spring.xml               # JSON logging (Logstash encoder)
    │       └── db/migration/
    │           ├── V1__init_schema.sql
    │           └── V2__add_manual_review_status.sql
    │
    └── test/
        ├── java/com/payments/orchestrator/
        │   ├── BaseIntegrationTest.java              # Testcontainers base (Redis dynamic port)
        │   ├── ContainerIntegrationTests.java
        │   ├── PaymentOrchestrationIntegrationTests.java
        │   ├── PaymentOrchestrationFlowTests.java
        │   ├── PaymentLifecycleStateMachineTests.java
        │   ├── IdempotencyServiceTests.java
        │   ├── OutboxPublishingIntegrationTests.java
        │   ├── ReconciliationWorkerTests.java
        │   ├── RetryWorkerTests.java
        │   ├── WebhookIngestionTests.java
        │   ├── PspRoutingAndResilienceTests.java
        │   ├── SecurityValidationTests.java
        │   ├── ObservabilityHardeningTests.java
        │   ├── PersistenceSchemaIntegrationTests.java
        │   ├── ApiSchemaLayerTests.java
        │   └── PaymentOrchestrationServiceTests.java
        └── resources/
            └── application-test.yml              # tc: JDBC URL; Redis via DynamicPropertySource
```

---

## Testing

### Test Architecture

All integration tests extend `BaseIntegrationTest`, which uses the `@Testcontainers` + `@SpringBootTest` combo:

- **PostgreSQL**: started via the Testcontainers JDBC URL (`jdbc:tc:postgresql:15-alpine:///payment_orchestrator`). Flyway migrations run automatically on startup.
- **Redis**: started as a `GenericContainer`; the mapped port is injected via `@DynamicPropertySource`. This avoids conflicts with any locally running Redis instance.
- **PSP connectors**: remain active but are configured for `SUCCESS` mode by default in tests. Individual tests override the `mode` directly via `PspAConnector.setMode(...)`.
- **Event publishing**: `InMemoryEventPublisher` is active under the `test` profile — no Kafka broker is required.

### Test Suites

| Suite | Type | What it covers |
|---|---|---|
| `PaymentOrchestrationIntegrationTests` | Integration | Atomic persistence (Intent + Attempt + Event + Outbox), merchant order uniqueness, transaction rollback correctness |
| `PaymentOrchestrationFlowTests` | Integration | End-to-end payment creation flow, idempotency replay, duplicate detection |
| `PaymentLifecycleStateMachineTests` | Integration | Legal and illegal state transitions for `IntentStatus` and `AttemptStatus` |
| `IdempotencyServiceTests` | Integration | TTL-based idempotency store, cache hit / cache miss behaviour |
| `OutboxPublishingIntegrationTests` | Integration | Outbox polling cycle, batch processing, SKIP LOCKED behaviour |
| `ReconciliationWorkerTests` | Integration | Backoff calculation, intent selection, PSP status reconciliation |
| `RetryWorkerTests` | Integration | Retry-safe error code filtering, exponential backoff enforcement |
| `WebhookIngestionTests` | Integration | Signature verification, duplicate webhook deduplication, state transitions |
| `PspRoutingAndResilienceTests` | Integration | CARD→PSP_A, UPI→PSP_B routing; circuit breaker open/close transitions |
| `SecurityValidationTests` | Integration | Replay attack rejection, nonce deduplication, HMAC failures |
| `ObservabilityHardeningTests` | Integration | Prometheus metric counters and gauge values after key operations |
| `PersistenceSchemaIntegrationTests` | Integration | Schema integrity, constraint violations, Flyway migration completeness |
| `ApiSchemaLayerTests` | Integration | Request validation (missing fields, bad enums, constraint violations) |
| `PaymentOrchestrationServiceTests` | Unit | Service-layer logic with mocked repositories and PSP connectors |

### Running Tests

**On Windows (using the project's local toolchain — no system Java needed):**

```powershell
# Run the full test suite
powershell -ExecutionPolicy Bypass -File .\build.ps1 test

# Or use the convenience wrapper:
powershell -ExecutionPolicy Bypass -File .\run_tests.ps1
```

**On macOS / Linux (system Java + Maven required):**

```bash
mvn test
```

**Run a single test class:**

```powershell
# Windows
powershell -ExecutionPolicy Bypass -File .\build.ps1 `-pl . -Dtest=PaymentOrchestrationIntegrationTests test`

# Linux / macOS
mvn -Dtest=PaymentOrchestrationIntegrationTests test
```

> **Docker must be running** when executing integration tests. Testcontainers will pull `postgres:15-alpine` and `redis:7-alpine` images on the first run (~150 MB total) and start ephemeral containers automatically.

---

## Key Design Decisions

- **Transactional Outbox over direct Kafka publish.** Publishing events inside the payment transaction would create a dual-write risk: the DB commit could succeed while the Kafka publish fails (or vice versa), leaving the system in an inconsistent state. Instead, the outbox record is committed atomically with the payment state. The `OutboxPublisherWorker` reliably delivers it downstream with at-least-once semantics, tolerating broker unavailability without data loss.

- **`SELECT FOR UPDATE SKIP LOCKED` in all workers.** Using `SKIP LOCKED` instead of `FOR UPDATE` eliminates lock-wait queuing: workers never block each other on contended rows. Each worker instance picks up a disjoint batch, enabling horizontal scaling of workers without coordination overhead or starvation.

- **HMAC-based webhook authentication with body caching.** Spring's `HttpServletRequest` input stream can only be read once. A `CachedBodyHttpServletRequest` servlet filter wrapper buffers the raw request body so it can be both deserialized (Jackson) and HMAC-verified against the raw bytes (required for exact signature match) within the same request lifecycle.

- **Simulated PSP connectors with configurable modes.** Real PSP integrations require credentials, network access, and sandbox accounts that obstruct local development and CI. The `mode` property (`SUCCESS` | `FAILURE` | `TIMEOUT`) lets the full system — including circuit breakers, retry workers, and reconciliation — be exercised locally and in tests without any external dependency, while the `PspConnector` interface allows a real HTTP client to be dropped in without changing the orchestration logic.

---

## Further Documentation

- **[architecture.md](./docs/architecture.md)** — Detailed architecture documentation covering the database schema, event model, idempotency implementation, webhook security model, circuit breaker configuration, and observability stack.
- **[reconciliation.md](./docs/reconciliation.md)** — In-depth design of the reconciliation subsystem: backoff formula, polling strategy, and state machine transitions.
- **[swagger.yaml](./docs/swagger.yaml)** — Full OpenAPI 3.0 contract. When the application is running, the interactive Swagger UI is available at `http://localhost:8080/swagger-ui.html`.

---

## License

This project is licensed under the [GNU License](./LICENSE).
