package com.payments.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.ErrorResponse;
import com.payments.orchestrator.dto.MoneyAmount;
import com.payments.orchestrator.dto.PaymentAuthorizedResponse;
import com.payments.orchestrator.security.InMemoryMerchantSecretResolver;
import com.payments.orchestrator.security.SecurityUtils;
import com.payments.orchestrator.service.PaymentOrchestrationFlowManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityValidationTests extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMerchantSecretResolver secretResolver;

    @MockBean
    private PaymentOrchestrationFlowManager flowManager;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private UUID merchantId;
    private String activeSecret;
    private String graceSecret;
    private String validBodyTemplate;

    @BeforeEach
    void setupSecrets() {
        merchantId = UUID.randomUUID();
        activeSecret = "active_key_rotation_token_1";
        graceSecret = "grace_key_rotation_token_2";

        List<String> secrets = new ArrayList<>();
        secrets.add(activeSecret);
        secrets.add(graceSecret);
        secretResolver.registerSecrets(merchantId, secrets);

        validBodyTemplate = "{\"merchant_id\":\"" + merchantId + "\",\"merchant_order_id\":\"ORD-100\",\"payment_method_type\":\"CARD\",\"payment_token_reference\":\"token_123\",\"transaction_amount\":{\"currency_code\":\"USD\",\"amount\":100.00},\"settlement_amount\":{\"currency_code\":\"INR\",\"amount\":8300.00}}";

        // Stub the flowManager to cleanly return authorized when request flows through PaymentController
        when(flowManager.processPayment(any(), anyString(), anyString()))
                .thenReturn(CreatePaymentResponse.authorized(new PaymentAuthorizedResponse(
                        UUID.randomUUID(), UUID.randomUUID(), "ORD-OK", "PSP_A", "ref_123",
                        new MoneyAmount("USD", BigDecimal.TEN), new MoneyAmount("USD", BigDecimal.TEN),
                        OffsetDateTime.now(ZoneOffset.UTC)
                )));
    }

    @Test
    void testSecurityValidationHappyPath() throws Exception {
        String body = validBodyTemplate.replace("ORD-100", "ORD-HAPPY");
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
                .header("X-Signature", signature)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void testInvalidSignatureRejection() throws Exception {
        String body = validBodyTemplate.replace("ORD-100", "ORD-BADSIG");
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
                .header("X-Signature", badSignature)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        ErrorResponse error = objectMapper.readValue(responseBody, ErrorResponse.class);
        assertThat(error.getErrorCode()).isEqualTo("INVALID_SIGNATURE");
        assertThat(error.getMessage()).isEqualTo("Request signature verification failed");
    }

    @Test
    void testGracePeriodSecretFallback() throws Exception {
        String body = validBodyTemplate.replace("ORD-100", "ORD-GRACE");
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
                .header("X-Signature", graceSignature)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void testExpiredTimestampDriftRejection() throws Exception {
        String body = validBodyTemplate.replace("ORD-100", "ORD-EXPDRIFT");
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
                .header("X-Signature", signature)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        ErrorResponse error = objectMapper.readValue(responseBody, ErrorResponse.class);
        assertThat(error.getErrorCode()).isEqualTo("TIMESTAMP_INVALID");
        assertThat(error.getMessage()).contains("drift window");
    }

    @Test
    void testNonceReplayRejection() throws Exception {
        String body = validBodyTemplate.replace("ORD-100", "ORD-REPLAY");
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
                .header("X-Signature", signature)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        // Second attempt with identical nonce - REJECTED (401 Nonce Reused)
        String responseBody = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", "req_replayed_nonce")
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        ErrorResponse error = objectMapper.readValue(responseBody, ErrorResponse.class);
        assertThat(error.getErrorCode()).isEqualTo("NONCE_REUSED");
        assertThat(error.getMessage()).contains("replay protection window");
    }
}
