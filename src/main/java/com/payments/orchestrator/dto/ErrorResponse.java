package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Schema(description = "Standardized error response returned for all 4xx and 5xx failures")
public class ErrorResponse {

    @JsonProperty("error_code")
    @Schema(description = "Machine-readable error code for programmatic handling.", example = "VALIDATION_ERROR")
    private String errorCode;

    @JsonProperty("message")
    @Schema(description = "Human-readable error description suitable for logging.", example = "Validation failed")
    private String message;

    @JsonProperty("details")
    @Schema(description = "Field-level validation details. Empty array for non-validation errors.")
    private List<ValidationDetail> details = new ArrayList<>();

    @JsonProperty("request_id")
    @Schema(description = "Echo of the X-Request-Id header (or system-generated trace ID if X-Request-Id was absent).", example = "req_abc123")
    private String requestId;

    @JsonProperty("timestamp")
    @Schema(description = "ISO 8601 UTC timestamp of error occurrence.", example = "2026-05-27T12:00:00Z")
    private OffsetDateTime timestamp;

    // Constructors
    public ErrorResponse() {}

    public ErrorResponse(String errorCode, String message, String requestId, OffsetDateTime timestamp) {
        this.errorCode = errorCode;
        this.message = message;
        this.requestId = requestId;
        this.timestamp = timestamp;
        this.details = new ArrayList<>();
    }

    public ErrorResponse(String errorCode, String message, List<ValidationDetail> details, String requestId, OffsetDateTime timestamp) {
        this.errorCode = errorCode;
        this.message = message;
        this.details = details != null ? details : new ArrayList<>();
        this.requestId = requestId;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ValidationDetail> getDetails() {
        return details;
    }

    public void setDetails(List<ValidationDetail> details) {
        this.details = details != null ? details : new ArrayList<>();
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
