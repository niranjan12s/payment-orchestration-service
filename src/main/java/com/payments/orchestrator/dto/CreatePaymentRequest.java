package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Request body to initiate a payment authorization")
public class CreatePaymentRequest {

    @JsonProperty("merchant_id")
    @Schema(description = "UUID of the merchant initiating the payment.", example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "merchant_id must not be null")
    private UUID merchantId;

    @JsonProperty("merchant_order_id")
    @Schema(description = "Merchant's own unique order identifier. Used for business-level deduplication.", maxLength = 255, example = "ORDER-20260527-001")
    @NotBlank(message = "merchant_order_id must not be blank")
    @Size(max = 255, message = "merchant_order_id must not exceed 255 characters")
    private String merchantOrderId;

    @JsonProperty("payment_method_type")
    @Schema(description = "Payment method category.", allowableValues = {"CARD", "UPI"}, example = "CARD")
    @NotNull(message = "payment_method_type must not be null")
    @Pattern(regexp = "^(CARD|UPI)$", message = "payment_method_type must be CARD or UPI")
    private String paymentMethodType;

    @JsonProperty("payment_token_reference")
    @Schema(description = "Reference to a tokenized payment instrument in an external vault.", maxLength = 512, example = "vault_token_abc123")
    @NotBlank(message = "payment_token_reference must not be blank")
    @Size(max = 512, message = "payment_token_reference must not exceed 512 characters")
    private String paymentTokenReference;

    @JsonProperty("transaction_amount")
    @Schema(description = "The original transaction currency and amount.")
    @NotNull(message = "transaction_amount must not be null")
    @Valid
    private MoneyAmount transactionAmount;

    @JsonProperty("settlement_amount")
    @Schema(description = "The target settlement currency and amount.")
    @NotNull(message = "settlement_amount must not be null")
    @Valid
    private MoneyAmount settlementAmount;

    @JsonProperty("metadata")
    @Schema(description = "Optional arbitrary key-value metadata.", example = "{\"source\": \"checkout\", \"device_type\": \"mobile\"}")
    private Map<String, String> metadata;

    // Constructors
    public CreatePaymentRequest() {}

    public CreatePaymentRequest(UUID merchantId, String merchantOrderId, String paymentMethodType, String paymentTokenReference, MoneyAmount transactionAmount, MoneyAmount settlementAmount, Map<String, String> metadata) {
        this.merchantId = merchantId;
        this.merchantOrderId = merchantOrderId;
        this.paymentMethodType = paymentMethodType;
        this.paymentTokenReference = paymentTokenReference;
        this.transactionAmount = transactionAmount;
        this.settlementAmount = settlementAmount;
        this.metadata = metadata;
    }

    // Getters and Setters
    public UUID getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(UUID merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantOrderId() {
        return merchantOrderId;
    }

    public void setMerchantOrderId(String merchantOrderId) {
        this.merchantOrderId = merchantOrderId;
    }

    public String getPaymentMethodType() {
        return paymentMethodType;
    }

    public void setPaymentMethodType(String paymentMethodType) {
        this.paymentMethodType = paymentMethodType;
    }

    public String getPaymentTokenReference() {
        return paymentTokenReference;
    }

    public void setPaymentTokenReference(String paymentTokenReference) {
        this.paymentTokenReference = paymentTokenReference;
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

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
