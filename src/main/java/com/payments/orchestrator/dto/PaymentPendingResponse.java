package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Response returned when a payment is in ambiguous timeout status and awaits async reconciliation")
public class PaymentPendingResponse {

    @JsonProperty("intent_id")
    @Schema(description = "UUID of the payment intent.", example = "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b")
    private UUID intentId;

    @JsonProperty("attempt_id")
    @Schema(description = "UUID of the pending attempt execution.", example = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d")
    private UUID attemptId;

    @JsonProperty("status")
    @Schema(description = "Status of the payment intent.", example = "PENDING")
    private final String status = "PENDING";

    @JsonProperty("provider_name")
    @Schema(description = "PSP provider name.", allowableValues = {"PSP_A", "PSP_B"}, example = "PSP_A")
    private String providerName;

    @JsonProperty("message")
    @Schema(description = "Informational status message.", example = "Payment outcome pending reconciliation")
    private String message;

    @JsonProperty("timestamp")
    @Schema(description = "ISO 8601 UTC timestamp of response creation.", example = "2026-05-27T12:00:00Z")
    private OffsetDateTime timestamp;

    // Constructors
    public PaymentPendingResponse() {}

    public PaymentPendingResponse(UUID intentId, UUID attemptId, String providerName, String message, OffsetDateTime timestamp) {
        this.intentId = intentId;
        this.attemptId = attemptId;
        this.providerName = providerName;
        this.message = message;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public UUID getIntentId() {
        return intentId;
    }

    public void setIntentId(UUID intentId) {
        this.intentId = intentId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public void setAttemptId(UUID attemptId) {
        this.attemptId = attemptId;
    }

    public String getStatus() {
        return status;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
