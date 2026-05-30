-- 1. payment_intents table
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
    active_attempt_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_merchant_order
        UNIQUE (merchant_id, merchant_order_id)
);

-- 2. payment_attempts table
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
            'PENDING'
        )),
    retry_count INT NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 3. payment_events table
CREATE TABLE payment_events (
    event_id UUID PRIMARY KEY,
    intent_id UUID NOT NULL,
    attempt_id UUID,
    correlation_id VARCHAR(255),
    event_type VARCHAR(100) NOT NULL,
    event_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 4. payment_idempotency table
CREATE TABLE payment_idempotency (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    request_hash VARCHAR(512) NOT NULL,
    response_payload JSONB NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- 5. payment_outbox table
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
    source_event_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP WITH TIME ZONE
);

-- 6. processed_webhooks table
CREATE TABLE processed_webhooks (
    id BIGSERIAL PRIMARY KEY,
    provider_name VARCHAR(100) NOT NULL,
    provider_event_id VARCHAR(255) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_provider_event
        UNIQUE(provider_name, provider_event_id)
);

-- 7. Required Indexes
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

-- Cleanup Indexes for TTL Jobs (Idempotency 24h & Processed Outbox Pruning)
CREATE INDEX idx_idempotency_expires_at
ON payment_idempotency(expires_at);

CREATE INDEX idx_outbox_cleanup
ON payment_outbox(status, processed_at)
WHERE status = 'PROCESSED';
