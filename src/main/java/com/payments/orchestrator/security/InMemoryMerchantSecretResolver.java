package com.payments.orchestrator.security;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryMerchantSecretResolver implements MerchantSecretResolver {

    private final Map<UUID, List<String>> merchantSecrets = new ConcurrentHashMap<>();

    public InMemoryMerchantSecretResolver() {
        // Pre-register standard swagger example merchant for smooth local development
        UUID swaggerMerchant = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        List<String> swaggerKeys = new ArrayList<>();
        swaggerKeys.add("active_secret_key_123");
        swaggerKeys.add("grace_secret_key_999");
        merchantSecrets.put(swaggerMerchant, swaggerKeys);

        // Pre-register playground console merchant ID
        UUID playgroundMerchant = UUID.fromString("893c5d61-ff8b-4c07-9b24-7cb4bbd7c679");
        List<String> playgroundKeys = new ArrayList<>();
        playgroundKeys.add("active_secret_key_123");
        playgroundKeys.add("grace_secret_key_999");
        merchantSecrets.put(playgroundMerchant, playgroundKeys);
    }

    /**
     * Registers keys for a merchant dynamically (very useful for isolated unit/integration tests).
     */
    public void registerSecrets(UUID merchantId, List<String> secrets) {
        if (merchantId != null && secrets != null) {
            merchantSecrets.put(merchantId, new ArrayList<>(secrets));
        }
    }

    /**
     * Clear all registered secrets (reset state between test executions).
     */
    public void clear() {
        merchantSecrets.clear();
    }

    @Override
    public List<String> resolveSecrets(UUID merchantId) {
        if (merchantId == null) {
            return Collections.emptyList();
        }
        return merchantSecrets.getOrDefault(merchantId, Collections.emptyList());
    }
}
