package com.payments.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.domain.AttemptStatus;
import com.payments.orchestrator.domain.PaymentIntent;
import com.payments.orchestrator.domain.PaymentStatus;
import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.MoneyAmount;
import com.payments.orchestrator.repository.PaymentIntentRepository;
import com.payments.orchestrator.security.InMemoryMerchantSecretResolver;
import com.payments.orchestrator.security.SecurityUtils;
import com.payments.orchestrator.service.PspAConnector;
import com.payments.orchestrator.service.PspBConnector;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PaymentOrchestrationFlowTests extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryMerchantSecretResolver secretResolver;

    @Autowired
    private PspAConnector pspAConnector;

    @Autowired
    private PspBConnector pspBConnector;

    @Autowired
    private PaymentIntentRepository intentRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private UUID merchantId;
    private String merchantSecret;
    private CreatePaymentRequest cardRequest;
    private CreatePaymentRequest upiRequest;

    @BeforeEach
    void setUpSecretsAndRequests() {
        merchantId = UUID.randomUUID();
        merchantSecret = "active_key_orchestration_token_secret_1";

        secretResolver.registerSecrets(merchantId, List.of(merchantSecret));

        cardRequest = new CreatePaymentRequest(
                merchantId,
                "ORD-FLOW-CARD-" + UUID.randomUUID().toString().substring(0, 8),
                "CARD",
                "vault_token_visa_flow",
                new MoneyAmount("USD", new BigDecimal("100.00")),
                new MoneyAmount("INR", new BigDecimal("8300.00")),
                null
        );

        upiRequest = new CreatePaymentRequest(
                merchantId,
                "ORD-FLOW-UPI-" + UUID.randomUUID().toString().substring(0, 8),
                "UPI",
                "upi_reference_identifier_flow",
                new MoneyAmount("INR", new BigDecimal("500.00")),
                new MoneyAmount("INR", new BigDecimal("500.00")),
                null
        );

        // Reset connector modes to SUCCESS
        pspAConnector.setMode("SUCCESS");
        pspBConnector.setMode("SUCCESS");

        // Reset circuit breakers
        circuitBreakerRegistry.circuitBreaker("psp-a").reset();
        circuitBreakerRegistry.circuitBreaker("psp-b").reset();
    }

    @Test
    void testEndToEndCardSuccessFlow() throws Exception {
        String body = objectMapper.writeValueAsString(cardRequest);
        String timestampStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "nonce_" + UUID.randomUUID();
        String idempotencyKey = "idemp_" + UUID.randomUUID();

        // Generate HMAC signature required by SecurityFilter
        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n",
                "POST",
                "/api/v1/payments-orchestration/payments",
                bodyHash,
                timestampStr,
                nonce,
                merchantId.toString()
        );
        String signature = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString);

        // Perform HTTP Request (should return 200 OK with AUTHORIZED response)
        String responseContent = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response = objectMapper.readValue(responseContent, CreatePaymentResponse.class);

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getProviderName()).isEqualTo("PSP_A"); // CARD routes to PSP_A
        assertThat(response.getProviderReference()).startsWith("ref_pspa_");

        // Verify committed database state
        PaymentIntent intent = intentRepository.findById(response.getIntentId()).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(intent.getAttempts()).hasSize(1);
        assertThat(intent.getAttempts().get(0).getStatus()).isEqualTo(AttemptStatus.AUTHORIZED);
        assertThat(intent.getAttempts().get(0).getProviderName()).isEqualTo("PSP_A");
    }

    @Test
    void testEndToEndUpiSuccessFlow() throws Exception {
        String body = objectMapper.writeValueAsString(upiRequest);
        String timestampStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "nonce_" + UUID.randomUUID();
        String idempotencyKey = "idemp_" + UUID.randomUUID();

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n",
                "POST",
                "/api/v1/payments-orchestration/payments",
                bodyHash,
                timestampStr,
                nonce,
                merchantId.toString()
        );
        String signature = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString);

        String responseContent = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response = objectMapper.readValue(responseContent, CreatePaymentResponse.class);

        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getProviderName()).isEqualTo("PSP_B"); // UPI routes to PSP_B
        assertThat(response.getProviderReference()).startsWith("ref_pspb_");
    }

    @Test
    void testIdempotencyCacheDeduplicationMatch() throws Exception {
        String body = objectMapper.writeValueAsString(cardRequest);
        String timestampStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce1 = "nonce_" + UUID.randomUUID();
        String idempotencyKey = "idemp_" + UUID.randomUUID();

        // 1st request
        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString1 = String.join("\n", "POST", "/api/v1/payments-orchestration/payments", bodyHash, timestampStr, nonce1, merchantId.toString());
        String signature1 = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString1);

        String firstResponse = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce1)
                .header("X-Signature", signature1)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response1 = objectMapper.readValue(firstResponse, CreatePaymentResponse.class);

        // 2nd request (with different nonce to bypass replay check but same idempotency key)
        String nonce2 = "nonce_" + UUID.randomUUID();
        String canonicalString2 = String.join("\n", "POST", "/api/v1/payments-orchestration/payments", bodyHash, timestampStr, nonce2, merchantId.toString());
        String signature2 = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString2);

        String secondResponse = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce2)
                .header("X-Signature", signature2)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response2 = objectMapper.readValue(secondResponse, CreatePaymentResponse.class);

        // Assert they match exactly (deduplicated correctly without re-running transactions)
        assertThat(response1.getIntentId()).isEqualTo(response2.getIntentId());
        assertThat(response1.getAttemptId()).isEqualTo(response2.getAttemptId());
        assertThat(response1.getProviderReference()).isEqualTo(response2.getProviderReference());
    }

    @Test
    void testEndToEndSoftTimeoutReturns202Pending() throws Exception {
        // Set PspA to TIMEOUT mode
        pspAConnector.setMode("TIMEOUT");

        String body = objectMapper.writeValueAsString(cardRequest);
        String timestampStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "nonce_" + UUID.randomUUID();
        String idempotencyKey = "idemp_" + UUID.randomUUID();

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n", "POST", "/api/v1/payments-orchestration/payments", bodyHash, timestampStr, nonce, merchantId.toString());
        String signature = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString);

        // Perform HTTP Request (should return 202 ACCEPTED with PENDING response)
        String responseContent = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response = objectMapper.readValue(responseContent, CreatePaymentResponse.class);

        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getMessage()).contains("timeout occurred");

        // Verify committed database state
        PaymentIntent intent = intentRepository.findById(response.getIntentId()).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(intent.getAttempts().get(0).getStatus()).isEqualTo(AttemptStatus.PENDING);
    }

    @Test
    void testEndToEndFailureReturns200Failed() throws Exception {
        // Set PspA to FAILURE mode
        pspAConnector.setMode("FAILURE");

        String body = objectMapper.writeValueAsString(cardRequest);
        String timestampStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "nonce_" + UUID.randomUUID();
        String idempotencyKey = "idemp_" + UUID.randomUUID();

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n", "POST", "/api/v1/payments-orchestration/payments", bodyHash, timestampStr, nonce, merchantId.toString());
        String signature = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString);

        // Perform HTTP Request (should return 200 OK with FAILED response status)
        String responseContent = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response = objectMapper.readValue(responseContent, CreatePaymentResponse.class);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("Card declined by issuing bank");

        // Verify committed database state
        PaymentIntent intent = intentRepository.findById(response.getIntentId()).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(intent.getAttempts().get(0).getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(intent.getAttempts().get(0).getErrorCode()).isEqualTo("CARD_DECLINED");
    }

    @Test
    void testCircuitBreakerTripsAndFailsOverToFallbackProvider() throws Exception {
        // Force-open the psp-a circuit breaker so that calls to PSP_A fail immediately with CallNotPermittedException
        circuitBreakerRegistry.circuitBreaker("psp-a").transitionToOpenState();

        // PSP_B is healthy and set to SUCCESS mode
        pspBConnector.setMode("SUCCESS");

        String body = objectMapper.writeValueAsString(cardRequest); // Card request ordinarily routes to PSP_A
        String timestampStr = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String nonce = "nonce_" + UUID.randomUUID();
        String idempotencyKey = "idemp_" + UUID.randomUUID();

        String bodyHash = SecurityUtils.sha256Hex(SecurityUtils.canonicalizeJson(body));
        String canonicalString = String.join("\n", "POST", "/api/v1/payments-orchestration/payments", bodyHash, timestampStr, nonce, merchantId.toString());
        String signature = SecurityUtils.hmacSha256Base64(merchantSecret, canonicalString);

        // Perform HTTP Request:
        // System should catch CB open on PSP_A, failover to PSP_B, create second attempt, and return success!
        String responseContent = mockMvc.perform(post("/api/v1/payments-orchestration/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-Timestamp", timestampStr)
                .header("X-Nonce", nonce)
                .header("X-Signature", signature)
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        CreatePaymentResponse response = objectMapper.readValue(responseContent, CreatePaymentResponse.class);

        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getProviderName()).isEqualTo("PSP_B"); // Successfully routed to fallback PSP_B!
        assertThat(response.getProviderReference()).startsWith("ref_pspb_");

        // Verify committed database state (1 intent, 2 attempts)
        PaymentIntent intent = intentRepository.findById(response.getIntentId()).orElseThrow();
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(intent.getAttempts()).hasSize(2); // First is SUPERSEDED, second is AUTHORIZED!
        
        // Attempt 1 check
        assertThat(intent.getAttempts().stream()
                .filter(a -> a.getProviderName().equals("PSP_A"))
                .findFirst().orElseThrow().getStatus()).isEqualTo(AttemptStatus.SUPERSEDED);
                
        // Attempt 2 check
        assertThat(intent.getAttempts().stream()
                .filter(a -> a.getProviderName().equals("PSP_B"))
                .findFirst().orElseThrow().getStatus()).isEqualTo(AttemptStatus.AUTHORIZED);
    }
}
