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

/**
 * REST controller that exposes endpoints for creating and managing payment intents
 * within the Payment Orchestration system.
 */
@RestController
@RequestMapping("/api/v1/payments-orchestration")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private PaymentOrchestrationFlowManager flowManager;

    @Autowired
    private PaymentIntentRepository intentRepository;

    /**
     * Authorizes and creates a new payment intent based on the provided request details.
     * Ensures idempotency using the supplied idempotency key.
     *
     * @param request the payment details and configuration
     * @param idempotencyKey unique key to prevent duplicate processing of the transaction
     * @param servletRequest the HTTP servlet request containing raw body details
     * @return a response containing the payment intent creation status and details
     * @throws IllegalArgumentException if the idempotency key is missing or blank
     */
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

    /**
     * Retrieves the details of a specific payment intent by its unique identifier.
     *
     * @param intentId the unique UUID of the payment intent
     * @return the retrieved payment intent
     * @throws PaymentNotFoundException if no payment intent is found with the specified ID
     */
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

    /**
     * Retrieves the status of the latest payment intent associated with a merchant order ID.
     *
     * @param merchantOrderId the merchant-provided unique identifier for the order
     * @return the status response of the latest matching payment intent
     * @throws PaymentNotFoundException if no payment intent is found with the specified merchant order ID
     */
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
