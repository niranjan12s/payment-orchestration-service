package com.payments.orchestrator.service;

import com.payments.orchestrator.domain.PaymentMethodType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RoutingEngineImpl implements RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngineImpl.class);

    @Autowired
    private PspAConnector pspAConnector;

    @Autowired
    private PspBConnector pspBConnector;

    @Override
    public PspConnector selectConnector(PaymentMethodType methodType) {
        log.info("Selecting appropriate PSP connector for payment method type: {}", methodType);
        
        if (methodType == null) {
            throw new IllegalArgumentException("Payment method type must not be null");
        }

        return switch (methodType) {
            case CARD -> pspAConnector;
            case UPI -> pspBConnector;
        };
    }
}
