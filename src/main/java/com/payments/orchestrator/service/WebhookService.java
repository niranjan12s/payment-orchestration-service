package com.payments.orchestrator.service;

import com.payments.orchestrator.dto.WebhookRequest;

public interface WebhookService {
    void processWebhook(String provider, String signature, String rawBody, WebhookRequest request);
}
