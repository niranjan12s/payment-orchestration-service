package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

@Schema(description = "Represents a monetary amount and currency pair")
public class MoneyAmount {

    @JsonProperty("currency_code")
    @Schema(description = "ISO 4217 3-letter currency code.", minLength = 3, maxLength = 3, pattern = "^[A-Z]{3}$", example = "USD")
    @NotNull(message = "currency_code must not be null")
    @Pattern(regexp = "^[A-Z]{3}$", message = "currency_code must be a valid ISO 4217 3-letter currency code")
    private String currencyCode;

    @JsonProperty("amount")
    @Schema(description = "Positive monetary value. Precision to 2 decimal places. Must be greater than 0.", example = "100.00")
    @NotNull(message = "amount must not be null")
    @DecimalMin(value = "0.00", inclusive = false, message = "amount must be greater than 0")
    private BigDecimal amount;

    // Default constructor for Jackson
    public MoneyAmount() {}

    public MoneyAmount(String currencyCode, BigDecimal amount) {
        this.currencyCode = currencyCode;
        this.amount = amount;
    }

    // Getters and Setters
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
