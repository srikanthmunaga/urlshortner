package com.schwab.urlshortener.util;

import java.security.SecureRandom;

/**
 * Generates random Base62 short codes.
 *
 * Design decision: random generation + DB uniqueness check-and-retry, rather than
 * encoding the DB auto-increment ID directly.
 * Rationale: encoding the sequential ID leaks creation order/volume (e.g. competitors
 * can infer traffic by watching IDs increment) and makes codes guessable/enumerable.
 * Random generation avoids that at the cost of a rare collision retry, which is cheap
 * at this scale (7 chars = 62^7 ≈ 3.5 trillion possibilities).
 */
public final class Base62Encoder {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Base62Encoder() {}

    public static String generate(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
