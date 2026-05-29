package com.payments.orchestrator.controller;

import com.payments.orchestrator.domain.PaymentIntent;
import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.PaymentStatusResponse;
import com.payments.orchestrator.exception.PaymentNotFoundException;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.security.CachedBodyHttpServletRequest;
import com.payments.orchestrator.service.PaymentOrchestrationFlowManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments-orchestration")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private PaymentOrchestrationFlowManager flowManager;

    @Autowired
    private PaymentIntentRepository intentRepository;

    @PostMapping("/payments")
    public ResponseEntity<CreatePaymentResponse> createPayment(
            @RequestBody @Valid CreatePaymentRequest request,
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            HttpServletRequest servletRequest
    ) {
        log.info("REST API request received for payment authorization. Order ID: {}", request.getMerchantOrderId());

        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be null or blank");
        }

        // Retrieve raw JSON body string directly from the CachedBodyHttpServletRequest servlet wrapper
        String rawBody = "";
        if (servletRequest instanceof CachedBodyHttpServletRequest cachedReq) {
            rawBody = new String(cachedReq.getCachedBody(), StandardCharsets.UTF_8);
        } else {
            log.warn("servletRequest is not an instance of CachedBodyHttpServletRequest. Falling back to empty body string.");
        }

        CreatePaymentResponse response = flowManager.processPayment(request, idempotencyKey, rawBody);

        // Map synchronous flow outcomes to spec-compliant HTTP statuses
        if ("PENDING".equalsIgnoreCase(response.getStatus())) {
            log.info("Returning 202 ACCEPTED for pending payment intent: {}", response.getIntentId());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        log.info("Returning 200 OK for terminal {} payment intent: {}", response.getStatus(), response.getIntentId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/payments/{intentId}")
    @Transactional(readOnly = true)
    public ResponseEntity<PaymentIntent> getIntent(@PathVariable("intentId") UUID intentId) {
        log.info("REST API request received to fetch payment intent: {}", intentId);
        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment intent not found with ID: " + intentId));
        // Eagerly load attempts size to avoid lazy loading issues
        intent.getAttempts().size();
        return ResponseEntity.ok(intent);
    }

    @GetMapping("/payments/status/{merchantOrderId}")
    @Transactional(readOnly = true)
    public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable("merchantOrderId") String merchantOrderId) {
        log.info("REST API request received to fetch status for merchant order: {}", merchantOrderId);
        PaymentIntent intent = intentRepository.findAll().stream()
                .filter(i -> merchantOrderId.equals(i.getMerchantOrderId()))
                .max(java.util.Comparator.comparing(PaymentIntent::getUpdatedAt))
                .orElseThrow(() -> new PaymentNotFoundException("Payment intent not found with Order ID: " + merchantOrderId));

        PaymentStatusResponse statusResponse = new PaymentStatusResponse(
                intent.getIntentId(),
                intent.getMerchantOrderId(),
                intent.getStatus().name(),
                intent.getUpdatedAt()
        );
        return ResponseEntity.ok(statusResponse);
    }
}
