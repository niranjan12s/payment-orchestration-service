package com.payments.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.dto.CreatePaymentRequest;
import com.payments.orchestrator.dto.CreatePaymentResponse;
import com.payments.orchestrator.dto.MoneyAmount;
import com.payments.orchestrator.dto.PaymentAuthorizedResponse;
import com.payments.orchestrator.dto.PaymentPendingResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiSchemaLayerTests {

    private static Validator validator;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void testMoneyAmountValidationRules() {
        // 1. Happy path
        MoneyAmount validAmount = new MoneyAmount("USD", new BigDecimal("100.00"));
        Set<ConstraintViolation<MoneyAmount>> violations = validator.validate(validAmount);
        assertThat(violations).isEmpty();

        // 2. Malformed currency code (not 3 letters, lowercase)
        MoneyAmount badCurrency = new MoneyAmount("usd", new BigDecimal("100.00"));
        Set<ConstraintViolation<MoneyAmount>> currencyViolations = validator.validate(badCurrency);
        assertThat(currencyViolations).hasSize(1);
        assertThat(currencyViolations.iterator().next().getMessage()).contains("currency_code must be a valid ISO 4217 3-letter currency code");

        // 3. Non-positive amount
        MoneyAmount zeroAmount = new MoneyAmount("USD", new BigDecimal("0.00"));
        Set<ConstraintViolation<MoneyAmount>> zeroViolations = validator.validate(zeroAmount);
        assertThat(zeroViolations).hasSize(1);
        assertThat(zeroViolations.iterator().next().getMessage()).contains("amount must be greater than 0");

        MoneyAmount negativeAmount = new MoneyAmount("USD", new BigDecimal("-5.00"));
        Set<ConstraintViolation<MoneyAmount>> negViolations = validator.validate(negativeAmount);
        assertThat(negViolations).hasSize(1);
    }

    @Test
    void testCreatePaymentRequestValidationRules() {
        CreatePaymentRequest request = new CreatePaymentRequest();
        request.setMerchantId(UUID.randomUUID());
        request.setMerchantOrderId("ORDER-12345");
        request.setPaymentMethodType("CARD");
        request.setPaymentTokenReference("tok_visa_abc");
        request.setTransactionAmount(new MoneyAmount("USD", new BigDecimal("10.00")));
        request.setSettlementAmount(new MoneyAmount("INR", new BigDecimal("830.00")));

        Set<ConstraintViolation<CreatePaymentRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();

        // Invalid Enum for Payment Method
        request.setPaymentMethodType("CRYPTO");
        Set<ConstraintViolation<CreatePaymentRequest>> badEnumViolations = validator.validate(request);
        assertThat(badEnumViolations).hasSize(1);
        assertThat(badEnumViolations.iterator().next().getMessage()).contains("payment_method_type must be CARD or UPI");
    }

    @Test
    void testJsonSnakeCaseSerializationConsistency() throws Exception {
        UUID merchantId = UUID.randomUUID();
        MoneyAmount transactionAmount = new MoneyAmount("USD", new BigDecimal("50.00"));
        MoneyAmount settlementAmount = new MoneyAmount("USD", new BigDecimal("50.00"));
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "checkout");

        CreatePaymentRequest request = new CreatePaymentRequest(
                merchantId,
                "ORDER-ABC-123",
                "UPI",
                "upi_ref_123",
                transactionAmount,
                settlementAmount,
                metadata
        );

        String json = objectMapper.writeValueAsString(request);
        
        // Assert JSON keys are snake_case as specified in swagger.yaml
        assertThat(json)
                .contains("\"merchant_id\":\"" + merchantId + "\"")
                .contains("\"merchant_order_id\":\"ORDER-ABC-123\"")
                .contains("\"payment_method_type\":\"UPI\"")
                .contains("\"payment_token_reference\":\"upi_ref_123\"")
                .contains("\"transaction_amount\"")
                .contains("\"currency_code\":\"USD\"")
                .contains("\"amount\":50.00")
                .contains("\"settlement_amount\"")
                .contains("\"metadata\"")
                .contains("\"source\":\"checkout\"");
    }

    @Test
    void testJsonSnakeCaseDeserialization() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String json = "{"
                + "\"merchant_id\":\"" + merchantId + "\","
                + "\"merchant_order_id\":\"ORDER-999\","
                + "\"payment_method_type\":\"CARD\","
                + "\"payment_token_reference\":\"tok_ref_999\","
                + "\"transaction_amount\":{\"currency_code\":\"USD\",\"amount\":9.99},"
                + "\"settlement_amount\":{\"currency_code\":\"USD\",\"amount\":9.99}"
                + "}";

        CreatePaymentRequest request = objectMapper.readValue(json, CreatePaymentRequest.class);

        assertThat(request.getMerchantId()).isEqualTo(merchantId);
        assertThat(request.getMerchantOrderId()).isEqualTo("ORDER-999");
        assertThat(request.getPaymentMethodType()).isEqualTo("CARD");
        assertThat(request.getTransactionAmount().getCurrencyCode()).isEqualTo("USD");
        assertThat(request.getTransactionAmount().getAmount()).isEqualTo(new BigDecimal("9.99"));
    }

    @Test
    void testUnifiedResponseBuildersSerialization() throws Exception {
        UUID intentId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);

        // 1. Authorized response serialization
        PaymentAuthorizedResponse auth = new PaymentAuthorizedResponse(
                intentId,
                attemptId,
                "ORDER-OK",
                "PSP_A",
                "psp_tx_ok",
                new MoneyAmount("USD", new BigDecimal("100.00")),
                new MoneyAmount("INR", new BigDecimal("8300.00")),
                timestamp
        );

        CreatePaymentResponse authResponse = CreatePaymentResponse.authorized(auth);
        String authJson = objectMapper.writeValueAsString(authResponse);

        assertThat(authJson)
                .contains("\"intent_id\":\"" + intentId + "\"")
                .contains("\"attempt_id\":\"" + attemptId + "\"")
                .contains("\"status\":\"AUTHORIZED\"")
                .contains("\"provider_reference\":\"psp_tx_ok\"")
                .contains("\"merchant_order_id\":\"ORDER-OK\"")
                .doesNotContain("\"message\""); // message is null and should be excluded via NON_NULL

        // 2. Pending response serialization
        PaymentPendingResponse pending = new PaymentPendingResponse(
                intentId,
                attemptId,
                "PSP_A",
                "reconciliation pending",
                timestamp
        );

        CreatePaymentResponse pendingResponse = CreatePaymentResponse.pending(pending);
        String pendingJson = objectMapper.writeValueAsString(pendingResponse);

        assertThat(pendingJson)
                .contains("\"intent_id\":\"" + intentId + "\"")
                .contains("\"attempt_id\":\"" + attemptId + "\"")
                .contains("\"status\":\"PENDING\"")
                .contains("\"message\":\"reconciliation pending\"")
                .doesNotContain("\"provider_reference\"") // null exclusion
                .doesNotContain("\"merchant_order_id\""); // null exclusion
    }
}
