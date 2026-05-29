package com.payments.orchestrator.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.dto.ErrorResponse;
import com.payments.orchestrator.dto.ValidationDetail;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class SecurityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SecurityFilter.class);
    private static final String CREATE_PAYMENT_PATH = "/api/v1/payments-orchestration/payments";

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private SecurityValidator securityValidator;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        // Fully bypass security filter for non-API requests (static dashboard, actuator, etc.)
        if (!requestPath.startsWith("/api/v1/payments-orchestration/")) {
            filterChain.doFilter(request, response);
            return;
        }

        CachedBodyHttpServletRequest wrappedRequest;
        try {
            wrappedRequest = new CachedBodyHttpServletRequest(request);
        } catch (IOException requestBodyReadException) {
            log.error("Failed to read and cache request body.", requestBodyReadException);
            sendErrorResponse(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Failed to read request body", null, "sys_" + UUID.randomUUID());
            return;
        }

        // 1. Resolve trace headers and establish MDC context
        String requestId = wrappedRequest.getHeader("X-Request-Id");
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = "sys_" + UUID.randomUUID().toString();
        }

        String correlationId = wrappedRequest.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = "corr_" + UUID.randomUUID().toString();
        }

        String internalRequestId = "int_" + UUID.randomUUID().toString();

        MDC.put("request_id", requestId);
        MDC.put("correlation_id", correlationId);
        MDC.put("internal_request_id", internalRequestId);

        // Echo tracking headers back to the response
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Correlation-Id", correlationId);

        try {
            requestPath = wrappedRequest.getRequestURI();
            String method = wrappedRequest.getMethod();

            // 2. Perform Security Validation ONLY for POST endpoints that require signature verification
            if ("POST".equalsIgnoreCase(method) && CREATE_PAYMENT_PATH.equals(requestPath)) {
                String timestampStr = wrappedRequest.getHeader("X-Timestamp");
                String nonceStr = wrappedRequest.getHeader("X-Nonce");
                String signatureStr = wrappedRequest.getHeader("X-Signature");

                List<ValidationDetail> missingHeaders = new ArrayList<>();
                if (timestampStr == null) missingHeaders.add(new ValidationDetail("X-Timestamp", "header is required"));
                if (nonceStr == null) missingHeaders.add(new ValidationDetail("X-Nonce", "header is required"));
                if (signatureStr == null) missingHeaders.add(new ValidationDetail("X-Signature", "header is required"));

                if (!missingHeaders.isEmpty()) {
                    sendErrorResponse(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Missing required security headers", missingHeaders, requestId);
                    return;
                }

                // Parse cached JSON body to dynamically extract the merchant_id
                String requestBodyJson = new String(wrappedRequest.getCachedBody(), StandardCharsets.UTF_8);
                String merchantIdStr = null;
                try {
                    JsonNode parsedRequestBody = mapper.readTree(requestBodyJson);
                    if (parsedRequestBody != null && parsedRequestBody.has("merchant_id")) {
                        merchantIdStr = parsedRequestBody.get("merchant_id").asText();
                    }
                } catch (Exception bodyParseException) {
                    // Body is not a valid JSON. Malformed JSON will be mapped to 400.
                }

                if (merchantIdStr == null || merchantIdStr.trim().isEmpty()) {
                    List<ValidationDetail> details = new ArrayList<>();
                    details.add(new ValidationDetail("merchant_id", "must not be null"));
                    sendErrorResponse(response, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", details, requestId);
                    return;
                }

                // Trigger security validation pipeline
                securityValidator.validate(
                        method,
                        requestPath,
                        requestBodyJson,
                        merchantIdStr,
                        timestampStr,
                        nonceStr,
                        signatureStr
                );
            }

            // Move downstream with the multi-read request wrapper
            filterChain.doFilter(wrappedRequest, response);

        } catch (SecurityValidationException securityValidationException) {
            sendErrorResponse(response, securityValidationException.getStatus(), securityValidationException.getErrorCode(), securityValidationException.getMessage(), null, requestId);
        } catch (Exception unexpectedFilterException) {
            log.error("Unhandled filter exception occurred.", unexpectedFilterException);
            sendErrorResponse(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.", null, requestId);
        } finally {
            // Guarantee MDC thread cleanup
            MDC.clear();
        }
    }

    private void sendErrorResponse(
            HttpServletResponse response,
            HttpStatus status,
            String errorCode,
            String message,
            List<ValidationDetail> details,
            String requestId
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        ErrorResponse errorResponse = new ErrorResponse(
                errorCode,
                message,
                details,
                requestId,
                OffsetDateTime.now(ZoneOffset.UTC)
        );

        response.getWriter().write(mapper.writeValueAsString(errorResponse));
    }
}
