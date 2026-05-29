package com.payments.orchestrator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityUtils {

    private static final ObjectMapper mapper = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private SecurityUtils() {}

    /**
     * Parse raw JSON, recursively sort all maps/objects by keys, and re-serialize to standard format.
     */
    public static String canonicalizeJson(String rawJson) throws Exception {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            return "";
        }
        // Deserialize to standard Map to force parsing and key-sorting
        Object parsed = mapper.readValue(rawJson, Object.class);
        // Serialize back using sorting feature
        return mapper.writeValueAsString(parsed);
    }

    /**
     * Computes the SHA-256 hash of a string, returning a lowercase Hexadecimal representation.
     */
    public static String sha256Hex(String input) throws Exception {
        if (input == null) {
            input = "";
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] sha256DigestBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte digestByte : sha256DigestBytes) {
            String hex = Integer.toHexString(0xff & digestByte);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Computes the HmacSHA256 signature of a message using a secret key, returning a Base64-encoded string.
     */
    public static String hmacSha256Base64(String hmacSecretKey, String message) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec hmacKeySpec = new SecretKeySpec(hmacSecretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        hmac.init(hmacKeySpec);
        byte[] rawHmac = hmac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(rawHmac);
    }

    /**
     * Compares signature strings without early exit to reduce timing leakage.
     */
    public static boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
    }
}
