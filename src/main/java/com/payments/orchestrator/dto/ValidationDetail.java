package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Represents a specific validation failure detail for a field")
public class ValidationDetail {

    @JsonProperty("field")
    @Schema(description = "JSON path or header name of the offending field.", example = "transaction_amount.currency_code")
    private String field;

    @JsonProperty("issue")
    @Schema(description = "Human-readable description of the constraint violation.", example = "must be a valid ISO 4217 3-letter currency code")
    private String issue;

    // Constructors
    public ValidationDetail() {}

    public ValidationDetail(String field, String issue) {
        this.field = field;
        this.issue = issue;
    }

    // Getters and Setters
    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getIssue() {
        return issue;
    }

    public void setIssue(String issue) {
        this.issue = issue;
    }
}
