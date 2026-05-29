package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Lightweight status-only check for a payment intent")
public class PaymentStatusResponse {

    @JsonProperty("intent_id")
    @Schema(description = "UUID of the payment intent.", example = "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b")
    private UUID intentId;

    @JsonProperty("merchant_order_id")
    @Schema(description = "Merchant unique order identifier.", example = "ORDER-20260527-001")
    private String merchantOrderId;

    @JsonProperty("status")
    @Schema(description = "Canonical lifecycle state from the payment_intents table.", allowableValues = {"CREATED", "PROCESSING", "AUTHORIZED", "FAILED", "PENDING", "SUPERSEDED"}, example = "AUTHORIZED")
    private String status;

    @JsonProperty("updated_at")
    @Schema(description = "ISO 8601 UTC timestamp of last state transition.", example = "2026-05-27T12:00:02Z")
    private OffsetDateTime updatedAt;

    // Constructors
    public PaymentStatusResponse() {}

    public PaymentStatusResponse(UUID intentId, String merchantOrderId, String status, OffsetDateTime updatedAt) {
        this.intentId = intentId;
        this.merchantOrderId = merchantOrderId;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    // Getters and Setters
    public UUID getIntentId() {
        return intentId;
    }

    public void setIntentId(UUID intentId) {
        this.intentId = intentId;
    }

    public String getMerchantOrderId() {
        return merchantOrderId;
    }

    public void setMerchantOrderId(String merchantOrderId) {
        this.merchantOrderId = merchantOrderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
