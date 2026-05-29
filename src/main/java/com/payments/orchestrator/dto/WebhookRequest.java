package com.payments.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public class WebhookRequest {

    @NotBlank(message = "event_id must not be blank")
    @JsonProperty("event_id")
    private String eventId;

    @NotBlank(message = "event_type must not be blank")
    @JsonProperty("event_type")
    private String eventType;

    @NotBlank(message = "provider_reference must not be blank")
    @JsonProperty("provider_reference")
    private String providerReference;

    @JsonProperty("intent_id")
    private UUID intentId;

    @JsonProperty("attempt_id")
    private UUID attemptId;

    @NotBlank(message = "status must not be blank")
    @JsonProperty("status")
    private String status;

    @NotNull(message = "timestamp must not be null")
    @JsonProperty("timestamp")
    private OffsetDateTime timestamp;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    public WebhookRequest() {
    }

    public WebhookRequest(String eventId, String eventType, String providerReference, UUID intentId, UUID attemptId, String status, OffsetDateTime timestamp, Map<String, Object> metadata) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.providerReference = providerReference;
        this.intentId = intentId;
        this.attemptId = attemptId;
        this.status = status;
        this.timestamp = timestamp;
        this.metadata = metadata;
    }

    // Getters and Setters
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public void setProviderReference(String providerReference) {
        this.providerReference = providerReference;
    }

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

    public void setStatus(String status) {
        this.status = status;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
