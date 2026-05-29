package com.payments.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.domain.PaymentIdempotency;
import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.IdempotencyResult;
import com.payments.orchestrator.exception.IdempotencyConflictException;
import com.payments.orchestrator.repository.PaymentIdempotencyRepository;
import com.payments.orchestrator.security.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyServiceImpl.class);

    @Autowired
    private PaymentIdempotencyRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional
    public IdempotencyResult checkIdempotency(String idempotencyKey, String rawBody) {
        String requestBodyHash;
        try {
            String canonicalizedRequestJson = SecurityUtils.canonicalizeJson(rawBody);
            requestBodyHash = SecurityUtils.sha256Hex(canonicalizedRequestJson);
        } catch (Exception bodyHashingException) {
            log.error("Failed to hash request body for idempotency key: {}", idempotencyKey, bodyHashingException);
            throw new IllegalArgumentException("Failed to canonicalize and hash request payload", bodyHashingException);
        }

        Optional<PaymentIdempotency> existingIdempotencyRecord = repository.findByIdempotencyKey(idempotencyKey);

        if (existingIdempotencyRecord.isPresent()) {
            PaymentIdempotency idempotencyRecord = existingIdempotencyRecord.get();

            // 1. Handle Expired Keys (TTL check)
            if (idempotencyRecord.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
                log.info("Idempotency key '{}' expired. Deleting and treating as a new request.", idempotencyKey);
                repository.delete(idempotencyRecord);
                repository.flush();
                return createNewIdempotencyRecord(idempotencyKey, requestBodyHash);
            }

            // 2. Handle Payload Conflict Check
            if (!idempotencyRecord.getRequestHash().equals(requestBodyHash)) {
                log.warn("[Conflict Alert] Idempotency conflict for key '{}'. Request payload hash mismatch.", idempotencyKey);
                throw new IdempotencyConflictException(idempotencyKey, "The same idempotency key was submitted with a different request payload.");
            }

            // 3. Handle Duplicate In-Flight requests
            if ("PROCESSING".equals(idempotencyRecord.getStatus())) {
                log.warn("Concurrent duplicate request detected for in-flight idempotency key '{}'.", idempotencyKey);
                throw new IdempotencyConflictException(idempotencyKey, "Payment is already being processed. Please wait or retry later.");
            }

            // 4. Handle Duplicate Completed requests (Return Cached Response)
            log.info("Duplicate request matched cached response for idempotency key '{}'.", idempotencyKey);
            CreatePaymentResponse cachedResponse = objectMapper.convertValue(
                    idempotencyRecord.getResponsePayload(),
                    CreatePaymentResponse.class
            );

            return IdempotencyResult.match(cachedResponse);
        }

        // Create a new in-flight record
        return createNewIdempotencyRecord(idempotencyKey, requestBodyHash);
    }

    @Override
    @Transactional
    public void saveCompletedResponse(String idempotencyKey, CreatePaymentResponse response) {
        Optional<PaymentIdempotency> existingOpt = repository.findByIdempotencyKey(idempotencyKey);
        if (existingOpt.isPresent()) {
            PaymentIdempotency record = existingOpt.get();
            
            // Map CreatePaymentResponse properties to generic Jackson Map for JSONB column
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.convertValue(response, Map.class);
            
            record.setResponsePayload(payload);
            record.setStatus("COMPLETED");
            
            repository.save(record);
            log.info("Idempotency cache updated to COMPLETED for key: {}", idempotencyKey);
        } else {
            log.error("Failed to persist completed response: Idempotency key '{}' not found in database.", idempotencyKey);
        }
    }

    private IdempotencyResult createNewIdempotencyRecord(String idempotencyKey, String requestBodyHash) {
        PaymentIdempotency newIdempotencyRecord = new PaymentIdempotency();
        newIdempotencyRecord.setIdempotencyKey(idempotencyKey);
        newIdempotencyRecord.setRequestHash(requestBodyHash);
        newIdempotencyRecord.setStatus("PROCESSING");
        newIdempotencyRecord.setResponsePayload(new HashMap<>());
        // Default TTL: 24 Hours
        newIdempotencyRecord.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(24));

        try {
            repository.saveAndFlush(newIdempotencyRecord);
            log.info("Claimed idempotency key '{}' with status PROCESSING.", idempotencyKey);
        } catch (DataIntegrityViolationException concurrentInsertException) {
            log.warn("Concurrent idempotency claim detected for key '{}'.", idempotencyKey);
            throw new IdempotencyConflictException(
                    idempotencyKey,
                    "Payment is already being processed. Please wait or retry later."
            );
        }

        return IdempotencyResult.absent(requestBodyHash);
    }
}
