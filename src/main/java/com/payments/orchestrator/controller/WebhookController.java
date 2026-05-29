package com.payments.orchestrator.controller;

import com.payments.orchestrator.dto.WebhookRequest;
import com.payments.orchestrator.security.CachedBodyHttpServletRequest;
import com.payments.orchestrator.service.WebhookService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments-orchestration")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    @Autowired
    private WebhookService webhookService;

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<Map<String, Object>> receiveWebhook(
            @PathVariable("provider") String provider,
            @RequestHeader(value = "X-PSP-Signature") String signature,
            @RequestBody @Valid WebhookRequest request,
            HttpServletRequest servletRequest
    ) {
        log.info("REST API webhook callback received from provider: {}. Event ID: {}", provider, request.getEventId());

        String rawBody = "";
        if (servletRequest instanceof CachedBodyHttpServletRequest cachedReq) {
            rawBody = new String(cachedReq.getCachedBody(), StandardCharsets.UTF_8);
        } else {
            log.warn("servletRequest is not an instance of CachedBodyHttpServletRequest in WebhookController.");
        }

        // Process webhook ingestion flow
        webhookService.processWebhook(provider, signature, rawBody, request);

        // Acknowledge event processed successfully
        Map<String, Object> response = new HashMap<>();
        response.put("acknowledged", true);
        response.put("event_id", request.getEventId());

        return ResponseEntity.ok(response);
    }
}
