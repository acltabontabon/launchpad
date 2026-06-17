package com.acltabontabon.launchpad.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Shared primitives for deriving stable identity from model content: a
 * kebab-case slug and a SHA-256 content hash. Both deterministic and
 * locale-independent, so generated artifacts are reproducible across runs and
 * machines.
 * <p>
 * Single home for the id/hash scheme the model assemblers stamp into their
 * sidecars, so the scheme stays consistent across {@code project-context.json}
 * and {@code project.model.json}.
 */
public final class ModelIdentity {

    private ModelIdentity() {}

    /** Lowercase, non-alphanumeric runs collapsed to {@code -}, trimmed of leading/trailing {@code -}. */
    public static String slug(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** Lowercase hex SHA-256 of {@code input}. */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this branch is unreachable.
            return Integer.toHexString(input.hashCode());
        }
    }
}
