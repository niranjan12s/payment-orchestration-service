package com.payments.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.dto.ErrorResponse;
import com.payments.orchestrator.security.InMemoryMerchantSecretResolver;
import com.payments.orchestrator.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityValidationTests extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMerchantSecretResolver secretResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID merchantId;
    private String activeSecret;
    private String graceSecret;

    @BeforeEach
    void setupSecrets() {
        merchantId = UUID.randomUUID();
        activeSecret = "active_key_rotation_token_1";
        graceSecret = "grace_key_rotation_token_2";

        List<String> secrets = new ArrayList<>();
        secrets.add(activeSecret);
        secrets.add(graceSecret);
        secretResolver.registerSecrets(merchantId, secrets);
    }

    @Test
    void testSecurityValidationHappyPath() throws Exception {
        String body = "{\"merchant_id\":\"" + merchantId + "\",\"merchant_order_id\":\"ORD-100\"}";
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        String timestampStr = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "random_nonce_123456789";

        String canonicalJson = SecurityUtils.canonicalizeJson(body);
        String bodyHash = SecurityUtils.sha256Hex(canonicalJson);

        String canonicalString = String.join("\n",
                "POST",
                "/api/v1/payments-orchestration/payments",
                bodyHash,
                timestampStr,
                nonce,
                merchantId.toString()
        );

        String signature = SecurityUtils.hmacSha256Base64(activeSecret, canonicalString);

        mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void testInvalidSignatureRejection() throws Exception {
        String body = "{\"merchant_id\":\"" + merchantId + "\",\"merchant_order_id\":\"ORD-101\"}";
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        String timestampStr = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "random_nonce_234567890";

        // Generate signature signed with an incorrect key
        String badSignature = SecurityUtils.hmacSha256Base64("wrong_secret_key", "canonical_msg");

        String responseBody = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", "req_invalid_sig")
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", badSignature))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        ErrorResponse error = objectMapper.readValue(responseBody, ErrorResponse.class);
        assertThat(error.getErrorCode()).isEqualTo("INVALID_SIGNATURE");
        assertThat(error.getMessage()).isEqualTo("Request signature verification failed");
    }

    @Test
    void testGracePeriodSecretFallback() throws Exception {
        String body = "{\"merchant_id\":\"" + merchantId + "\",\"merchant_order_id\":\"ORD-102\"}";
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        String timestampStr = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "random_nonce_345678901";

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n",
                "POST",
                "/api/v1/payments-orchestration/payments",
                bodyHash,
                timestampStr,
                nonce,
                merchantId.toString()
        );

        // Sign using the GRACE SECRET key instead of the active key
        String graceSignature = SecurityUtils.hmacSha256Base64(graceSecret, canonicalString);

        mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", graceSignature))
                .andExpect(status().isOk());
    }

    @Test
    void testExpiredTimestampDriftRejection() throws Exception {
        String body = "{\"merchant_id\":\"" + merchantId + "\",\"merchant_order_id\":\"ORD-103\"}";
        // Create an expired timestamp (6 minutes ago)
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(6);
        String timestampStr = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "random_nonce_456789012";

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n",
                "POST",
                "/api/v1/payments-orchestration/payments",
                bodyHash,
                timestampStr,
                nonce,
                merchantId.toString()
        );

        String signature = SecurityUtils.hmacSha256Base64(activeSecret, canonicalString);

        String responseBody = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", "req_expired_time")
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        ErrorResponse error = objectMapper.readValue(responseBody, ErrorResponse.class);
        assertThat(error.getErrorCode()).isEqualTo("TIMESTAMP_INVALID");
        assertThat(error.getMessage()).contains("drift window");
    }

    @Test
    void testNonceReplayRejection() throws Exception {
        String body = "{\"merchant_id\":\"" + merchantId + "\",\"merchant_order_id\":\"ORD-104\"}";
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
        String timestampStr = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "replayed_nonce_567890123";

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n",
                "POST",
                "/api/v1/payments-orchestration/payments",
                bodyHash,
                timestampStr,
                nonce,
                merchantId.toString()
        );

        String signature = SecurityUtils.hmacSha256Base64(activeSecret, canonicalString);

        // First attempt - SUCCESS (200)
        mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature))
                .andExpect(status().isOk());

        // Second attempt with identical nonce - REJECTED (401 Nonce Reused)
        String responseBody = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", "req_replayed_nonce")
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        ErrorResponse error = objectMapper.readValue(responseBody, ErrorResponse.class);
        assertThat(error.getErrorCode()).isEqualTo("NONCE_REUSED");
        assertThat(error.getMessage()).contains("replay protection window");
    }

    // A tiny Mock Controller nested inside the test context to capture authorized request flows
    @RestController
    @RequestMapping("/api/v1/payments-orchestration")
    static class TestSecurityController {
        @PostMapping("/payments")
        public ResponseEntity<String> testPayments(@RequestBody String body) {
            return ResponseEntity.ok("AUTHORIZED");
        }
    }
}
