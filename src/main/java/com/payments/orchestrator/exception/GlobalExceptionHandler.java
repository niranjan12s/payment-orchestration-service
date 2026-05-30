package com.payments.orchestrator.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.payments.orchestrator.dto.ErrorResponse;
import com.payments.orchestrator.dto.ValidationDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Intercepts validation failures for REST @RequestBody request objects (400 Bad Request).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Validation failed for payload properties.", requestId);

        List<ValidationDetail> details = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> new ValidationDetail(
                        formatFieldPath(err.getField()),
                        err.getDefaultMessage()
                ))
                .collect(Collectors.toList());

        ErrorResponse error = new ErrorResponse(
                "VALIDATION_ERROR",
                "Validation failed",
                details,
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Intercepts query parameters or path validation failures (400 Bad Request).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Constraint violation occurred.", requestId);

        List<ValidationDetail> details = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String propertyPath = violation.getPropertyPath().toString();
            details.add(new ValidationDetail(
                    formatFieldPath(propertyPath),
                    violation.getMessage()
            ));
        }

        ErrorResponse error = new ErrorResponse(
                "VALIDATION_ERROR",
                "Validation failed",
                details,
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Intercepts unreadable/malformed HTTP requests (e.g. invalid JSON syntax) (400 Bad Request).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMalformedJson(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.error("[X-Request-Id: {}] Request payload is malformed or unreadable.", requestId, ex);

        List<ValidationDetail> details = new ArrayList<>();
        String fieldValidationIssue = "The request payload contains a malformed JSON structure or unreadable body.";
        String validationFieldName = "body";

        // Try to extract field-level details from the Jackson mapping exception if possible
        if (ex.getCause() instanceof JsonMappingException jme) {
            String fieldJsonPath = jme.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : String.valueOf(ref.getIndex()))
                    .collect(Collectors.joining("."));
            if (!fieldJsonPath.isEmpty()) {
                validationFieldName = formatFieldPath(fieldJsonPath);
                fieldValidationIssue = "Unreadable or invalid field format.";
            }
        }

        details.add(new ValidationDetail(validationFieldName, fieldValidationIssue));

        ErrorResponse error = new ErrorResponse(
                "VALIDATION_ERROR",
                "Malformed JSON payload",
                details,
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handles idempotency key conflicts (409 Conflict).
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Idempotency key conflict occurred: {}", requestId, ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "IDEMPOTENCY_CONFLICT",
                ex.getMessage(),
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handles duplicate merchant order conflicts (409 Conflict).
     */
    @ExceptionHandler(DuplicateMerchantOrderException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateMerchantOrder(DuplicateMerchantOrderException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Duplicate merchant order conflict occurred: {}", requestId, ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "DUPLICATE_MERCHANT_ORDER",
                ex.getMessage(),
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handles business logic illegal state transitions (422 Unprocessable Entity).
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateTransition(IllegalStateTransitionException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Illegal state transition request rejected: {}", requestId, ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "ILLEGAL_STATE_TRANSITION",
                ex.getMessage(),
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * Handles invalid webhook signatures (422 Unprocessable Entity).
     */
    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWebhookSignature(InvalidWebhookSignatureException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Invalid webhook signature rejected: {}", requestId, ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "INVALID_WEBHOOK_SIGNATURE",
                ex.getMessage(),
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * Handles payment or attempt references not found (404 Not Found).
     */
    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.warn("[X-Request-Id: {}] Reference not found: {}", requestId, ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                "PAYMENT_NOT_FOUND",
                ex.getMessage(),
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }


    /**
     * Fallback handler for all uncaught/unexpected system errors (500 Internal Server Error).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = getOrCreateRequestId(request);
        log.error("[X-Request-Id: {}] Unexpected system exception occurred.", requestId, ex);

        ErrorResponse error = new ErrorResponse(
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please retry or contact support.",
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    /**
     * Resolves the request correlation ID, providing a system trace ID if missing.
     */
    private String getOrCreateRequestId(HttpServletRequest request) {
        String resolvedRequestId = request.getHeader("X-Request-Id");
        if (resolvedRequestId == null || resolvedRequestId.trim().isEmpty()) {
            return "sys_" + UUID.randomUUID().toString();
        }
        return resolvedRequestId;
    }

    /**
     * Helper to format Java camelCase fields to match DTO JSON snake_case properties.
     */
    private String formatFieldPath(String path) {
        if (path == null) return null;
        String[] parts = path.split("\\.");
        for (int partIndex = 0; partIndex < parts.length; partIndex++) {
            parts[partIndex] = toSnakeCase(parts[partIndex]);
        }
        return String.join(".", parts);
    }

    private String toSnakeCase(String camel) {
        if (camel == null) return null;
        return camel.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase();
    }
}
