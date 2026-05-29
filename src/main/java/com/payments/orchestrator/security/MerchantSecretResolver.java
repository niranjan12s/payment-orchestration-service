package com.payments.orchestrator.security;

import java.util.List;
import java.util.UUID;

public interface MerchantSecretResolver {

    /**
     * Resolves the list of secrets for the merchant.
     * The first element in the list is the ACTIVE key.
     * The subsequent elements represent valid grace-period keys.
     * Returns an empty list if the merchant is not found or inactive.
     */
    List<String> resolveSecrets(UUID merchantId);
}
