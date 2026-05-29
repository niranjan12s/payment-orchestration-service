package com.payments.orchestrator.dto;

public class PspResponse {

    private final PspStatus status;
    private final String providerReference;
    private final String errorCode;
    private final String errorMessage;

    public PspResponse(PspStatus status, String providerReference, String errorCode, String errorMessage) {
        this.status = status;
        this.providerReference = providerReference;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public PspStatus getStatus() {
        return status;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "PspResponse{" +
                "status=" + status +
                ", providerReference='" + providerReference + '\'' +
                ", errorCode='" + errorCode + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
