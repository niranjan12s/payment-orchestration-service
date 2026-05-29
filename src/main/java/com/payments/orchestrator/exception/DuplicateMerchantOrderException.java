package com.payments.orchestrator.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(value = HttpStatus.CONFLICT, reason = "DUPLICATE_MERCHANT_ORDER")
public class DuplicateMerchantOrderException extends RuntimeException {

    public DuplicateMerchantOrderException(String message) {
        super(message);
    }

    public DuplicateMerchantOrderException(String merchantId, String merchantOrderId) {
        super(String.format("Duplicate merchant order for merchantId '%s' and orderId '%s'", merchantId, merchantOrderId));
    }
}
