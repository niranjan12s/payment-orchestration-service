package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.AttemptStatus;
import com.payments.orchestrator.domain.PaymentStatus;
import com.payments.orchestrator.dto.PspStatus;
import org.springframework.stereotype.Component;

@Component
public class PspErrorClassifier {

    public PaymentStatus getTargetIntentStatus(PspStatus pspStatus) {
        if (pspStatus == null) {
            return PaymentStatus.PENDING;
        }
        return switch (pspStatus) {
            case SUCCESS -> PaymentStatus.AUTHORIZED;
            case FAILED -> PaymentStatus.FAILED;
            case PENDING -> PaymentStatus.PENDING;
        };
    }

    public AttemptStatus getTargetAttemptStatus(PspStatus pspStatus) {
        if (pspStatus == null) {
            return AttemptStatus.PENDING;
        }
        return switch (pspStatus) {
            case SUCCESS -> AttemptStatus.AUTHORIZED;
            case FAILED -> AttemptStatus.FAILED;
            case PENDING -> AttemptStatus.PENDING;
        };
    }
}
