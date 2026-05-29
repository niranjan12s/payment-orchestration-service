package com.payments.orchestrator.domain;

public enum PaymentStatus {
    CREATED,
    PROCESSING,
    AUTHORIZED,
    FAILED,
    PENDING,
    MANUAL_REVIEW
}
