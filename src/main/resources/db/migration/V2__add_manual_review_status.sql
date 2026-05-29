-- V2: Alter check constraint on payment_intents to include MANUAL_REVIEW status
ALTER TABLE payment_intents DROP CONSTRAINT IF EXISTS payment_intents_status_check;

ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_status_check
    CHECK (status IN ('CREATED', 'PROCESSING', 'AUTHORIZED', 'FAILED', 'PENDING', 'MANUAL_REVIEW'));
