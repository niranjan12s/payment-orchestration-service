package com.payments.orchestrator.dto;

public class IdempotencyResult {

    public enum Status {
        ABSENT,
        MATCH,
        CONFLICT
    }

    private final Status status;
    private final CreatePaymentResponse cachedResponse;
    private final String requestHash;

    private IdempotencyResult(Status status, CreatePaymentResponse cachedResponse, String requestHash) {
        this.status = status;
        this.cachedResponse = cachedResponse;
        this.requestHash = requestHash;
    }

    public static IdempotencyResult absent(String requestHash) {
        return new IdempotencyResult(Status.ABSENT, null, requestHash);
    }

    public static IdempotencyResult match(CreatePaymentResponse cachedResponse) {
        return new IdempotencyResult(Status.MATCH, cachedResponse, null);
    }

    public static IdempotencyResult conflict() {
        return new IdempotencyResult(Status.CONFLICT, null, null);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isMatch() {
        return status == Status.MATCH;
    }

    public boolean isConflict() {
        return status == Status.CONFLICT;
    }

    public boolean isAbsent() {
        return status == Status.ABSENT;
    }

    public CreatePaymentResponse getCachedResponse() {
        return cachedResponse;
    }

    public String getRequestHash() {
        return requestHash;
    }
}
