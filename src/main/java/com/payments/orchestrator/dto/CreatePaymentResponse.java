package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Schema(description = "Unified payment creation response which encapsulates both successful authorization or pending reconciliation outcomes")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreatePaymentResponse {

    @JsonProperty("intent_id")
    @Schema(description = "UUID of the payment intent.", example = "7f3e2c1a-4b5d-6e7f-8a9b-0c1d2e3f4a5b")
    private UUID intentId;

    @JsonProperty("attempt_id")
    @Schema(description = "UUID of the attempt execution.", example = "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d")
    private UUID attemptId;

    @JsonProperty("merchant_order_id")
    @Schema(description = "Merchant unique order identifier. (Null for pending response if not mapped).", example = "ORDER-20260527-001")
    private String merchantOrderId;

    @JsonProperty("provider_name")
    @Schema(description = "PSP provider name.", allowableValues = {"PSP_A", "PSP_B"}, example = "PSP_A")
    private String providerName;

    @JsonProperty("provider_reference")
    @Schema(description = "PSP transaction reference, used for reconciliation. (Only returned for AUTHORIZED status).", example = "provider_tx_9f8e7d6c")
    private String providerReference;

    @JsonProperty("status")
    @Schema(description = "Status of the payment intent.", allowableValues = {"AUTHORIZED", "PENDING"}, example = "AUTHORIZED")
    private String status;

    @JsonProperty("transaction_amount")
    private MoneyAmount transactionAmount;

    @JsonProperty("settlement_amount")
    private MoneyAmount settlementAmount;

    @JsonProperty("message")
    @Schema(description = "Informational message (Only returned for PENDING status).", example = "Payment outcome pending reconciliation")
    private String message;

    @JsonProperty("timestamp")
    @Schema(description = "ISO 8601 UTC timestamp of response creation.", example = "2026-05-27T12:00:00Z")
    private OffsetDateTime timestamp;

    // Constructors
    public CreatePaymentResponse() {}

    // Factory builders
    public static CreatePaymentResponse authorized(PaymentAuthorizedResponse auth) {
        CreatePaymentResponse res = new CreatePaymentResponse();
        res.intentId = auth.getIntentId();
        res.attemptId = auth.getAttemptId();
        res.merchantOrderId = auth.getMerchantOrderId();
        res.providerName = auth.getProviderName();
        res.providerReference = auth.getProviderReference();
        res.status = auth.getStatus();
        res.transactionAmount = auth.getTransactionAmount();
        res.settlementAmount = auth.getSettlementAmount();
        res.timestamp = auth.getTimestamp();
        return res;
    }

    public static CreatePaymentResponse pending(PaymentPendingResponse pending) {
        CreatePaymentResponse res = new CreatePaymentResponse();
        res.intentId = pending.getIntentId();
        res.attemptId = pending.getAttemptId();
        res.providerName = pending.getProviderName();
        res.status = pending.getStatus();
        res.message = pending.getMessage();
        res.timestamp = pending.getTimestamp();
        return res;
    }

    public static CreatePaymentResponse failed(UUID intentId, UUID attemptId, String merchantOrderId, String providerName, String message, MoneyAmount transactionAmount, MoneyAmount settlementAmount) {
        CreatePaymentResponse res = new CreatePaymentResponse();
        res.intentId = intentId;
        res.attemptId = attemptId;
        res.merchantOrderId = merchantOrderId;
        res.providerName = providerName;
        res.status = "FAILED";
        res.message = message;
        res.transactionAmount = transactionAmount;
        res.settlementAmount = settlementAmount;
        res.timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        return res;
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

    public void setStatus(String status) {
        this.status = status;
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
