package com.payments.orchestrator.service;

public interface PaymentInstrumentResolver {

    /**
     * Securely resolves a payment token reference into its actionable details (e.g. mock card/account details).
     */
    String resolveInstrument(String paymentTokenReference);
}
