package com.payments.orchestrator.security;

public final class MaskingUtils {

    private MaskingUtils() {}

    /**
     * Masks sensitive string values, keeping the first 4 and last 4 characters.
     * Useful for obfuscating secrets, vault references, or key signatures.
     */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 8) {
            return "***masked***";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }
}
