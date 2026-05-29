package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Response returned when a payment is successfully authorized")
public class PaymentAuthorizedResponse {

    @JsonProperty("intent_id")
    @Schema(description = "UUID of the payment intent.", example = "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b")
    private UUID intentId;

    @JsonProperty("attempt_id")
    @Schema(description = "UUID of the successful attempt execution.", example = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d")
    private UUID attemptId;

    @JsonProperty("merchant_order_id")
    @Schema(description = "Merchant unique order identifier.", example = "ORDER-20260527-001")
    private String merchantOrderId;

    @JsonProperty("provider_name")
    @Schema(description = "PSP provider name.", allowableValues = {"PSP_A", "PSP_B"}, example = "PSP_A")
    private String providerName;

    @JsonProperty("provider_reference")
    @Schema(description = "PSP transaction reference, used for reconciliation.", example = "provider_tx_9f8e7d6c")
    private String providerReference;

    @JsonProperty("status")
    @Schema(description = "Status of the payment intent.", example = "AUTHORIZED")
    private final String status = "AUTHORIZED";

    @JsonProperty("transaction_amount")
    private MoneyAmount transactionAmount;

    @JsonProperty("settlement_amount")
    private MoneyAmount settlementAmount;

    @JsonProperty("timestamp")
    @Schema(description = "ISO 8601 UTC timestamp of authorization.", example = "2026-05-27T12:00:00Z")
    private OffsetDateTime timestamp;

    // Constructors
    public PaymentAuthorizedResponse() {}

    public PaymentAuthorizedResponse(UUID intentId, UUID attemptId, String merchantOrderId, String providerName, String providerReference, MoneyAmount transactionAmount, MoneyAmount settlementAmount, OffsetDateTime timestamp) {
        this.intentId = intentId;
        this.attemptId = attemptId;
        this.merchantOrderId = merchantOrderId;
        this.providerName = providerName;
        this.providerReference = providerReference;
        this.transactionAmount = transactionAmount;
        this.settlementAmount = settlementAmount;
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

    public String getMerchantOrderId() {
        return merchantOrderId;
    }

    public void setMerchantOrderId(String merchantOrderId) {
        this.merchantOrderId = merchantOrderId;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

    public String getStatus() {
        return status;
    }

    public MoneyAmount getTransactionAmount() {
        return transactionAmount;
    }

    public void setTransactionAmount(MoneyAmount transactionAmount) {
        this.transactionAmount = transactionAmount;
    }

    public MoneyAmount getSettlementAmount() {
        return settlementAmount;
    }

    public void setSettlementAmount(MoneyAmount settlementAmount) {
        this.settlementAmount = settlementAmount;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
