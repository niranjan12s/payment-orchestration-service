package com.payments.orchestrator.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * SecurityValidator handles the security pipeline for incoming requests,
 * enforcing merchant verification, timestamp drift checks (replay window),
 * nonce uniqueness via Redis, and HMAC-SHA256 signature verification.
 */
@Component
public class SecurityValidator {

    private static final Logger log = LoggerFactory.getLogger(SecurityValidator.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MerchantSecretResolver secretResolver;

    /**
     * Executes the mandatory security verification pipeline in sequence.
     *
     * @param method the HTTP request method (e.g. POST)
     * @param requestPath the request path (e.g. /api/v1/payments-orchestration/payments)
     * @param requestBodyJson the raw HTTP request body string
     * @param merchantIdStr the raw merchant ID string
     * @param timestampStr the raw X-Timestamp string
     * @param nonceStr the raw X-Nonce string
     * @param signatureStr the raw X-Signature string
     * @throws SecurityValidationException if any security check fails
     */
    public void validate(
            String method,
            String requestPath,
            String requestBodyJson,
            String merchantIdStr,
            String timestampStr,
            String nonceStr,
            String signatureStr
    ) {
        // 1. Validate Merchant ID and retrieve secrets
        UUID merchantId;
        try {
            merchantId = UUID.fromString(merchantIdStr);
        } catch (IllegalArgumentException invalidMerchantIdException) {
            throw new SecurityValidationException(
                    "VALIDATION_ERROR",
                    "merchant_id must be a valid UUID",
                    HttpStatus.BAD_REQUEST
            );
        }

        List<String> merchantSecrets = secretResolver.resolveSecrets(merchantId);
        if (merchantSecrets == null || merchantSecrets.isEmpty()) {
            throw new SecurityValidationException(
                    "MERCHANT_INACTIVE",
                    "Merchant account is not active or does not exist",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        // 2. Validate request Timestamp drift (±5 minutes window)
        OffsetDateTime timestamp;
        try {
            timestamp = OffsetDateTime.parse(timestampStr);
        } catch (Exception timestampParseException) {
            throw new SecurityValidationException(
                    "VALIDATION_ERROR",
                    "X-Timestamp must be a valid ISO 8601 UTC timestamp",
                    HttpStatus.BAD_REQUEST
            );
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long driftMinutes = Math.abs(Duration.between(timestamp, now).toMinutes());
        if (driftMinutes > 5) {
            log.warn("[Security Alert] Expired timestamp drift detected. Request time: {}, Server time: {}", timestamp, now);
            throw new SecurityValidationException(
                    "TIMESTAMP_INVALID",
                    "Request timestamp is outside the allowed 5-minute drift window",
                    HttpStatus.UNAUTHORIZED
            );
        }

        // 3. Validate Nonce (Replay Protection using Redis with a 10-minute TTL)
        String redisKey = String.format("nonce:%s:%s", merchantId, nonceStr);
        Boolean nonceClaimed = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(nonceClaimed)) {
            log.error("[SECURITY AUDIT] Nonce replay attack detected! Reused Nonce: {} for Merchant: {}", nonceStr, merchantId);
            throw new SecurityValidationException(
                    "NONCE_REUSED",
                    "Nonce has already been used within the replay protection window",
                    HttpStatus.UNAUTHORIZED
            );
        }

        // 4. Validate HMAC-SHA256 Signature (supporting zero-downtime key rotation)
        String requestBodySha256Hash;
        try {
            String canonicalizedRequestJson = SecurityUtils.canonicalizeJson(requestBodyJson);
            requestBodySha256Hash = SecurityUtils.sha256Hex(canonicalizedRequestJson);
        } catch (Exception bodyHashingException) {
            log.error("Failed to canonicalize and hash request payload.", bodyHashingException);
            throw new SecurityValidationException(
                    "VALIDATION_ERROR",
                    "Failed to process and hash request body",
                    HttpStatus.BAD_REQUEST
            );
        }

        // Canonical string: METHOD\nPATH\nBODY_SHA256\nTIMESTAMP\nNONCE\nMERCHANT_ID
        String canonicalString = String.join("\n",
                method,
                requestPath,
                requestBodySha256Hash,
                timestampStr,
                nonceStr,
                merchantIdStr
        );

        boolean isSignatureValid = false;
        int matchedSecretIndex = -1;

        for (int secretIndex = 0; secretIndex < merchantSecrets.size(); secretIndex++) {
            String candidateSecret = merchantSecrets.get(secretIndex);
            try {
                String expectedSignature = SecurityUtils.hmacSha256Base64(candidateSecret, canonicalString);
                if (SecurityUtils.constantTimeEquals(expectedSignature, signatureStr)) {
                    isSignatureValid = true;
                    matchedSecretIndex = secretIndex;
                    break;
                }
            } catch (Exception hmacCalculationException) {
                log.error("Hmac calculation failed.", hmacCalculationException);
            }
        }

        if (!isSignatureValid) {
            log.error("[SECURITY AUDIT] Invalid signature verification failure! Merchant: {}, Provided Signature: {}", merchantId, signatureStr);
            throw new SecurityValidationException(
                    "INVALID_SIGNATURE",
                    "Request signature verification failed",
                    HttpStatus.UNAUTHORIZED
            );
        }

        if (matchedSecretIndex > 0) {
            log.warn("[Security Audit] Grace-period secret utilized by merchant: {}", merchantId);
        }
    }
}
