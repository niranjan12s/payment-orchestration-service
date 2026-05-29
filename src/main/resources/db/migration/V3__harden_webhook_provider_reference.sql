-- V3: Webhook correlation must be unique per provider when a PSP reference exists.
-- Multiple NULL provider references remain allowed while attempts are still pending.
CREATE UNIQUE INDEX IF NOT EXISTS uq_attempt_provider_reference
ON payment_attempts(provider_name, provider_reference)
WHERE provider_reference IS NOT NULL;
