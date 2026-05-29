package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentMethodType;

public interface RoutingEngine {

    /**
     * Resolves the appropriate PSP connector based on payment method category and health.
     */
    PspConnector selectConnector(PaymentMethodType methodType);
}
