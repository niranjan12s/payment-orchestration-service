package com.payments.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentInstrumentResolverImpl implements PaymentInstrumentResolver {

    private static final Logger log = LoggerFactory.getLogger(PaymentInstrumentResolverImpl.class);

    @Override
    public String resolveInstrument(String paymentTokenReference) {
        log.info("Resolving secure payment instrument from vault token: {}", paymentTokenReference);
        
        if (paymentTokenReference == null || paymentTokenReference.trim().isEmpty()) {
            throw new IllegalArgumentException("Payment token reference cannot be blank");
        }
        
        // Mock card or UPI details string for simulation (no real card details printed or logged)
        return "resolved_instrument_details_for_token:" + paymentTokenReference;
    }
}
